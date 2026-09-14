package org.sscc.ssccopsserver.domain.assistant.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;

/*
 * 적용 상태 전환 요청 (#401 · `PATCH /v1/assistant/documents/{id}/apply-status` · 기획안 §5.5).
 *
 * **바꿀 값을 그대로 받고 성립 여부는 엔티티의 전이표가 본다.** 여기서 «`EFFECTIVE`만 받는다»로
 * 좁히면 «되돌릴 수 없다»가 요청 모양과 전이표 두 곳에 적히기 시작한다 — 실제로 성립하는 것은
 * `DRAFT → EFFECTIVE`와 `EFFECTIVE → SUPERSEDED` 둘뿐이고, 나머지는 400
 * `INVALID_RAG_APPLY_STATUS_TRANSITION`으로 끊긴다(`RagApplyStatus.canTransitionTo`).
 *
 * **색인 상태는 여기 없다** — 사람이 정하는 값이 아니라 워커가 적는 값이다(§10). 화면의
 * «재색인»은 상태 지정이 아니라 `POST …/reindex`로 다시 줄을 세우는 조작이다.
 *
 * `effectiveFrom`은 {@code EFFECTIVE}로 올릴 때만 쓰이며 **비워 보내면 오늘이 들어간다** —
 * 답변에 붙는 «YYYY-MM-DD 시행 기준» 배지의 값이라 비어 있으면 화면이 그 배지를 그리지 못한다
 * (§5.5). 의결일이 따로 있으면 그 날짜를 실어 보낸다.
 */
public record RagDocumentApplyStatusUpdateRequest(
        @NotNull(message = "바꿀 적용 상태(applyStatus)는 필수입니다.") RagApplyStatus applyStatus,
        LocalDate effectiveFrom) {}
