package org.sscc.ssccopsserver.domain.member.service;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * **자기 잠금 방지의 유일한 구현** (VR-M13 · #65에서 시작해 #81이 함께 쓴다).
 *
 * 인가를 바꾸는 조작에는 문이 둘 있다. 하나는 역할에서 권한을 회수하는 것이고(#65
 * PUT /v1/roles/{roleId}/authorities), 다른 하나는 사람에게서 역할을 떼는 것이다(#81
 * PATCH /v1/members/{memberId}/roles/{mbrRoleId} — 종료일을 채우거나 앞당긴다). 두 문이 같은
 * 방으로 이어지므로 잠금 장치도 하나여야 한다. #65의 판정을 #81이 복제했다면 한쪽만 고쳐진
 * 규칙이 다른 쪽에 남아 "권한 화면으로는 못 하지만 역할 화면으로는 되는" 우회로가 생긴다.
 *
 * **방식은 '바꾼 뒤 다시 물어보기'다.** 요청자의 역할과 대상을 비교해 미리 계산할 수도 있지만,
 * 그러면 '유효 기간·여러 역할·자손 펼침'이라는 판정 규칙을 여기서 한 벌 더 구현하게 된다.
 * 실제 상태를 만들어 놓고 AuthorityPolicy에게 물으면 규칙이 한 곳에 남고, 거절은 예외로
 * 트랜잭션을 통째로 되돌려 아무것도 반영되지 않는다.
 *
 * flush를 호출부가 아니라 여기서 하는 것은 순서가 이 검사의 전부이기 때문이다 — 변경이 DB에
 * 닿기 전에 물으면 언제나 '아직 가지고 있다'가 나와 가드가 조용히 무력해진다.
 *
 * **이 규칙을 검증하는 테스트에는 @Transactional을 걸 수 없다.** 참여 트랜잭션은 rollback-only로
 * 표시만 되고 실제로 되돌아가지 않아 "거절은 됐는데 변경은 남은" 상태를 보게 된다
 * (RoleAuthoritySelfLockTest · MemberRoleSelfLockTest · MemberSignupRollbackTest가 같은 이유로
 * 트랜잭션 없이 돈다).
 */
@Component
@RequiredArgsConstructor
public class RoleManageSelfLockGuard {

    private final AuthorityPolicy authorityPolicy;
    private final EntityManager entityManager;

    /*
     * 이 트랜잭션의 변경이 반영된 상태에서 요청자가 여전히 ROLE_MANAGE를 행사할 수 있는지 묻는다.
     * 아니면 409로 던져 조작 전체를 되돌린다.
     *
     * 막는 것은 '자기 자신'뿐이다. 남의 권한·역할을 거두는 것은 통과시킨다 — 그 경우엔 거둔
     * 쪽이 여전히 관리할 수 있어 아무도 되돌리지 못하는 상태가 되지 않는다.
     */
    public void verifyRequesterKeepsRoleManage(Long requesterMemberId) {
        entityManager.flush();
        if (!authorityPolicy.hasAuthority(requesterMemberId, AuthorityCode.ROLE_MANAGE)) {
            throw new GeneralException(MemberErrorCode.CANNOT_REVOKE_OWN_ROLE_MANAGE);
        }
    }
}
