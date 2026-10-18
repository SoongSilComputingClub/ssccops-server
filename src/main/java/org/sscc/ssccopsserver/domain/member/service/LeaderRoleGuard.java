package org.sscc.ssccopsserver.domain.member.service;

import java.util.Collection;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.LeaderRole;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * **«리더 역할은 권한을 갖지 않는다»의 유일한 구현** (#687 · ssccops#611).
 *
 * 스터디장·프로젝트장·트랙장(LeaderRole)은 운영진이 아니다. 그런데 이 규칙이 주석에만 있어
 * prod에서 스터디장 역할에 ACADEMIC_PROGRAM_MANAGE가 붙었고, 기획안 승인이 그 역할을 종료일 없이
 * 자동 부여하므로 **승인 한 번이 ROLE_MANAGE를 거치지 않고 학술국장 권한을 나눠 주는 길**이 됐다
 * (모든 리더가 모든 프로그램의 전이·회차 승인·선발·신청자 학번 조회를 할 수 있었다).
 *
 * 막는 문은 넷이고 전부 여기를 부른다 — 하나라도 자기 판정을 따로 두면 그 문이 우회로가 된다
 * (RoleManageSelfLockGuard가 #65·#81 두 문을 한 장치로 묶은 것과 같은 판단):
 *  1. 역할↔권한 교체(PUT /v1/roles/{roleId}/authorities) — 비어 있지 않은 부여를 거절
 *  2. 역할 이름 변경 — 승인이 이름으로 찾으므로, 이름을 맞바꾸면 권한 있는 역할이 리더 역할이 된다
 *  3. 역할 삭제 — 아직 아무도 받지 않은 리더 역할은 ROLE_IN_USE에 걸리지 않아 지워지고, 그러면
 *     그 유형의 승인이 500으로 멈춘다
 *  4. 기획안 승인의 리더 역할 부여 — 위 셋이 생기기 전에 붙은 옛 데이터를 만나면 내주지 않는다
 *     (V33이 배포 때 지우지만, 지운 뒤에도 DB를 직접 고치는 길은 남는다)
 *
 * ── 왜 읽을 때(AuthorityPolicy) 무시하지 않는가 ──────────────────────────
 * 리더 역할의 부여를 판정에서 빼는 쪽이 한 줄로 끝나지만, 그러면 역할별 권한 화면에는 체크돼
 * 보이는데 실제로는 무시되는 «보이는 것과 다른 판정»이 생긴다 — BR-M28이 판정을 한 벌로 둔 이유가
 * 그것이다. 쓰기에서 막고 남은 행은 마이그레이션으로 지우면 판정 규칙에 예외가 필요 없다.
 */
@Component
@RequiredArgsConstructor
public class LeaderRoleGuard {

    private final RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    public boolean isLeaderRole(MemberRoleEntity role) {
        return role != null && LeaderRole.isLeaderRoleName(role.getName());
    }

    /** 문 1 — 리더 역할에 권한을 붙이는 교체를 거절한다. 빈 목록(전부 회수)은 통과시킨다 */
    public void verifyGrantable(MemberRoleEntity role, Collection<String> requestedCodes) {
        if (isLeaderRole(role) && requestedCodes != null && !requestedCodes.isEmpty()) {
            throw new GeneralException(MemberErrorCode.LEADER_ROLE_AUTHORITY_FORBIDDEN);
        }
    }

    /** 문 2 — 리더 역할의 이름을 바꾸지 못하게 한다. 같은 이름 그대로면 통과다(분류·순번만 바꾸는 요청) */
    public void verifyRenamable(MemberRoleEntity role, String newName) {
        if (isLeaderRole(role) && !role.getName().equals(newName)) {
            throw new GeneralException(MemberErrorCode.LEADER_ROLE_PROTECTED);
        }
    }

    /** 문 3 — 리더 역할을 지우지 못하게 한다 */
    public void verifyDeletable(MemberRoleEntity role) {
        if (isLeaderRole(role)) {
            throw new GeneralException(MemberErrorCode.LEADER_ROLE_PROTECTED);
        }
    }

    /*
     * 문 4 — 승인이 리더 역할을 내주기 직전에 부른다. 권한이 하나라도 붙어 있으면 역할을 내주지 않고
     * 409로 승인 전체를 되돌린다.
     *
     * 조용히 건너뛰거나(역할 없이 승인) 권한을 떼고 내주지 않는 것은, 둘 다 운영진 모르게 데이터를
     * 바꾸거나 상태를 숨기기 때문이다. 승인이 막히면 학술국장이 오류 문구를 보고 역할 관리에서 권한을
     * 지운 뒤 다시 승인하면 된다 — 권한을 몰래 나눠 주는 것보다 승인이 한 번 멈추는 편이 싸다.
     */
    public void requireAuthorityFree(MemberRoleEntity role) {
        if (roleAuthorityRelationRepository.existsByRoleId(role.getId())) {
            throw new GeneralException(MemberErrorCode.LEADER_ROLE_AUTHORITY_FORBIDDEN);
        }
    }
}
