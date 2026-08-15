package org.sscc.ssccopsserver.support;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;

/*
 * 테스트용 조직 역할 배정 픽스처 (#47).
 *
 * 승인·투표 권한이 회원의 현재 역할(mbr_role_rel)로 갈리므로, 그 경로를 타는 테스트는 여기서
 * 역할을 붙인다. data.sql이 시드하는 역할명(회장·부회장·총무·국장·국원 …)은 총칭이고 실제
 * 조직은 부서별로 나뉘므로(홍보국장·행정국원 …) 시드에 없는 이름도 만들 수 있게 두었다.
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

    /** 부서별 직책. 시드에 없는 이름이라 승인자 판정이 접미사로 도는지 확인하는 데 쓴다 */
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
