package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationTypeRecipientEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationTypeRecipientRepository;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;

/*
 * «이 알림은 어느 앱에 보이는가»의 유일한 구현 (#535 · ssccops#465 · ADR-0047).
 *
 * 규칙은 둘뿐이다:
 *  1. 기준표(noti_type_rcpn)에 그 유형의 행이 있으면 **그 행들의 앱 전부**.
 *  2. 행이 하나도 없으면 **그 알림 행 자신의 app_cd**(= 보낸 앱) 하나.
 *
 * 2가 있어 새 유형을 더하며 시드를 빠뜨려도 알림이 사라지지 않는다 — «아직 안 정함»의 안전한
 * 기본값이고, 지금 TEST가 그 규칙으로 도는 유일한 유형이다(누른 앱이 곧 수신 앱).
 *
 * **발송과 조회가 같은 이 클래스를 쓴다**(PushDispatcher · NotificationServiceImpl). 두 벌로
 * 두면 «목록에는 있는데 푸시는 안 오는» 알림이 조용히 생긴다 — AuthorityPolicy가 인가와
 * capabilities를 한 메서드로 답하는 것과 같은 판단이다.
 *
 * ── 캐시 ────────────────────────────────────────────────────────────────────
 * 표 전체를 한 항목으로 들고 있다가 쓰기가 비운다(ADR-0047 «저장할 때 비운다»). 항목을 유형별로
 * 쪼개지 않은 것은 «미등록»의 판정이 표 전체를 알아야 나오기 때문이다 — 유형별 캐시라면 행이
 * 없는 유형마다 매번 질의가 돈다. 표가 최대 36행이라 통째로 드는 비용이 없다.
 *
 * expireAfterWrite를 함께 두는 것은 인스턴스가 둘 이상이 될 때의 바닥이다. 지금은 한 대라
 * 무효화만으로 충분하지만, 늘어나면 다른 대의 캐시는 이 무효화를 못 보고 «5분 안에 반영»이
 * 최악값이 된다. Redis·이벤트 버스로 무효화를 퍼뜨리는 안은 기각 — 정책이 바뀌는 빈도가
 * 학기당 몇 번이고, 그 대가로 인프라 한 겹을 더 세울 이유가 없다.
 *
 * 캐시에 담는 것은 «등록된 유형만»이다. 미등록은 키가 없는 것으로 나타내며, 빈 집합을 값으로
 * 넣지 않는다 — 두 상태(«미등록» vs «앱이 하나도 없음»)를 같은 모양으로 표현하면 기본값 규칙이
 * 무너진다. 쓰기 쪽이 빈 배열을 400으로 막는 것도 같은 이유다.
 */
@Slf4j
@Component
public class NotificationRoutingPolicy {

    /* 항목 하나짜리 캐시의 키. 값이 «표 전체»라 키에 담을 것이 없다 */
    private static final String TABLE = "noti_type_rcpn";

    /* 여러 대로 늘었을 때의 바닥. 한 대뿐인 지금은 invalidate가 먼저 닿는다 */
    private static final Duration STALENESS_FLOOR = Duration.ofMinutes(5);

    private final NotificationTypeRecipientRepository recipientRepository;

    private final Cache<String, Map<NotificationType, Set<NotificationApp>>> cache =
            Caffeine.newBuilder().expireAfterWrite(STALENESS_FLOOR).maximumSize(1).build();

    public NotificationRoutingPolicy(NotificationTypeRecipientRepository recipientRepository) {
        this.recipientRepository = recipientRepository;
    }

    /**
     * 이 알림이 보일 앱 전부. {@code sendingApp}은 그 알림 행의 {@code app_cd}이며 미등록 유형의 답이 된다.
     *
     * <p>돌려주는 집합은 언제나 비어 있지 않다 — 표가 답하지 못하면 보낸 앱 하나다.
     */
    public Set<NotificationApp> appsFor(NotificationType type, NotificationApp sendingApp) {
        Set<NotificationApp> registered = table().get(type);
        return registered != null ? registered : EnumSet.of(sendingApp);
    }

    /** 이 알림이 저 앱에 보이는가 — 발송(구독의 앱)과 조회(요청한 앱)가 함께 쓰는 판정 */
    public boolean deliversTo(
            NotificationType type, NotificationApp sendingApp, NotificationApp target) {
        return appsFor(type, sendingApp).contains(target);
    }

    /**
     * 기준표가 이 앱으로 보내라고 적어 둔 유형들. 목록·배지 질의가 «유형이 이 집합에 들면 무조건 보인다»로 쓴다.
     *
     * <p>미등록 유형은 여기 없다 — 그쪽은 {@link #typesFollowingSendingApp()}이 답하고 알림 행의 app_cd를 함께 봐야 한다.
     */
    public Set<NotificationType> typesRoutedTo(NotificationApp app) {
        Set<NotificationType> routed = EnumSet.noneOf(NotificationType.class);
        table().forEach(
                        (type, apps) -> {
                            if (apps.contains(app)) {
                                routed.add(type);
                            }
                        });
        return routed;
    }

    /** 기준표에 행이 없어 «보낸 앱»을 따르는 유형들. 조회 질의가 app_cd와 함께 보는 쪽이다 */
    public Set<NotificationType> typesFollowingSendingApp() {
        Map<NotificationType, Set<NotificationApp>> registered = table();
        Set<NotificationType> following = EnumSet.allOf(NotificationType.class);
        following.removeAll(registered.keySet());
        return following;
    }

    /** 편집 화면이 그리는 «등록된 유형 → 앱» 전부. 미등록 유형은 키가 없다 */
    public Map<NotificationType, Set<NotificationApp>> registeredApps() {
        return table();
    }

    /** 표를 바꾼 쪽이 부른다. 다음 조회가 DB에서 다시 읽는다 (ADR-0047) */
    public void invalidate() {
        cache.invalidateAll();
    }

    /*
     * 표 한 벌. **@Transactional을 걸지 않는다** — 이 메서드를 부르는 자리가 전부 같은 클래스
     * 안이라(appsFor·typesRoutedTo…) 프록시를 타지 않아 애노테이션이 무시될 뿐이고, 적재가
     * 하는 일은 findAll() 하나라 그 리포지토리 호출 자체의 짧은 읽기 트랜잭션으로 충분하다.
     * 발송 스레드는 트랜잭션 밖에서 이 자리를 밟는다(PushDispatcher 주석).
     */
    private Map<NotificationType, Set<NotificationApp>> table() {
        return cache.get(TABLE, key -> load());
    }

    private Map<NotificationType, Set<NotificationApp>> load() {
        Map<NotificationType, Set<NotificationApp>> loaded = new EnumMap<>(NotificationType.class);
        for (NotificationTypeRecipientEntity row : recipientRepository.findAll()) {
            NotificationType type = typeOf(row.getTypeCode());
            if (type == null) {
                continue;
            }
            loaded.computeIfAbsent(type, key -> EnumSet.noneOf(NotificationApp.class))
                    .add(row.getApp());
        }
        return loaded;
    }

    /*
     * 코드에 없는 유형 코드는 **무시한다**(V23 주석 — 이 컬럼에 CHECK가 없다). 유형을 뺀 배포
     * 직후 남아 있는 정책 행이 그 경우이고, 그 행 하나가 발송·조회를 전부 터뜨리면 안 된다.
     * WARN으로 남기는 것은 «지워도 되는 행이 있다»를 운영이 알아채는 유일한 신호이기 때문이다.
     */
    private NotificationType typeOf(String code) {
        try {
            return NotificationType.valueOf(code);
        } catch (IllegalArgumentException ex) {
            log.warn("기준표에 코드가 모르는 알림 유형이 있다 — noti_type_cd={}", code);
            return null;
        }
    }
}
