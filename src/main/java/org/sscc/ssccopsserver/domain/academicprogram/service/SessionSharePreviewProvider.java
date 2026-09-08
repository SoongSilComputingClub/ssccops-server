package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;
import org.sscc.ssccopsserver.domain.share.service.SharePreviewProvider;

import lombok.RequiredArgsConstructor;

/*
 * 학술 세션(회차)의 공유 미리보기 (ssccops#311 · ADR-0016).
 *
 * 클래스 이름이 `AcademicSession...`이 아닌 것은 이 도메인이 회차를 줄곧 `Session`으로
 * 부르기 때문이다(`SessionService`·`SessionRepository`·`SessionEntity`). 대상 종류 이름에
 * `ACADEMIC_` 접두어가 붙는 것은 공유 도메인 쪽 사정이다 — 거기서는 어느 도메인의 회차인지가
 * 값 이름으로만 구별된다.
 *
 * ## 제목은 계획(`crclm_artcl.ttl`)에서 온다
 *
 * **`sesn`에는 제목 컬럼이 없다.** 회차는 계획 한 건에 1:1로 붙는 실적이고
 * (`uk_sesn_crclm_artcl`), 그 회차가 무엇이었는지를 말하는 낱말은 계획의 주제다.
 * 프로그램이 event의 제목을 쓰는 것과 같은 자리이며, 회차 상세 응답도 같은 값을
 * `curriculumTtl`로 내린다.
 *
 * ## 요약은 실시일 + 공지(없으면 진행 내용)
 *
 * <pre>2026-09-15 · 다음 주까지 3장 예제 풀어 오기</pre>
 *
 * **실시일(`actl_ymd`)을 담는다.** 시간에 따라 변하는 값을 카드에 싣지 않는다는 제약
 * (ssccops#194 제약 ②)이 막는 것은 *조회 시점마다 답이 달라지는 값*이고, 실시일은 그런 값이
 * 아니라 **이미 일어난 일의 날짜**다 — 사람이 적어 넣은 확정된 사실이라는 점에서 업무의
 * 기간(ssccops#251)·회의의 일시(ssccops#252)와 같은 판단이고, 회차는 그중에서도 가장 확정적이다
 * (기록 자체가 회차가 끝난 뒤에 쓰인다). **감수하는 것**은 재제출로 날짜가 바뀌면 이미 나간
 * 카드가 옛 날짜를 말한다는 것이며, 메신저 캐시를 지울 수 없으므로 되돌릴 방법이 없다.
 *
 * **본문은 공지(`ntc_cn`)를 먼저 본다.** 이 링크를 뿌리는 이유가 회차를 부원에게 알리는
 * 것이고(ssccops#253), 공지는 바로 그 목적으로 적힌 문장이다. 공지가 없으면 진행 내용
 * (`prgrs_cn`, NOT NULL)으로 떨어진다 — **둘 다 사람이 적은 실제 값이라 대체 문구를 지어내는
 * 것이 아니다**(`WorkSharePreviewProvider`가 유형·기간을 실을 때와 같은 구별).
 *
 * ## 담지 않는 것
 *
 * 회차 상태(`sesn_stts_cd`) · 출석 인원 · 출석률을 담지 않는다. 승인 상태와 출석은 조회
 * 시점마다 달라지는 값이고 카드는 한 번 굳는다. 출석부는 그에 더해 누가 왔는지를 익명 경로로
 * 흘리는 일이라 애초에 실을 것이 아니다.
 *
 * ## 404로 감출 상태가 없다
 *
 * `sesn`에는 소프트 삭제가 없고, 저장되는 상태 셋(SUBMITTED·APPROVED·REVISION_REQUESTED)은
 * 전부 스터디장이 실제로 적어 넣은 기록이다. NOT_SUBMITTED는 저장되는 값이 아니라 **행이 없는
 * 상태**를 가리키는 파생 값이라(`SessionStatus`) 그 회차에는 발급할 대상 자체가 없다.
 *
 * **승인 전(SUBMITTED·REVISION_REQUESTED) 회차를 감추지 않는 것**은 회차 상세 조회가 상태로
 * 감추지 않기 때문이다 — 미리보기가 감추는 것은 상세가 감추는 것과 같아야 한다. 감췄다면
 * 국장 승인이 나기 전에는 공지를 뿌릴 수 없게 되는데, 뿌리는 시점이 바로 그때다.
 *
 * 그래서 빈 Optional이 되는 경우는 **없는 회차 하나**이며, 그때 응답은 폐기된 링크와 같은
 * 404다. 다른 활동의 회차인가는 여기서 묻지 않는다 — 토큰이 이미 회차 하나를 못 박고 있어
 * 경로로 좁힐 때와 달리 남의 활동으로 흘러갈 자리가 없다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionSharePreviewProvider implements SharePreviewProvider {

    /* 익명에게 내주는 본문의 상한. 근거는 `SubWorkSharePreviewProvider`와 같다 — 새는 양이다 */
    private static final int SUMMARY_LIMIT = 500;

    /*
     * `actl_ymd`는 `LocalDate`라 시간대 변환이 없다 — 업무의 기간(`Instant`)과 갈리는 지점이며,
     * 그래서 하루 어긋날 여지 자체가 없다.
     */
    private static final DateTimeFormatter REAL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 실시일과 본문을 가르는 구분자. 카드 한 줄에 두 값을 담으므로 줄바꿈이 아니라 가운뎃점이다 */
    private static final String SEPARATOR = " · ";

    private final SessionRepository sessionRepository;

    @Override
    public ShareTargetType targetType() {
        return ShareTargetType.ACADEMIC_SESSION;
    }

    /*
     * 제목이 계획에 있으므로 계획을 함께 끌어온다 — LAZY 그대로면 조회가 한 번 더 나간다.
     */
    @Override
    public Optional<SharePreview> preview(Long targetId) {
        return sessionRepository
                .findWithCurriculumItemById(targetId)
                .map(
                        session ->
                                new SharePreview(
                                        session.getCurriculumItem().getTitle(),
                                        summaryOf(session)));
    }

    /*
     * 실시일 + 본문. **본문이 없어도 문구가 깨지지 않아야 한다** — `prgrs_cn`이 NOT NULL이라
     * 실제로는 언제나 남지만, 공백만 든 행에 `2026-09-15 · `처럼 재료 없는 구분자가 남으면
     * 카드가 잘린 것처럼 보인다(`WorkSharePreviewProvider`가 물결을 남기지 않는 것과 같은 이유).
     */
    private String summaryOf(SessionEntity session) {
        String date = REAL_DATE.format(session.getRealDate());
        String body = bodyOf(session);
        return body == null ? date : date + SEPARATOR + body;
    }

    /*
     * 공지가 먼저다 — 뿌리는 목적이 회차를 알리는 것이라, 그 목적으로 적힌 문장이 있으면
     * 그것이 요약이다. 없으면 진행 내용으로 떨어지고, 둘 다 비면 **지어내지 않고 null이다.**
     */
    private String bodyOf(SessionEntity session) {
        String notice = session.getNoticeContent();
        String body = notice == null || notice.isBlank() ? session.getContent() : notice;
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.strip();
        return trimmed.length() <= SUMMARY_LIMIT ? trimmed : trimmed.substring(0, SUMMARY_LIMIT);
    }
}
