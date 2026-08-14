package org.sscc.ssccopsserver.domain.form.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/*
 * 폼의 라벨 지정 요청 (PUT /v1/forms/{formId}/labels).
 *
 * 부분 추가/삭제가 아니라 전체 교체다. 편집 화면이 "현재 선택된 칩 전체"를 보내는 구조라
 * (entities/form/model/store.ts의 setFormLbls) 교체 쪽이 화면과 정확히 맞는다.
 *
 * 빈 배열은 "전부 해제"라는 뜻이므로 정상 요청이다 — @NotEmpty가 아니라 @NotNull인 이유다.
 * 반대로 필드 자체가 빠진 요청은 "라벨을 건드리지 마라"인지 "전부 지워라"인지 알 수 없어
 * 400으로 끊는다.
 */
public record FormLabelAssignRequest(@NotNull List<@NotNull Long> labelIds) {}
