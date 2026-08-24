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
 *
 * rspnsSeq(응답 순번)를 싣는 것은 #143부터다. 다중 응답 폼에서는 이 요청이 새 응답을 만들었는지
 * 이어 쓰던 응답을 낸 것인지가 응답자 화면에서 갈리는데, 식별자만으로는 그것을 알 수 없다.
 */
public record FormResponseSubmitResponse(
        Long formRspnsId, int rspnsSeq, ResponseStatus rspnsSttsCd, OffsetDateTime sbmsnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormResponseSubmitResponse from(FormResponseHistoryEntity response) {
        return new FormResponseSubmitResponse(
                response.getId(),
                response.getResponseSequence(),
                response.getStatus(),
                toOffsetDateTime(response.getSubmittedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
