package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 응답 목록 항목 (#37 · GET /v1/forms/{formId}/responses).
 *
 * 응답 내용(rspnsCn)을 싣지 않는다. 목록 표가 그리는 것은 응답자·제출 일시·상태뿐인데,
 * 답 전체를 함께 실으면 모집 폼 한 회차의 목록 응답이 문항 수 × 응답 수만큼 커진다.
 * 답이 필요하면 상세를 부른다.
 *
 * sbmsnDt는 DRAFT인 응답에서 null이다 (ssccops #64 · 제출하지 않은 응답은 제출 일시를 가질 수
 * 없다). 그 응답은 statusCode=DRAFT를 명시했을 때만 목록에 나온다.
 *
 * 상태 변경(PATCH .../status)의 응답 본문도 이 모양을 쓴다 — 웹은 변경 후 재조회로 화면을
 * 맞추므로 본문을 읽지 않지만, 응답 없는 200을 돌려주면 ApiResponse 봉투만 남아 무엇이 바뀌었는지
 * 확인할 방법이 사라진다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormResponseSummaryResponse(
        Long formRspnsId,
        ResponseStatus rspnsSttsCd,
        OffsetDateTime sbmsnDt,
        ResponseMemberSummary member) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormResponseSummaryResponse from(FormResponseHistoryEntity response) {
        return new FormResponseSummaryResponse(
                response.getId(),
                response.getStatus(),
                toOffsetDateTime(response.getSubmittedAt()),
                ResponseMemberSummary.from(response.getMember()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
