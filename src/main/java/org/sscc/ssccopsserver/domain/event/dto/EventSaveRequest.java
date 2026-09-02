package org.sscc.ssccopsserver.domain.event.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/*
 * 행사 생성·수정 공용 요청 (ssccops#139 · POST /v1/events · PUT /v1/events/{eventId}).
 *
 * 두 요청이 같은 DTO를 쓰는 것은 편집 화면이 신규와 수정에 같은 편집기를 쓰기 때문이다
 * (FormSaveRequest 선례). creatrMbrId는 여기에 없다 — 생성자는 인증 주체에서 서버가 채운다.
 *
 * **상태(eventSttsCd) 필드가 아예 없다** — 폼 PUT이 실려 온 상태를 무시하는 것에서 한 발 더
 * 나간 형태다. 생성은 항상 DRAFT이고(만들자마자 공개되는 경로를 두지 않는다), 상태를 바꾸는
 * 길은 POST /v1/events/{eventId}/status 하나뿐이다.
 *
 * mtxtCn은 md 원문 그대로 저장·서빙한다(D12) — sanitize하지 않는다. 렌더링은 공개 앱의 안전
 * 렌더러 책임이다. 상한(10만 자) 검증은 Bean Validation이 아니라 서비스가 건다 — @Size로
 * 잡으면 전역 핸들러가 VALIDATION_FAILED(400)로 바꿔 계약표의 413 EVENT_CONTENT_TOO_LARGE와
 * 어긋난다 (INVALID_RECEIPT_PERIOD와 같은 이유).
 *
 * formId는 선택이다 — NULL이면 폼 없는 공지다(D11). 연결 규칙(전속·신청 발생 후 변경 금지)은
 * 서비스가 검증한다.
 *
 * 일시는 AP-12에 따라 오프셋을 포함한 RFC 3339 문자열로 주고받는다.
 */
public record EventSaveRequest(
        @NotBlank String eventClsfCd,
        @NotBlank @Size(max = 256) String eventTtl,
        @NotNull String mtxtCn,
        @Size(max = 200) String thmbUrlAddr,
        Long formId,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        @Size(max = 100) String plcNm,
        @Positive Integer ptcpLmtCnt) {}
