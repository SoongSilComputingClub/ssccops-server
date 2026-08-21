package org.sscc.ssccopsserver.support;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.RoleAuthorityRelationEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;

/*
 * 테스트용 조직 역할 배정 픽스처 (#47).
 *
 * 승인·투표 자격이 회원의 현재 역할에 부여된 **권한**(role_authrt_rel)으로 갈리므로(#123),
 * 그 경로를 타는 테스트는 여기서 역할을 붙인다. data.sql이 시드하는 역할명(회장·부회장·총무·
 * 국장·국원 …)은 총칭이고 실제 조직은 부서별로 나뉘므로(홍보국장·행정국원 …) 시드에 없는
 * 이름도 만들 수 있게 두었다.
 *
 * **시드에 없는 역할은 권한도 비어 있다.** 시드된 역할은 data.sql이 결재·투표 권한까지 붙여
 * 두지만, 여기서 만든 역할에 자격을 주려면 grantAuthorities로 명시해야 한다 — 실제 운영에서
 * 역할별 권한 화면이 부서별 국장에 SUB_WORK_APPROVE_DIRECTOR를 부여해 주는 것과 같은 준비다.
 * 부여하지 않은 역할은 그 자체로 "이름만 국장인 사용자 정의 역할"의 픽스처가 된다.
 */
public final class MemberRoleFixture {

    public static final String PRESIDENT = "회장";
    public static final String TREASURER = "총무";

    /*
     * 시드된 '국장'. data.sql이 이 역할에 OPERATOR 권한을 붙여 두므로(#9) 업무·폼·응답 심사
     * 엔드포인트를 부를 수 있다 — 인가가 필요한 컨트롤러 테스트는 이 역할을 쓴다.
     * 회장·부회장·총무는 EXECUTIVE라 그보다 넓고, 시드에 없는 이름(홍보국장 등)은 권한이 없다.
     */
    public static final String DIRECTOR = "국장";

    /** 부서별 직책. 시드에 없는 이름이라 자격이 필요한 테스트가 grantAuthorities로 권한을 붙인다 */
    public static final String PR_DIRECTOR = "홍보국장";

    public static final String PLANNING_STAFF = "기획국원";

    /** 운영진이 아닌 역할. 투표 자격 차단을 확인하는 데 쓴다 */
    public static final String STUDY_LEADER = "스터디장";

    private MemberRoleFixture() {}

    public static MemberRoleAssignmentEntity assign(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            MemberRoleAssignmentRepository assignmentRepository,
            MemberEntity member,
            String roleName) {

        MemberRoleEntity role = findOrCreate(roleRepository, classificationRepository, roleName);
        return assignmentRepository.save(
                MemberRoleAssignmentEntity.create(member, role, LocalDate.of(2026, 3, 1), true));
    }

    /*
     * 역할에 권한을 직접 부여한다 — 역할별 권한 화면(PUT /v1/roles/{roleId}/authorities)과 같은
     * 조작이다. 시드에 없는 역할(홍보국장 등)에 결재·투표 자격을 줄 때 쓴다. 이름과 자격이
     * 분리됐다는 것이 #123의 요점이므로, 부여 없이 이름만 지은 역할과 대비하는 테스트가 가능하다.
     */
    public static void grantAuthorities(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            AuthorityRepository authorityRepository,
            RoleAuthorityRelationRepository relationRepository,
            String roleName,
            AuthorityCode... codes) {

        MemberRoleEntity role = findOrCreate(roleRepository, classificationRepository, roleName);
        for (AuthorityCode code : codes) {
            AuthorityEntity authority =
                    authorityRepository
                            .findById(code.code())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "data.sql이 넣어야 할 권한이 없다: " + code.code()));
            relationRepository.save(RoleAuthorityRelationEntity.create(role, authority));
        }
    }

    private static MemberRoleEntity findOrCreate(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            String roleName) {

        return roleRepository.findAll().stream()
                .filter(role -> roleName.equals(role.getName()))
                .findFirst()
                .orElseGet(
                        () -> {
                            MemberRoleClassificationEntity position =
                                    classificationRepository
                                            .findById("POSITION")
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "data.sql이 넣어야 할 역할 분류가"
                                                                            + " 없다: POSITION"));
                            // 표시 순번은 판정에 쓰이지 않으므로 시드 뒤쪽으로만 밀어 둔다
                            return roleRepository.save(
                                    MemberRoleEntity.create(99, roleName, position));
                        });
    }
}
