package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 내 응답 목록 항목 (#143 · GET /v1/forms/{formId}/responses/mine).
 *
 * 운영자용 목록(FormResponseSummaryResponse)과 스키마를 나눈다. 저쪽은 남의 응답을 심사하는
 * 화면이라 응답자 정보(회원_명·학번·학과·등급·상태)를 싣지만, 여기서는 그 회원이 요청 주체
 * 본인이라 실을 이유가 없다 — 공개 경로와 운영자 경로를 나눠 온 규칙(#35)을 응답 목록에도
 * 그대로 적용한다.
 *
 * **응답 내용(rspnsCn)을 싣지 않는다.** 응답자가 자기가 낸 답을 다시 읽는 화면은 아직 없고
 * (#141이 "수정요청 사유를 읽고 이전 답을 불러오는 길"을 별도 이슈로 미뤄 두었다), 목록에
 * 미리 실어 두면 그 화면이 정해질 때 쓰지 않는 계약이 이미 굳어 있다. 지금 필요한 것은
 * "몇 건을 냈고 각각 어떤 상태인가"다.
 *
 * rspnsSeq(응답 순번)와 sbmsnSeq(제출 회차)를 함께 싣는다. **다른 값이다** — 앞은 이 회원의
 * 몇 번째 응답인가이고 뒤는 그 응답을 몇 번 냈는가다. 수정요청을 받아 다시 낸 응답은
 * rspnsSeq가 그대로인 채 sbmsnSeq만 오른다.
 *
 * 작성 중(DRAFT) 응답도 이 목록에 실린다(sbmsnDt가 null이다). 운영자 목록이 DRAFT를 빼는 것과
 * 갈리는데, 그 규칙은 "남의 제출 전 답안이 심사 목록에 섞이지 않게" 하는 것이고 내 것을 나에게
 * 숨길 이유는 없다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record MyFormResponseSummaryResponse(
        Long formRspnsId,
        int rspnsSeq,
        ResponseStatus rspnsSttsCd,
        int sbmsnSeq,
        OffsetDateTime sbmsnDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static MyFormResponseSummaryResponse from(FormResponseHistoryEntity response) {
        return new MyFormResponseSummaryResponse(
                response.getId(),
                response.getResponseSequence(),
                response.getStatus(),
                response.getSubmissionSequence(),
                toOffsetDateTime(response.getSubmittedAt()),
                toOffsetDateTime(response.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
