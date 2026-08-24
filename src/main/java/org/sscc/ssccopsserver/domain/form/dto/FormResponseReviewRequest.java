package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;

/*
 * 검토 처리 요청 (#141 · POST /v1/forms/{formId}/responses/{formRspnsId}/reviews).
 *
 * 상태와 검토 의견을 **한 요청으로** 받는다. 상태 변경과 의견 등록을 두 경로로 나누면 하나만
 * 성공한 응답이 남는다 — 반려는 됐는데 사유가 없거나, 사유만 남고 상태는 그대로인 응답이다.
 * 웹에서도 이것은 시트 하나에 상태와 의견을 적고 저장을 누르는 한 번의 조작이다.
 *
 * 액션이 아니라 **대상 상태를 그대로 받는다** — 이전 PATCH .../status가 세운 계약을 그대로
 * 잇는다. 검토자가 고르는 것은 세 결론 중 하나이고 그 셋이 상태와 1:1이라, 액션 어휘를 따로
 * 두면 상태 어휘를 그대로 베낀 것이 된다(폼 상태 전이가 액션을 받는 것과 갈리는 지점 — 그쪽은
 * '연다/닫는다'가 현재 상태에 따라 다른 결과로 간다). 처리 구분(prcs_se_cd)은 그 상태에서
 * 유도한다 — 두 값을 함께 받으면 서로 어긋난 요청을 검사할 자리가 하나 더 생긴다.
 *
 * 기준 코드 밖의 값은 여기까지 오지 않는다. enum 역직렬화 실패를 전역 핸들러가 400
 * INVALID_CODE_VALUE로 옮긴다 (VL-09). 검토로 도달할 수 없는 상태(DRAFT · SUBMITTED)는
 * 400 INVALID_RESPONSE_STATUS_TRANSITION이다.
 *
 * rvwOpnnCn에 @NotBlank을 달지 않은 것은 필수 여부가 rspnsSttsCd에 달려 있기 때문이다 —
 * 승인은 의견이 선택이라 필드 하나만 보고는 판정할 수 없고, 조건부 검증을 Bean Validation으로
 * 표현해도 전역 핸들러가 VALIDATION_FAILED로 뭉개 웹이 "의견을 적으라"는 안내를 고를 수 없다.
 * 실제로 막는 자리는 FormResponseReviewHistoryEntity.record이며 코드는 REVIEW_OPINION_REQUIRED다.
 *
 * **처리자는 본문에 없다.** 요청이 실어 보내게 두면 "누가 했는가"를 스스로 적어 넣을 수 있어
 * 이력이 증거가 되지 못한다 — 등급·상태 변경(#78)이 세운 규칙과 같고, 값은 @CurrentMember에서 온다.
 */
public record FormResponseReviewRequest(@NotNull ResponseStatus rspnsSttsCd, String rvwOpnnCn) {}
