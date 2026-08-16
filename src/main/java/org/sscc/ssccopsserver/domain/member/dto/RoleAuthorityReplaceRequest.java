package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/*
 * 역할의 권한 전체 교체 요청 (#65 PUT /v1/roles/{roleId}/authorities).
 *
 * 화면이 체크박스 트리의 상태 전체를 보내므로 부분 부여·회수가 아니라 전체 교체다. 요청에 없는
 * 권한은 회수되고 빈 배열이면 전부 회수된다.
 *
 * authrtCds 자체가 빠지면 "건드리지 마라"인지 "전부 회수하라"인지 알 수 없으므로 @NotNull이다
 * (폼 라벨 지정 교체 #34와 같은 이유).
 */
public record RoleAuthorityReplaceRequest(@NotNull List<String> authrtCds) {}
