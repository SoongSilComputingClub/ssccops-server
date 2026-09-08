package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingCategory;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;
import org.sscc.ssccopsserver.domain.share.service.SharePreviewProvider;

import lombok.RequiredArgsConstructor;

/*
 * 회의의 공유 미리보기 (ssccops#310 · ADR-0016).
 *
 * `WorkSharePreviewProvider`가 형판이고 같은 도메인이라 새 의존이 생기지 않는다. 경계도 같다 —
 * 공유 도메인은 회의가 무엇인지 모르고 이 클래스는 토큰이 무엇인지 모른다. **삭제된 운영 건은
 * 없는 것으로 답하는 판정도 같은 질의다**(`findByIdAndOperationDeletedAtIsNull`, mtg에는 del_dt가
 * 없어 부모 oper를 본다). 빈 Optional은 폐기된 링크와 같은 404로 나간다.
 *
 * ## 요약은 회의 유형 + 일시 (ssccops#252, 2026-09-08 확정)
 *
 * <pre>정례 · 2026-09-15 19:00</pre>
 *
 * **제목은 회의 자기 것이 없다** — mtg에 제목 컬럼이 없어 부모 운영 건의 `oper_ttl`이며, 업무와
 * 같다.
 *
 * **안건 제목을 담지 않는다.** 카드가 말하는 것이 늘어나긴 하지만 인사·징계·예산처럼 익명에게
 * 나가면 곤란한 것이 섞일 수 있고, **메신저 캐시는 우리가 지울 수 없어 한 번 나간 카드를 거둘
 * 방법이 없다.** `/public/v1`에 실리는 값을 늘리는 것은 UI 결정이 아니라 보안 결정이라는 Epic의
 * 전제를 그대로 따른다.
 *
 * **`otsd_mtg_dtl_cn`(제출 요약본)도 쓰지 않는다.** 이미 밖에 낼 것을 전제로 쓰는 글이지만
 * **회의가 끝난 뒤에 쓰는 글이고 공유는 회의 전에 뿌린다** — 이 기능이 답하려는 순간("이번 주
 * 회의 이거야")에는 비어 있다. "있으면 쓰고 없으면 유형+일시"도 기각했다. 같은 종류의 링크가
 * 회의마다 다른 모양으로 나가고, 제출용으로 쓴 글이 메신저 카드에 맞는지는 별개 문제다.
 *
 * **여전히 담지 않는 것**: 진행 상태(`mtg_stts_cd`) · 안건 처리 현황 · 참석 대상(`atnd_trgt_cd`).
 * 앞의 둘은 시간이 바꾸는 값이라 카드가 굳은 뒤 사실이 아닌 것을 말하게 되고(ssccops#194 제약 ②),
 * 참석 대상은 요약에 넣기로 한 둘에 들지 않는다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingSharePreviewProvider implements SharePreviewProvider {

    /*
     * 표기 기준 시간대. `bgng_dt`가 `Instant`라 벽시계 시각으로 적으려면 시간대가 필요하고,
     * 그 기준은 서비스 타임존이다 — `MeetingDetailResponse`가 같은 값을 내려보낼 때 쓰는 것과
     * 같다(AP-12).
     */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 업무는 날짜까지만 적는데(`WorkSharePreviewProvider`) 회의는 분까지 적는다 — 업무의 기간은
     * 여러 날에 걸치지만 회의는 **몇 시에 모이는가가 곧 그 회의**라서다.
     */
    private static final DateTimeFormatter START_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 유형과 일시를 가르는 구분자. 카드 한 줄에 두 값을 담으므로 줄바꿈이 아니라 가운뎃점이다 */
    private static final String SEPARATOR = " · ";

    private final MeetingRepository meetingRepository;

    @Override
    public ShareTargetType targetType() {
        return ShareTargetType.MEETING;
    }

    @Override
    public Optional<SharePreview> preview(Long targetId) {
        return meetingRepository
                .findByIdAndOperationDeletedAtIsNull(targetId)
                .map(
                        meeting ->
                                new SharePreview(
                                        meeting.getOperation().getTitle(), summaryOf(meeting)));
    }

    /*
     * 유형 + 일시. **한쪽이 없어도 문구가 깨지지 않아야 한다** — `mtg_se_cd`·`bgng_dt` 둘 다
     * 컬럼이 NULL을 허용한다(등록 API는 둘을 필수로 받지만 그것은 이 자리가 기댈 보장이 아니다).
     *
     * | 있는 값 | 요약 |
     * |---|---|
     * | 둘 다 | `정례 · 2026-09-15 19:00` |
     * | 유형만 | `정례` |
     * | 일시만 | `2026-09-15 19:00` |
     * | 없음 | null |
     *
     * 재료가 하나뿐일 때 구분자를 남기지 않는 것은 그것이 **잘린 문자열로 보이기** 때문이다 —
     * 카드에서 사람이 보는 것은 이 문장 하나뿐이라 미완성으로 읽히면 안 된다. 둘 다 없으면
     * **서버가 대체 문구를 지어내지 않고 null로 둔다**(`SubWorkSharePreviewProvider`와 같은 규칙).
     */
    private String summaryOf(MeetingEntity meeting) {
        String typeLabel = displayNameOf(meeting.getMeetingCategory());
        String startAt = startAtOf(meeting.getOperation().getBeginAt());
        if (typeLabel == null) {
            return startAt;
        }
        return startAt == null ? typeLabel : typeLabel + SEPARATOR + startAt;
    }

    /*
     * 확정된 시각은 담을 수 있다. 카드가 한 번 굳는다는 제약이 막는 것은 *변하는* 값이지
     * *시각*이 아니다 — 업무의 기간에 대해 한 판단(ssccops#251)과 같다.
     *
     * **감수하는 것**: 일정이 바뀌면 이미 나간 카드가 옛 시각을 말한다. 메신저 캐시를 우리가
     * 지울 수 없어 되돌릴 방법이 없다. 받아들이는 근거는 회의 일시가 자주 바뀌지 않는다는
     * 것이고, 자주 바뀐다는 것이 드러나면 이 자리를 다시 본다.
     *
     * 종료 일시는 적지 않는다 — 사람이 링크를 보고 판단하는 것은 언제 모이는가이고, `end_dt`는
     * 회의에서 실제로 채워지지 않는 경우가 흔해 있는 회의와 없는 회의의 카드가 갈린다.
     */
    private String startAtOf(Instant beginAt) {
        return beginAt == null ? null : START_AT.format(beginAt.atZone(SERVICE_ZONE));
    }

    /*
     * 회의 구분의 표시명. **이 문자열은 웹이 아니라 메신저가 그린다** — 크롤러는 코드값을 받아
     * 이름으로 바꿔 줄 수 없으므로 서버가 완성된 문장을 내줄 수밖에 없다
     * (`WorkSharePreviewProvider`가 `work_type_cd`에 대해 한 판단과 같다).
     *
     * **정본은 데이터사전의 `mtg_se_cd`**이고 웹 `codes.ts`의 `MtgSeCd`가 같은 낱말을 갖는다 —
     * 갈리면 카드와 화면이 같은 회의를 다른 이름으로 부르므로, 그 표시명을 고칠 때 이 자리도
     * 함께 고친다. ssccops#252가 예로 든 `정기회의`가 아니라 `정례`인 이유가 이것이다. 이슈의
     * 작업 항목이 가리킨 것도 "회의 유형(`mtg_se_cd`) 표시명"이다.
     *
     * switch를 default 없이 두어 구분이 늘면 컴파일이 깨지게 한다 — 새 값이 조용히 빈 이름으로
     * 나가는 것보다 낫다. null은 컬럼이 NULL을 허용하는 데 대한 답이고, 그때는 유형 없이
     * 일시만 남는다.
     */
    private String displayNameOf(MeetingCategory meetingCategory) {
        if (meetingCategory == null) {
            return null;
        }
        return switch (meetingCategory) {
            case REGULAR -> "정례";
            case TOPIC -> "주제";
        };
    }
}
