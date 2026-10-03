package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/*
 * 운영 건의 태그 지정 요청 (PUT /v1/operations/{operationId}/tags). 부분 추가·삭제가 아니라 **전체
 * 교체**다 — 폼 라벨(FormLabelAssignRequest)과 같은 모양이라야 MCP·화면이 두 벌이 되지 않는다.
 *
 * 빈 배열은 «전부 해제»라는 정상 요청이라 @NotEmpty가 아니라 @NotNull이다. 필드가 아예 빠진
 * 요청은 «건드리지 마라»인지 «전부 지워라»인지 알 수 없어 400으로 끊는다.
 */
public record OperationTagAssignRequest(@NotNull List<@NotNull Long> tagIds) {}
