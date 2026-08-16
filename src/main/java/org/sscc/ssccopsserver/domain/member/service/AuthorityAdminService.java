package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.dto.AuthorityCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityResponse;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityTreeResponse;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityUpdateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleAuthorityResponse;

/*
 * 권한 트리와 역할↔권한 부여의 관리 (#65 · ssccops#70).
 *
 * 판정(AuthorityPolicy)과 관리(이 서비스)를 나눈 것은 방향이 반대이기 때문이다 — 저쪽은
 * "이 회원이 무엇을 할 수 있는가"를 읽기만 하고, 이쪽은 그 근거가 되는 데이터를 쓴다.
 * 다만 펼침·순환 같은 규칙은 여기서 다시 구현하지 않고 AuthorityPolicy와 AuthorityEntity의
 * 것을 그대로 부른다.
 */
public interface AuthorityAdminService {

    /** 권한 트리 전체 (children 중첩) */
    List<AuthorityTreeResponse> getAuthorityTree();

    /** 사용자 정의 묶음 권한 생성 (항상 sys_yn = false) */
    AuthorityResponse createAuthority(AuthorityCreateRequest request);

    /** 이름·설명·상위·표시 순번 변경. 코드(PK)와 sys_yn은 바뀌지 않는다 */
    AuthorityResponse updateAuthority(String authrtCd, AuthorityUpdateRequest request);

    /** 삭제. sys_yn = false이고 어느 역할에도 부여되지 않았으며 자식이 없을 때만 */
    void deleteAuthority(String authrtCd);

    /** 역할에 부여된 권한(직접 부여 + 펼친 결과) */
    RoleAuthorityResponse getRoleAuthorities(Long roleId);

    /**
     * 역할의 권한 전체 교체. requesterMemberId는 자기 잠금 방지(VR-M13)를 위해 받는다 — 교체 결과로 요청자 자신이 ROLE_MANAGE를 잃으면
     * 거절한다.
     */
    RoleAuthorityResponse replaceRoleAuthorities(
            Long roleId, List<String> authrtCds, Long requesterMemberId);
}
