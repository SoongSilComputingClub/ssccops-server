package org.sscc.ssccopsserver.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Comparator;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;

/*
 * data.sql이 넣는 기준 코드·기준 데이터 검증.
 *
 * 기대값을 enum이 아니라 문자열 리터럴로 적은 것은 의도된 것이다. 이 코드값들은 웹
 * shared/config/codes.ts와 맞춰 놓은 계약이라, enum 상수 이름이 바뀌었을 때 테스트가 따라
 * 바뀌어 조용히 통과해 버리면 안 된다. 여기서만큼은 서버 코드가 아니라 계약을 적는다.
 */
@DataJpaTest
@ActiveProfiles("test")
class CodeSeedDataTest {

    @Autowired private DataSource dataSource;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;

    @Test
    void seedsEveryGradeCodeTheWebRenders() {
        assertThat(memberGradeRepository.findAll())
                .extracting(MemberGradeEntity::getCode, MemberGradeEntity::getName)
                .containsExactlyInAnyOrder(
                        tuple("TEMP", "임시회원"),
                        tuple("ASSOC", "준회원"),
                        tuple("ACTIVE", "활동회원"),
                        tuple("FULL", "정회원"));
    }

    @Test
    void seedsEveryStatusCodeTheWebRenders() {
        assertThat(memberStatusRepository.findAll())
                .extracting(MemberStatusEntity::getCode, MemberStatusEntity::getName)
                .containsExactlyInAnyOrder(
                        tuple("ENROLLED", "재학"),
                        tuple("LEAVE", "일반휴학"),
                        tuple("MIL_LEAVE", "군휴학"),
                        tuple("GRADUATED", "졸업"),
                        tuple("WITHDRAWN", "탈퇴"),
                        tuple("EXPELLED", "제명"));
    }

    // 졸업 회원 가입(#21)이 막혀 있던 직접적인 원인이라 따로 못 박아 둔다
    @Test
    void seedsGraduatedStatusSoThatGraduateSignUpIsPossible() {
        assertThat(memberStatusRepository.findById("GRADUATED")).isPresent();
    }

    @Test
    void seedsRoleClassifications() {
        assertThat(memberRoleClassificationRepository.findAll())
                .extracting(MemberRoleClassificationEntity::getCode)
                .contains("POSITION", "DEPT", "PROJECT", "STUDY", "EVENT", "SYSTEM");
    }

    /*
     * 역할 표시순번이 회칙 순서대로인지 확인한다.
     *
     * **분류를 갈라 비교한다.** indct_seqno는 분류마다 1부터 다시 시작하므로(data.sql) SYSTEM의
     * '최고관리자'(1)와 POSITION의 '회장'(1)은 값이 같다 — 분류를 섞어 한 줄로 세우면 순서가
     * 무엇에 의해 정해지는지 알 수 없는 비교가 된다. #71로 SYSTEM 분류에 역할이 생기기 전까지는
     * 역할이 전부 POSITION이라 이 구별이 드러나지 않았다.
     *
     * 순번은 화면 정렬용일 뿐 서열이 아니다 — 인가는 역할에 부여된 권한으로 한다(#9 · VR-M11).
     */
    @Test
    void ordersPositionRolesInBylawOrder() {
        assertThat(rolesOfClassification("POSITION"))
                .containsExactly("회장", "부회장", "총무", "국장", "국원", "프로젝트장", "스터디장");
    }

    /*
     * 직위 코드(role_pstn_cd) 시드 (#118). 승인·투표 자격이 역할명 문자열이 아니라 이 값으로
     * 갈리므로, 비어 있으면 예산지출을 승인할 사람도 투표할 사람도 없는 상태로 배포된다.
     *
     * **프로젝트장·스터디장·최고관리자가 NULL인 것도 함께 못 박는다.** 값이 없으면 승인도
     * 투표도 되지 않는다는 것이 그 셋을 운영 의사결정에서 빼는 방법이고, 여기에 무심코 코드를
     * 채우면 스터디장이 예산지출에 투표하게 된다.
     *
     * 코드값을 enum이 아니라 문자열로 적는 것은 이 클래스의 다른 기대값과 같은 이유다 —
     * 상수 이름이 바뀌었을 때 테스트가 따라 바뀌어 조용히 통과하면 안 된다.
     */
    @Test
    void seedsPositionCodesThatDecideApprovalAndVotingRights() {
        assertThat(memberRoleRepository.findAll())
                .extracting(
                        MemberRoleEntity::getName,
                        role ->
                                role.getPositionCode() == null
                                        ? null
                                        : role.getPositionCode().name())
                .contains(
                        tuple("회장", "PRESIDENT"),
                        tuple("부회장", "VICE_PRESIDENT"),
                        tuple("총무", "TREASURER"),
                        tuple("국장", "DIRECTOR"),
                        tuple("국원", "STAFF"),
                        tuple("프로젝트장", null),
                        tuple("스터디장", null),
                        tuple("최고관리자", null));
    }

    /*
     * 최초 가입자 부트스트랩(#71)이 딛고 서는 시드 세 가지 — 역할·권한·매핑.
     *
     * 이름을 문자열로 적는 것은 MemberServiceImpl.BOOTSTRAP_ROLE_NAME과 data.sql이 **문자열로**
     * 맞춰져 있기 때문이다. 상수를 참조하면 상수만 바뀌었을 때 테스트가 따라 바뀌어 조용히
     * 통과한다 — 그 순간 최초 가입자는 역할을 못 받고, 회원이 한 명 생겨 버려 부트스트랩 창구는
     * 영영 닫힌다.
     */
    @Test
    void seedsBootstrapRoleThatCarriesSuperAuthority() {
        assertThat(rolesOfClassification("SYSTEM")).containsExactly("최고관리자");

        MemberRoleEntity bootstrapRole =
                memberRoleRepository.findAll().stream()
                        .filter(role -> "최고관리자".equals(role.getName()))
                        .findFirst()
                        .orElseThrow();

        assertThat(roleAuthorityRelationRepository.findAllByRoleId(bootstrapRole.getId()))
                .extracting(relation -> relation.getAuthority().getCode())
                .containsExactly("SUPER");
    }

    /*
     * SUPER가 트리의 최상위이고 EXECUTIVE가 그 자식이어야 한다 (#71 · BR-M35).
     *
     * 이 두 줄이 SUPER의 의미 전부다 — AuthorityPolicy에는 SUPER를 특별 취급하는 분기가 없고,
     * "모든 권한을 포함한다"는 오로지 트리 모양에서 나온다. 부모 관계가 끊기면 인가는 조용히
     * 좁아지고, 웹이 트리 간선으로 하는 저장 전 미리 보기와도 어긋난다.
     */
    @Test
    void seedsSuperAsTheRootAboveExecutive() {
        assertThat(authorityRepository.findById("SUPER"))
                .get()
                .satisfies(
                        authority -> {
                            assertThat(authority.getParent()).isNull();
                            assertThat(authority.isSystemDefined()).isTrue();
                        });

        assertThat(authorityRepository.findById("EXECUTIVE"))
                .get()
                .extracting(authority -> authority.getParent().getCode())
                .isEqualTo("SUPER");
    }

    private List<String> rolesOfClassification(String roleClassificationCode) {
        return memberRoleRepository.findAll().stream()
                .filter(
                        role ->
                                roleClassificationCode.equals(
                                        role.getRoleClassification().getCode()))
                .sorted(Comparator.comparing(MemberRoleEntity::getDisplayOrder))
                .map(MemberRoleEntity::getName)
                .toList();
    }

    // 승인이 필요한 유형에는 승인자가 있고, 필요 없는 유형에는 없어야 한다
    @Test
    void assignsAuthorizerRoleOnlyToTypesThatNeedApproval() {
        assertThat(subWorkTypeRepository.findAll())
                .allSatisfy(
                        type ->
                                assertThat(type.getAuthorizerRoleCode() != null)
                                        .isEqualTo(type.isApprovalNeeded()));

        assertThat(subWorkTypeRepository.findById(1L))
                .get()
                .extracting(SubWorkTypeEntity::getAuthorizerRoleCode)
                .isEqualTo("TREASURER");
        assertThat(subWorkTypeRepository.findById(2L))
                .get()
                .extracting(SubWorkTypeEntity::getAuthorizerRoleCode)
                .isEqualTo("PRESIDENT");
    }

    /*
     * spring.sql.init.mode=always라 data.sql은 매 기동마다 실행된다. 재기동을 흉내 내려고
     * 부트가 쓰는 것과 같은 populator로 스크립트를 한 번 더 돌린 뒤, 건수가 그대로인지 본다.
     * WHERE NOT EXISTS가 빠진 INSERT가 하나라도 섞이면 여기서 잡힌다.
     */
    @Test
    void reRunningTheSeedScriptChangesNothing() {
        long grades = memberGradeRepository.count();
        long statuses = memberStatusRepository.count();
        long classifications = memberRoleClassificationRepository.count();
        long roles = memberRoleRepository.count();
        long authorities = authorityRepository.count();
        long grants = roleAuthorityRelationRepository.count();
        long subWorkTypes = subWorkTypeRepository.count();

        new ResourceDatabasePopulator(new ClassPathResource("data.sql")).execute(dataSource);

        assertThat(memberGradeRepository.count()).isEqualTo(grades);
        assertThat(memberStatusRepository.count()).isEqualTo(statuses);
        assertThat(memberRoleClassificationRepository.count()).isEqualTo(classifications);
        assertThat(memberRoleRepository.count()).isEqualTo(roles);
        assertThat(authorityRepository.count()).isEqualTo(authorities);
        assertThat(roleAuthorityRelationRepository.count()).isEqualTo(grants);
        assertThat(subWorkTypeRepository.count()).isEqualTo(subWorkTypes);
    }
}
