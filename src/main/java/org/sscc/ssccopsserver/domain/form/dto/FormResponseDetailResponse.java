package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;

/*
 * 응답 단건 상세 (#37 · GET /v1/forms/{formId}/responses/{formRspnsId}).
 *
 * 목록 항목에 응답 내용(rspnsCn)·응답자의 기수·학년·연락처·인접 응답 식별자를 더한 것이다.
 *
 * prevFormRspnsId / nextFormRspnsId는 상세 화면의 '이전 · 다음' 이동을 위한 값이다
 * (ssccops-web #13이 이미 이 이름으로 소비하고 있다). 웹이 목록을 들고 다니며 계산하는 방법도
 * 있었지만, 그러면 상세를 URL로 바로 열었을 때(새 탭·북마크·알림 링크) 이동 버튼이 죽는다 —
 * 목록을 거쳐 들어왔는지에 따라 화면 기능이 달라지는 것을 피한다.
 *
 * 인접 응답은 **목록의 기본 조회와 같은 순서·같은 범위**에서 고른다. 즉 DRAFT는 이웃이 되지
 * 않으며(심사 대상이 아니다), 정렬도 목록과 같은 '제출 일시 내림차순'이다. 목록에서 본 순서와
 * 이동 순서가 어긋나면 '이전'을 눌렀는데 방금 본 응답이 나오지 않는다.
 *
 * 목록에 없는 응답(DRAFT)을 직접 열면 두 값은 모두 null이다 — 이웃을 만들어 주면 심사 목록에서
 * 빠져 있던 응답이 이동만으로 심사 흐름 안에 들어온다.
 *
 * 처리 이력(reviewHistories)은 #141에서 더했다. 별도 엔드포인트를 두지 않은 것은 화면이 상세와
 * 이력을 언제나 함께 그리기 때문이다 — 나누면 상세를 여는 모든 화면이 두 번 요청하고, 두 응답
 * 사이에 다른 검토자의 처리가 끼어들면 화면의 상태와 타임라인이 서로 다른 시점을 가리킨다.
 * 시간순(처리 일시 오름차순)이며 처리가 없는 응답에서는 빈 배열이다 — null이 아니다.
 *
 * sbmsnSeq(제출 회차)를 함께 내리는 것은 이력의 각 줄이 몇 회차에 대한 처리였는지 읽으려면
 * "지금 몇 회차인가"라는 기준점이 필요하기 때문이다.
 *
 * rspnsSeq(응답 순번)는 #143에서 더했고 sbmsnSeq와 **다른 값이다** — 앞은 이 응답자의 몇 번째
 * 응답인가(다중 응답 폼에서 늘어난다)이고, 뒤는 그 응답을 몇 번 냈는가(수정요청 뒤 재제출에서
 * 늘어난다)다. 둘을 같은 값으로 읽으면 "2회차"가 두 번째 제안인지 첫 제안의 재제출인지 갈린다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormResponseDetailResponse(
        Long formRspnsId,
        int rspnsSeq,
        ResponseStatus rspnsSttsCd,
        OffsetDateTime sbmsnDt,
        int sbmsnSeq,
        ResponseMemberDetail member,
        ResponseContent rspnsCn,
        List<FormResponseReviewHistoryResponse> reviewHistories,
        Long prevFormRspnsId,
        Long nextFormRspnsId) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormResponseDetailResponse of(
            FormResponseHistoryEntity response,
            List<FormResponseReviewHistoryEntity> reviewHistories,
            Long previousId,
            Long nextId) {
        return new FormResponseDetailResponse(
                response.getId(),
                response.getResponseSequence(),
                response.getStatus(),
                toOffsetDateTime(response.getSubmittedAt()),
                response.getSubmissionSequence(),
                ResponseMemberDetail.from(response.getMember()),
                response.getContent(),
                reviewHistories.stream().map(FormResponseReviewHistoryResponse::from).toList(),
                previousId,
                nextId);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
