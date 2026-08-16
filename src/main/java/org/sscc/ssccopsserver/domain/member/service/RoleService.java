package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.dto.RoleCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleUpdateRequest;

/*
 * 조직 역할(role) 마스터의 관리 (#79 · ssccops#17).
 *
 * 역할↔권한 매핑은 여기가 아니라 AuthorityAdminService가 갖는다. 나누는 기준은 '무엇을 쓰는가'다 —
 * 이쪽은 role 행 하나를 만들고 고치고 지우고, 저쪽은 role_authrt_rel을 움직인다. 펼침·자기 잠금
 * 방지 같은 규칙이 전부 권한 쪽에 있어 합치면 이 서비스가 인가 규칙까지 알게 된다.
 */
public interface RoleService {

    /** 역할 목록. roleClsfCd가 null이면 전체이며 현재 보유 회원 수를 함께 내린다 */
    List<RoleResponse> getRoles(String roleClsfCd);

    /** 역할 단건. 재임 회원 목록을 함께 내린다 */
    RoleDetailResponse getRole(Long roleId);

    /** 역할 생성. indctSeqno를 생략하면 같은 분류 안의 최대값 + 1이 된다 */
    RoleResponse createRole(RoleCreateRequest request);

    /** 이름·분류·표시 순번 변경. null인 필드는 건드리지 않는다 */
    RoleResponse updateRole(Long roleId, RoleUpdateRequest request);

    /** 삭제. 배정 이력이 하나도 없고 권한도 붙어 있지 않을 때만 */
    void deleteRole(Long roleId);
}
