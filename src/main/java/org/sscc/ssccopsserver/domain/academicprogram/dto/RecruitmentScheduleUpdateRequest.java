package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.OffsetDateTime;

/*
 * 모집 일정 변경 요청 (PATCH /v1/academic-programs/{academicProgramId}/recruitment/schedule).
 *
 * 모집을 시작할 때 정한 접수 기간을 **그 뒤에 다시 고치는** 유일한 경로다. 시작 시각을 정하는
 * 경로(START_RECRUITMENT)와 나누는 이유는 그쪽이 전이이기 때문이다 — 전이는 APPROVED에서만
 * 일어나고 폼 OPEN·행사 게시까지 함께 하므로, 이미 ONGOING인 활동의 날짜만 고치려고 그것을
 * 다시 부를 수 없다(409 INVALID_ACADEMIC_PROGRAM_TRANSITION).
 *
 * **두 필드 모두 null을 허용하고, null은 "제한 없음"이다.** 비워서 보내면 그 끝을 지운다 —
 * 종료를 비우면 수동으로 마감할 때까지 열리고, 시작을 비우면 곧바로 접수가 열린다
 * (FormReceiptPolicy에서 NULL의 뜻이 그렇다). "안 바꿈"을 표현하는 자리가 없는 것은 의도이며,
 * 화면이 언제나 현재 값을 채워 보내는 전체 교체다 — 부분 갱신으로 두면 "비우기"와 "그대로"가
 * 같은 null이 되어 구별되지 않는다.
 *
 * 기간 역전(end < begin) 검증은 Bean Validation이 아니라 FormEntity.requireValidReceiptPeriod가
 * 한다 — AcademicProgramTransitionRequest와 같은 판단이며, 폼 도메인의 INVALID_RECEIPT_PERIOD를
 * 그대로 전파해야 계약표와 어긋나지 않는다.
 */
public record RecruitmentScheduleUpdateRequest(
        OffsetDateTime rcptBgngDt, OffsetDateTime rcptEndDt) {}
