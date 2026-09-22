package org.sscc.ssccopsserver.domain.notification.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.code.error.NotificationErrorCode;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationTypeRouteResponse;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationTypeRecipientEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationTypeRecipientRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;

/*
 * 기준표 편집의 구현 (#535 · #537 · ADR-0047).
 *
 * **수정은 «그 유형의 행을 지우고 새로 넣는다»다.** 차집합을 계산해 더하고 빼는 안은 기각 —
 * 한 유형의 행이 최대 셋이라 얻는 것이 없고, 부분 실패가 «절반만 바뀐 정책»을 남긴다. 한
 * 트랜잭션 안이라 지우고 넣는 사이의 빈 상태는 밖에서 보이지 않는다.
 *
 * **캐시는 커밋 여부와 무관하게 비운다**(트랜잭션 끝에서가 아니라 메서드 안에서). 롤백된 변경
 * 뒤에 캐시가 비어 있으면 다음 조회가 DB를 다시 읽을 뿐 답이 달라지지 않는다 — 반대로 커밋 뒤에만
 * 비우도록 동기화를 걸면 «비우지 못한 채 끝나는 경로»가 하나 생긴다. 싼 쪽이 안전한 쪽이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationTypeRoutingServiceImpl implements NotificationTypeRoutingService {

    private final NotificationTypeRecipientRepository recipientRepository;
    private final NotificationRoutingPolicy routingPolicy;
    private final AuditLog auditLog;

    @Override
    public List<NotificationTypeRouteResponse> listTypes() {
        Map<NotificationType, Set<NotificationApp>> registered = routingPolicy.registeredApps();
        List<NotificationTypeRouteResponse> rows = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            Set<NotificationApp> apps = registered.get(type);
            rows.add(
                    apps == null
                            ? NotificationTypeRouteResponse.followingSendingApp(type)
                            : NotificationTypeRouteResponse.routed(type, sorted(apps)));
        }
        return rows;
    }

    @Override
    @Transactional
    public NotificationTypeRouteResponse replaceApps(String typeCode, List<NotificationApp> apps) {
        NotificationType type = resolve(typeCode);
        // 같은 앱을 두 번 보내도 행이 둘이 되지 않는다(uk_noti_type_rcpn) — 여기서 접는다.
        // null 원소는 본문에 박힌 JSON null이라 «지정하지 않음»으로 보고 함께 걷어낸다
        Set<NotificationApp> distinct = EnumSet.noneOf(NotificationApp.class);
        if (apps != null) {
            apps.stream().filter(Objects::nonNull).forEach(distinct::add);
        }
        if (distinct.isEmpty()) {
            throw new GeneralException(NotificationErrorCode.EMPTY_NOTIFICATION_ROUTE);
        }
        Set<NotificationApp> current = routingPolicy.registeredApps().get(type);
        List<NotificationApp> before = current == null ? List.of() : sorted(current);

        recipientRepository.deleteByTypeCode(type.name());
        recipientRepository.saveAll(
                sorted(distinct).stream()
                        .map(app -> NotificationTypeRecipientEntity.route(type, app))
                        .toList());
        routingPolicy.invalidate();

        List<NotificationApp> after = sorted(distinct);
        auditLog.record(
                AuditEvent.success(AuditAction.NOTIFICATION_TYPE_ROUTE)
                        .target(type.name())
                        .change(join(before), join(after))
                        .build());
        return NotificationTypeRouteResponse.routed(type, after);
    }

    /*
     * 되돌리기 (#537). **PUT의 «최소 한 앱»을 완화하는 대신 조작을 하나 더 만든 것**이다 —
     * 빈 배열 PUT은 «체크를 다 끄고 저장을 눌렀다»라는 실수와 구별되지 않지만, DELETE는 그 자체가
     * 의도다. ADR-0047이 막으려는 것은 «행은 있는데 앱이 없는» 상태이지 기본값으로 돌아가는 길이
     * 아니고, 그 길이 없어 한 번 정한 유형을 «아직 안 정함»으로 되돌릴 수 없던 것이 이 이슈다.
     *
     * **멱등이다** — 이미 미등록인 유형에도 성공으로 답한다. 없는 행을 지우라는 요청은 요청자가
     * 원한 상태가 이미 참이라는 뜻이라 오류가 아니며(HTTP DELETE의 관례), 404를 내면 «이 유형은
     * 없다»(유형 코드 오류)와 «이 유형에 행이 없다»(정상 상태)가 같은 응답이 되어 화면이 둘을
     * 가르지 못한다. 유형 해석 실패만 404다.
     *
     * 감사 로그의 after는 `NONE`이다 — 빈 목록이 곧 «미등록 = 보낸 앱을 따른다»이고(join 주석),
     * 같은 상태를 PUT의 before와 다른 문자열로 적으면 한 유형의 이력에 이름이 둘이 된다.
     * 아무것도 바뀌지 않은 호출도 남긴다: 정책을 건드린 사람이 있었다는 사실이 감사의 내용이고,
     * «바뀐 것이 없으면 조용히»는 PUT(같은 값을 다시 저장해도 남는다)과도 어긋난다.
     */
    @Override
    @Transactional
    public NotificationTypeRouteResponse clearApps(String typeCode) {
        NotificationType type = resolve(typeCode);
        Set<NotificationApp> current = routingPolicy.registeredApps().get(type);
        List<NotificationApp> before = current == null ? List.of() : sorted(current);

        recipientRepository.deleteByTypeCode(type.name());
        routingPolicy.invalidate();

        auditLog.record(
                AuditEvent.success(AuditAction.NOTIFICATION_TYPE_ROUTE)
                        .target(type.name())
                        .change(join(before), join(List.of()))
                        .build());
        return NotificationTypeRouteResponse.followingSendingApp(type);
    }

    /*
     * 경로 값이 유형인가. 문자열로 받아 여기서 판정하는 것은 enum 변환 실패가 봉투 없는
     * ProblemDetail로 나가기 때문이다(NotificationErrorCode.NOTIFICATION_TYPE_NOT_FOUND 주석).
     */
    private NotificationType resolve(String typeCode) {
        try {
            return NotificationType.valueOf(typeCode);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new GeneralException(NotificationErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
        }
    }

    /** 앱 순서는 enum 선언 순으로 고정한다 — 응답이 호출마다 흔들리면 화면·테스트가 정렬을 한 벌 더 쓴다 */
    private List<NotificationApp> sorted(Set<NotificationApp> apps) {
        return apps.isEmpty() ? List.of() : List.copyOf(EnumSet.copyOf(apps));
    }

    /** 감사 로그의 전/후. 코드값을 쉼표로 이은 문자열이고 비어 있으면 «미등록»이다 */
    private String join(List<NotificationApp> apps) {
        return apps.isEmpty()
                ? "NONE"
                : String.join(",", apps.stream().map(NotificationApp::name).toList());
    }
}
