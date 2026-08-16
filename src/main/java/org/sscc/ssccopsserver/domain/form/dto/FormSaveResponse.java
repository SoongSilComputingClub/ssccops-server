package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 폼 생성·수정 응답 (#32 · POST /v1/forms · PUT /v1/forms/{formId}).
 *
 * 문항 구성을 되돌려주지 않는다. 방금 보낸 것과 같은 값이라 왕복 비용만 늘고, 서버가 정리한
 * 결과(유형에 맞지 않는 잔여 속성 제거)를 확인해야 한다면 상세 조회가 그 자리다.
 * 대신 서버가 정한 값 — 식별자·상태·수정 일시 — 은 반드시 싣는다. 편집기가 '바로 접수 시작'을
 * 눌렀는지에 따라 저장 후 화면이 갈리기 때문이다.
 *
 * labels를 싣는 것은 라벨 지정이 폼 저장과 같은 트랜잭션에서 함께 교체되기 때문이다.
 * 결과를 안 내리면 프론트가 자기가 보낸 labelIds를 믿고 그리게 되는데, 서버가 비활성 라벨을
 * 걸러내기 시작하면 화면과 실제가 어긋난다.
 *
 * 생성과 수정이 같은 DTO를 쓰는 것은 요청과 같은 이유다 — 화면 하나가 두 경우를 모두 부른다.
 */
public record FormSaveResponse(
        Long formId,
        String formTtlNm,
        FormStatus formSttsCd,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        List<FormLabelSummaryResponse> labels,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormSaveResponse of(FormEntity form, List<FormLabelSummaryResponse> labels) {
        return new FormSaveResponse(
                form.getId(),
                form.getTitle(),
                form.getStatus(),
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                labels,
                toOffsetDateTime(form.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
