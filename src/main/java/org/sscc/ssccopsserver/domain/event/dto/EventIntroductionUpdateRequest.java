package org.sscc.ssccopsserver.domain.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/*
 * 행사의 소개 칸 넷만 고치는 요청 (ssccops#618 · ADR-0069 참고 — 스터디장의 학술 경로
 * PUT /v1/academic-programs/{id}/event-post가 본문으로 받는다).
 *
 * 검증은 EventSaveRequest의 같은 칸을 **그대로** 옮겼다(제목 256 · 대표 이미지 200 · 장소 100 ·
 * 본문 NOT NULL). 본문 상한(10만 자 → 413 EVENT_CONTENT_TOO_LARGE)은 그쪽처럼 서비스가 건다
 * (EventServiceImpl.requireContentWithinLimit) — 두 경로가 같은 행을 쓰므로 규칙이 갈리면 한쪽에서
 * 저장된 값을 다른 쪽이 거절한다.
 *
 * 분류 · 폼 · 기간 · 정원이 없는 것이 이 타입의 요점이다 — 받지 않으면 덮어쓸 수 없다(모집 폼 문항
 * 경로 RecruitmentFormQuestionUpdateRequest가 접수 기간을 받지 않는 것과 같은 판단).
 */
public record EventIntroductionUpdateRequest(
        @NotBlank @Size(max = 256) String eventTtl,
        @NotNull String mtxtCn,
        @Size(max = 200) String thmbUrlAddr,
        @Size(max = 100) String plcNm) {}
