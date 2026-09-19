package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 모집 폼 문항 교체 요청 (#483 · PUT /v1/academic-programs/{id}/recruitment/form).
 *
 * **문항 구성 하나만 받는다.** 폼 편집(FormSaveRequest)은 제목·접수 기간·라벨·다중 응답을 함께
 * 받는데, 그중 어느 것도 스터디장/팀장의 것이 아니다:
 *
 *   접수 기간 — 학술국장이 모집 관리에서 정한다(이 이슈의 요구 3). 받지 않으면 덮어쓸 수 없다.
 *   제목      — "{행사명} 모집"으로 승인 이관이 파생한 값이라 행사명이 정본이다. 폼 제목만
 *               따로 고치면 같은 사실이 두 벌이 된다.
 *   라벨      — 폼 관리 화면의 분류 도구다. 활동 하나의 리더가 남의 분류 체계를 건드릴 자리가 아니다.
 *   다중 응답 — 모집은 1인 1건이다. 켜면 한 사람이 두 번 지원한다.
 *
 * 값을 받아 놓고 무시하는 길을 택하지 않았다. 폼 수정이 formSttsCd를 무시하는 선례(#33)가
 * 있지만 그것은 **자동 저장이 상세 응답을 그대로 되돌려 보내기 때문**이고, 여기는 화면을 새로
 * 만드는 자리라 무엇을 보낼지 우리가 정한다 — 무시는 "저장했는데 안 바뀐다"를 만드는 길이다.
 *
 * 문항 구성 자체의 검증(@Valid)을 걸지 않는 것은 FormSaveRequest와 같은 이유다 — 문항 간 상호
 * 규칙은 필드 제약으로 표현되지 않아 QuestionCompositionValidator 한 곳이 보고
 * 400 INVALID_QUESTION_COMPOSITION으로 내린다.
 */
public record RecruitmentFormQuestionUpdateRequest(
        @NotNull QuestionCompositionContent qitemCpstCn) {}
