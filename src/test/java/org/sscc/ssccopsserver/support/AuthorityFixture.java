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
 * 테스트용 권한 부여 픽스처 (#9).
 *
 * MemberRoleFixture가 '시드된 역할을 붙인다'라면 이쪽은 '정확히 이 권한 하나만 가진 역할을
 * 만들어 붙인다'이다. 펼침 규칙(상위는 통과 · 하위만으로는 불통)을 확인하려면 회원이 가진
 * 권한이 딱 하나여야 하는데, 시드 역할(EXECUTIVE·OPERATOR)은 너무 넓어서 그 구분이 안 된다.
 *
 * 역할명에 권한 코드를 그대로 쓰는 것은 테스트가 실패했을 때 어떤 권한을 준 역할이었는지
 * 이름만 보고 알 수 있게 하기 위해서다.
 */
public final class AuthorityFixture {

    private AuthorityFixture() {}

    /** 요구 권한 하나만 가진 역할을 만들어 회원에게 무기한으로 부여한다 */
    public static MemberRoleEntity grant(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            MemberRoleAssignmentRepository assignmentRepository,
            AuthorityRepository authorityRepository,
            RoleAuthorityRelationRepository relationRepository,
            MemberEntity member,
            AuthorityCode authority) {

        return grant(
                roleRepository,
                classificationRepository,
                assignmentRepository,
                authorityRepository,
                relationRepository,
                member,
                authority,
                LocalDate.now().minusYears(1),
                null);
    }

    /*
     * 기간을 지정해 부여한다. 만료된 역할(종료일이 어제)이나 아직 시작하지 않은 역할을 만들어
     * 유효 역할 판정을 확인하는 데 쓴다.
     */
    public static MemberRoleEntity grant(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            MemberRoleAssignmentRepository assignmentRepository,
            AuthorityRepository authorityRepository,
            RoleAuthorityRelationRepository relationRepository,
            MemberEntity member,
            AuthorityCode authority,
            LocalDate startDate,
            LocalDate endDate) {

        MemberRoleEntity role =
                createRole(roleRepository, classificationRepository, "역할:" + authority.code());

        AuthorityEntity authorityEntity =
                authorityRepository
                        .findById(authority.code())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "data.sql이 넣어야 할 권한이 없다: " + authority.code()));
        relationRepository.save(RoleAuthorityRelationEntity.create(role, authorityEntity));

        MemberRoleAssignmentEntity assignment =
                MemberRoleAssignmentEntity.create(member, role, startDate, false);
        if (endDate != null) {
            assignment.end(endDate);
        }
        assignmentRepository.save(assignment);
        return role;
    }

    /** 권한이 하나도 붙지 않은 역할. "권한 없는 새 역할은 아무것도 못 한다"를 확인하는 데 쓴다 */
    public static MemberRoleEntity grantRoleWithoutAuthority(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            MemberRoleAssignmentRepository assignmentRepository,
            MemberEntity member,
            String roleName) {

        MemberRoleEntity role = createRole(roleRepository, classificationRepository, roleName);
        assignmentRepository.save(
                MemberRoleAssignmentEntity.create(
                        member, role, LocalDate.now().minusYears(1), false));
        return role;
    }

    private static MemberRoleEntity createRole(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            String roleName) {

        MemberRoleClassificationEntity position =
                classificationRepository
                        .findById("POSITION")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "data.sql이 넣어야 할 역할 분류가 없다: POSITION"));
        // 표시 순번은 판정에 쓰이지 않으므로(#9 VR-M11) 시드 뒤쪽으로만 밀어 둔다
        return roleRepository.save(MemberRoleEntity.create(99, roleName, position));
    }
}
