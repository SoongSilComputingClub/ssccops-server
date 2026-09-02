package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;

/*
 * 처리 이력 한 줄 (#141 · FormResponseDetailResponse.reviewHistories).
 *
 * 별도 엔드포인트를 만들지 않고 응답 상세에 실어 내리는 것은 화면이 상세와 이력을 언제나 함께
 * 그리기 때문이다 — 나누면 상세를 여는 모든 화면이 두 번 요청하고, 두 응답 사이에 처리가
 * 끼어들면 화면의 상태와 타임라인이 어긋난다.
 *
 * 처리자_명(prcsMbrNm)은 이력 행이 아니라 mbr에서 온다. 이름이 바뀌면 이력에 보이는 이름도
 * 함께 바뀌는 것이 맞다 (FormResponseReviewHistoryEntity 주석). 식별자를 함께 내리는 것은 웹이
 * 회원 상세로 이동하는 링크를 걸기 때문이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 *
 * 처리 구분 필드는 prcsSeCd가 아니라 rvwPrcsSeCd다(#224 · ssccops#159). 컬럼(rvw_prcs_se_cd)에서
 * 유도한 이름이며, 개명의 이유는 회의 안건(mtg_dtl)이 같은 이름의 컬럼을 다른 값 집합으로 쓰고
 * 있어 데이터사전의 표준코드 그룹(코드그룹ID = 컬럼ID)에 둘을 함께 담을 수 없었기 때문이다.
 * 이 필드가 이번 개명에서 유일하게 움직이는 API 계약이므로 웹과 동시에 배포한다.
 */
public record FormResponseReviewHistoryResponse(
        Long formRspnsRvwHstryId,
        int sbmsnSeq,
        ResponseReviewAction rvwPrcsSeCd,
        Long prcsMbrId,
        String prcsMbrNm,
        String rvwOpnnCn,
        OffsetDateTime prcsDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormResponseReviewHistoryResponse from(FormResponseReviewHistoryEntity history) {
        return new FormResponseReviewHistoryResponse(
                history.getId(),
                history.getSubmissionSequence(),
                history.getAction(),
                history.getProcessor().getId(),
                history.getProcessor().getName(),
                history.getOpinion(),
                toOffsetDateTime(history.getProcessedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
