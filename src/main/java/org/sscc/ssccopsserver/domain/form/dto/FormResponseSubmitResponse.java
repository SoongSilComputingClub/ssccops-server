package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 응답 제출 결과 (#35 · POST /v1/forms/{formId}/responses).
 *
 * 저장된 답(rspnsCn)을 되돌려주지 않는다. 웹은 방금 자기가 보낸 값을 이미 들고 있고, 서버가
 * 정리한 결과(빈 값 제거·단일선택 배열 벗기기)까지 필요해지는 것은 응답 조회(#37)의 몫이다.
 *
 * sbmsnDt는 요청이 준 값이 아니라 서버가 주입된 Clock에서 찍은 값이다 — 접수 마감 판정과 같은
 * 시계를 쓴다.
 */
public record FormResponseSubmitResponse(
        Long formRspnsId, ResponseStatus rspnsSttsCd, OffsetDateTime sbmsnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormResponseSubmitResponse from(FormResponseHistoryEntity response) {
        return new FormResponseSubmitResponse(
                response.getId(),
                response.getStatus(),
                toOffsetDateTime(response.getSubmittedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
