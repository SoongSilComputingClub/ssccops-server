package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;

/*
 * 작성 중 응답 (#36 · GET·PUT /v1/forms/{formId}/responses/draft).
 *
 * 조회와 저장이 같은 record를 쓴다. 저장 응답에 rspnsCn을 함께 실어 주는 것은 서버가 저장하며
 * 내용을 정리하기 때문이다 — 빈 값인 key를 빼고 단일선택 배열을 벗기므로, 보낸 값과 저장된 값이
 * 언제나 같지는 않다. 저장 결과를 그대로 돌려주면 웹은 다음 저장을 자기가 보낸 값이 아니라 서버가
 * 실제로 들고 있는 값 위에서 시작할 수 있다.
 *
 * mdfcnDt는 웹이 "마지막 저장 시각"으로 표시하는 값이다. sbmsnDt를 싣지 않는 것은 이 응답이
 * 언제나 DRAFT이고 DRAFT의 제출 일시는 정의상 NULL이기 때문이다 — 항상 null인 필드를 계약에
 * 넣으면 읽는 쪽이 그 의미를 추측하게 된다.
 */
public record FormResponseDraftResponse(
        Long formRspnsId,
        ResponseStatus rspnsSttsCd,
        ResponseContent rspnsCn,
        OffsetDateTime mdfcnDt) {

    /** 서비스 표준 시간대 (AP-12). 제출 응답(FormResponseSubmitResponse)과 같은 기준이다 */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormResponseDraftResponse from(FormResponseHistoryEntity response) {
        return new FormResponseDraftResponse(
                response.getId(),
                response.getStatus(),
                response.getContent(),
                toOffsetDateTime(response.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
