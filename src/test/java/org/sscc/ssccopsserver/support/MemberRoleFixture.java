package org.sscc.ssccopsserver.support;

import java.time.LocalDate;
import java.util.Map;

import org.sscc.ssccopsserver.domain.member.code.RolePositionCode;
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
 *
 * **시드에 없는 역할에는 직위 코드(role_pstn_cd)를 명시해야 한다** (#118). 판정이 역할명
 * 접미사가 아니라 그 코드를 보므로, 이름만 '홍보국장'으로 지어 두면 승인도 투표도 되지 않는다 —
 * 실제 운영에서 역할 관리 화면이 부서별 국장에 DIRECTOR를 지정해 주는 것과 같은 준비다.
 * 코드를 지정하지 않은 역할은 그 자체로 "이름만 국장인 사용자 정의 역할"의 픽스처가 된다.
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

    /** 부서별 직책. 시드에 없는 이름이라 DIRECTOR 코드를 아래 표에서 붙여 준다 */
    public static final String PR_DIRECTOR = "홍보국장";

    public static final String PLANNING_STAFF = "기획국원";

    /** 운영진이 아닌 역할. 투표 자격 차단을 확인하는 데 쓴다 */
    public static final String STUDY_LEADER = "스터디장";

    /*
     * 시드에 없는 역할을 만들 때 붙일 직위 코드. 시드된 역할(회장·총무·국장·국원 …)은 data.sql이
     * 이미 채워 두므로 여기 없고, findOrCreate가 기존 행을 찾으면 그 값을 그대로 쓴다.
     */
    private static final Map<String, RolePositionCode> POSITION_CODES =
            Map.of(
                    PR_DIRECTOR, RolePositionCode.DIRECTOR,
                    PLANNING_STAFF, RolePositionCode.STAFF);

    private MemberRoleFixture() {}

    public static MemberRoleAssignmentEntity assign(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            MemberRoleAssignmentRepository assignmentRepository,
            MemberEntity member,
            String roleName) {

        return assign(
                roleRepository,
                classificationRepository,
                assignmentRepository,
                member,
                roleName,
                POSITION_CODES.get(roleName));
    }

    /*
     * 직위 코드를 직접 정해 배정한다. 위 표에 없는 이름을 쓰거나(사용자 정의 역할) 표와 다른
     * 코드를 붙여 보는 테스트를 위한 자리다 — 이름과 자격이 분리됐다는 것이 #118의 요점이므로
     * 그 둘을 어긋나게 만들 수 있어야 확인할 수 있다.
     */
    public static MemberRoleAssignmentEntity assign(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            MemberRoleAssignmentRepository assignmentRepository,
            MemberEntity member,
            String roleName,
            RolePositionCode positionCode) {

        MemberRoleEntity role =
                findOrCreate(roleRepository, classificationRepository, roleName, positionCode);
        return assignmentRepository.save(
                MemberRoleAssignmentEntity.create(member, role, LocalDate.of(2026, 3, 1), true));
    }

    private static MemberRoleEntity findOrCreate(
            MemberRoleRepository roleRepository,
            MemberRoleClassificationRepository classificationRepository,
            String roleName,
            RolePositionCode positionCode) {

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
                                    MemberRoleEntity.create(99, roleName, position, positionCode));
                        });
    }
}
