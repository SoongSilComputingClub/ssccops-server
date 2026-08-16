package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignmentResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleUpdateRequest;

/*
 * 회원 역할 배정 (#81 · ssccops#22).
 *
 * mbr_role_rel에 행을 넣는 두 번째 경로다 — 그전까지는 최초 가입자 부트스트랩(#71) 하나뿐이라
 * 두 번째 회원부터는 어떤 권한도 받을 수 없었다. 인가 체계(#9)와 역할별 권한 부여(#65)가
 * 실제로 작동하기 시작하는 자리다.
 *
 * 회원 조회·검색을 맡는 MemberService와 나눈 것은 요구 권한이 다르기 때문이다 — 이쪽은
 * MEMBER_MANAGE가 아니라 ROLE_MANAGE다. 역할 부여는 '그 사람이 무엇을 할 수 있는지'를 바꾸는
 * 조작이라 인가 쪽이며, 회원 정보를 고칠 수 있다고 스스로에게 임원 역할을 붙일 수 있으면
 * MEMBER_MANAGE가 사실상 최고 권한이 된다.
 */
public interface MemberRoleAssignmentService {

    /*
     * 회원의 역할 배정 목록. current가 true면 지금 유효한 것만(BR-M25), false면 종료된 배정까지
     * 전부다 — 종료는 삭제가 아니므로 지난 임기도 목록에 남는다.
     */
    List<MemberRoleAssignmentResponse> getAssignments(Long memberId, boolean currentOnly);

    /*
     * 역할 부여. 기간이 겹치게 같은 역할을 두 번 주지 않으며(409 ROLE_ALREADY_ASSIGNED),
     * 대표로 지정하면 기존 대표를 같은 트랜잭션에서 내린다.
     *
     * **부여는 즉시 반영된다** (BR-M31). 인가 판정이 요청마다 DB를 보므로 대상 회원은 재로그인
     * 없이 다음 요청부터 달라진다.
     */
    MemberRoleAssignmentResponse assign(Long memberId, MemberRoleAssignRequest request);

    /*
     * 종료일·대표 여부 변경. 요청자를 함께 받는 것은 자기 잠금 방지(VR-M13) 때문이다 — 이 값이
     * 없으면 마지막 ROLE_MANAGE 보유자가 자기 역할을 스스로 끝내는 것을 막을 수 없다.
     */
    MemberRoleAssignmentResponse updateAssignment(
            Long memberId, Long assignmentId, MemberRoleUpdateRequest request, Long requesterId);
}
