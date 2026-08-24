package org.sscc.ssccopsserver.domain.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 행사 분류 수정 요청 (ssccops#140 · PATCH /v1/event-categories/{eventClsfCd}).
 *
 * eventClsfCd가 본문에 없다 — PK이자 event.event_clsf_cd가 NOT NULL FK로 가리키는 값이라
 * 애초에 편집 대상이 아니며, 필드를 두면 바꿀 수 있는 것처럼 읽힌다. 코드를 바꾸는 경로는
 * '새로 만들고 → 행사를 옮기고 → 기존 것을 지운다' 하나뿐이다 (RoleClassificationUpdateRequest
 * 선례).
 *
 * indctSeqno가 null이면 현재 값을 유지한다 — 이름만 고치는 화면이 순번까지 들고 있지 않아도
 * 되게 한다.
 */
public record EventCategoryUpdateRequest(
        @NotBlank @Size(max = 50) String eventClsfNm, Integer indctSeqno) {}
