package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 역할 분류 수정 요청 (#80 PATCH /v1/role-classifications/{roleClsfCd}).
 *
 * **roleClsfCd가 본문에 없다.** 권한 수정(AuthorityUpdateRequest)은 코드를 받아 두고 바꾸려는
 * 시도를 409로 거절하지만, 그쪽은 코드를 편집란에 그려 놓은 화면이 있어 "바꿨는데 조용히
 * 무시됐다"가 생길 자리가 있었다. 역할 분류의 코드는 PK이자 role.role_clsf_cd가 NOT NULL FK로
 * 가리키는 값이라 애초에 편집 대상이 아니며, 필드를 두면 바꿀 수 있는 것처럼 읽힌다.
 * 코드를 바꾸는 경로는 '새로 만들고 → 역할을 옮기고 → 기존 것을 지운다' 하나뿐이다
 * (#65 AUTHORITY_CODE_IMMUTABLE과 같은 태도).
 *
 * indctSeqno가 null이면 현재 값을 유지한다 — 이름만 고치는 화면이 순번까지 들고 있지 않아도
 * 되게 한다.
 */
public record RoleClassificationUpdateRequest(
        @NotBlank @Size(max = 50) String roleClsfNm, Integer indctSeqno) {}
