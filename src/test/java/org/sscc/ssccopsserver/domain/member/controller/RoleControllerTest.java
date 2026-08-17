package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.RoleResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.RoleAuthorityRelationEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.domain.member.service.RoleService;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 역할 조회·생성·수정·삭제 API (#79 · ssccops#17).
 *
 * 확인의 중심은 두 가지다. 하나는 **화면에서 만든 역할이 시드 역할과 똑같이 쓰인다**는 것 —
 * 권한을 붙이고 사람에게 배정하면 그 사람이 실제로 통과해야 한다. 다른 하나는 **지울 수 없는
 * 역할을 지우지 못한다**는 것이다. 응답만 보면 "저장은 됐는데 쓸 수 없는 역할"이나 "이력이
 * 사라진 역할"이 통과해 버린다.
 *
 * 클래스에 @Transactional이 걸려 있으므로 **실패하는 요청은 테스트 하나에 하나씩만** 둔다.
 * 서비스가 예외를 던지면 참여 트랜잭션이 rollback-only로 표시돼 뒤이은 요청이
 * UnexpectedRollbackException을 만난다 (RoleAuthorityControllerTest와 같은 이유).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RoleControllerTest.StubJwtDecoderConfig.class)
@Transactional
class RoleControllerTest {

    private static final String ROLES = "/v1/roles";

    /** WORK_MANAGE를 요구하는 엔드포인트. 새로 만든 역할이 실제로 인가에 쓰이는지 확인한다 */
    private static final String WORKS = "/v1/works";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private RoleService roleService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private UUID adminToken;
    private UUID staffToken;
    private UUID outsiderToken;
    private MemberEntity staff;

    @BeforeEach
    void setUp() {
        adminToken = UUID.randomUUID();
        grant(saveMember(adminToken, "20260301", "최고운영자"), AuthorityCode.ROLE_MANAGE);

        staffToken = UUID.randomUUID();
        staff = saveMember(staffToken, "20260302", "홍보국장");

        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260303", "업무담당"), AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ 생성

    /*
     * **이 이슈의 핵심.** 화면에서 만든 역할이 목록에 나타나고, 권한을 붙여 사람에게 배정하면
     * 그 사람이 실제로 통과한다 — 시드가 넣은 역할과 다를 바 없이 쓰인다는 뜻이다.
     * 저장 여부만 보면 "목록에는 보이는데 부여 대상이 되지 않는" 구현도 통과한다.
     */
    @Test
    void createdRoleAppearsInTheListAndBecomesAssignable() throws Exception {
        Long roleId = createRole("홍보국장", "POSITION", null);

        mockMvc.perform(authorized(get(ROLES), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].roleNm").value(hasItem("홍보국장")));

        mockMvc.perform(
                        authorized(put(ROLES + "/" + roleId + "/authorities"), adminToken)
                                .content("{\"authrtCds\": [\"WORK_MANAGE\"]}"))
                .andExpect(status().isOk());

        // 권한만으로는 아무 일도 일어나지 않는다 — 그 역할을 가진 사람이 있어야 한다
        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isForbidden());

        assign(roleId, staff, LocalDate.now().minusDays(1), null);

        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isOk());
    }

    @Test
    void createReturnsTheStoredRoleWithItsClassificationName() throws Exception {
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(createBody("2026 신입모집 TF장", "PROJECT", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roleNm").value("2026 신입모집 TF장"))
                .andExpect(jsonPath("$.data.roleClsfCd").value("PROJECT"))
                .andExpect(jsonPath("$.data.roleClsfNm").value("프로젝트"))
                .andExpect(jsonPath("$.data.memberCount").value(0))
                .andExpect(jsonPath("$.data.crtDt").exists());
    }

    // 이름 중복은 DB 제약이 아니라 애플리케이션이 막는다 (RoleServiceImpl 주석 참고)
    @Test
    void creatingARoleWithAnExistingNameIsRejected() throws Exception {
        mockMvc.perform(authorized(post(ROLES), adminToken).content(createBody("국장", "DEPT", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_DUPLICATED"));
    }

    // 사전이 role_nm의 NULL을 허용하는 것과 별개로, 이름 없는 역할은 화면에서 고를 수 없다
    @Test
    void blankRoleNameIsRejected() throws Exception {
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(createBody("   ", "POSITION", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void creatingARoleInAnUnknownClassificationIsNotFound() throws Exception {
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(createBody("없는분류역할", "NO_SUCH_CLSF", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_CLASSIFICATION_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 직위 코드 (#118)

    /*
     * 승인·투표 자격은 역할명이 아니라 직위 코드가 준다 (#118). 부서별 국장을 만들 때 여기에
     * DIRECTOR를 지정하는 것이 그 자격을 주는 유일한 길이다 — 이름을 '홍보국장'으로 짓는 것만
     * 으로는 아무 자격도 생기지 않으며, 그것이 이름만 국장인 사용자 정의 역할을 막는 방법이다.
     */
    @Test
    void createStoresThePositionCodeThatGrantsApprovalRight() throws Exception {
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(
                                        "{\"roleNm\": \"홍보국장\", \"roleClsfCd\": \"POSITION\","
                                                + " \"rolePstnCd\": \"DIRECTOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rolePstnCd").value("DIRECTOR"));
    }

    /** 생략하면 NULL이고, 그 역할은 승인도 투표도 하지 못한다 — 안전한 기본값이다 */
    @Test
    void createWithoutAPositionCodeLeavesItEmpty() throws Exception {
        Long roleId = createRole("동아리방국장", "POSITION", null);

        mockMvc.perform(authorized(get(ROLES + "/" + roleId), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rolePstnCd").doesNotExist());
    }

    // 기준 코드에 없는 값은 역직렬화에서 걸린다
    @Test
    void createWithAnUnknownPositionCodeIsRejected() throws Exception {
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(
                                        "{\"roleNm\": \"이상한역할\", \"roleClsfCd\": \"POSITION\","
                                                + " \"rolePstnCd\": \"CHAIRMAN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    /*
     * **이 필드를 모르는 요청은 값을 건드리지 않는다.** 역할 관리 화면이 이름만 고쳐 보낼 때
     * 직위 코드가 조용히 지워지면, 개명 한 번으로 승인자가 사라지는 원래 문제가 형태만 바꿔
     * 되살아난다 (#118 RoleUpdateRequest 주석).
     */
    @Test
    void patchWithoutThePositionCodeFieldKeepsIt() throws Exception {
        Long roleId = createRoleWithPositionCode("학술국장", "DIRECTOR");

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + roleId), adminToken)
                                .content("{\"roleNm\": \"학술부장\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleNm").value("학술부장"))
                .andExpect(jsonPath("$.data.rolePstnCd").value("DIRECTOR"));
    }

    /*
     * 빈 문자열은 해제다. 잘못 지정한 코드를 되돌릴 길이 없으면 그 역할은 영영 승인·투표
     * 자격을 갖는다 — null은 '그대로 두라'로 이미 쓰이고 있어 다른 신호가 필요했다.
     */
    @Test
    void patchWithAnEmptyPositionCodeClearsIt() throws Exception {
        Long roleId = createRoleWithPositionCode("잘못 지정한 역할", "TREASURER");

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + roleId), adminToken)
                                .content("{\"rolePstnCd\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rolePstnCd").doesNotExist());
    }

    // 수정도 생성과 같은 응답으로 거절한다 — 같은 값에 두 갈래 처리를 만들지 않는다
    @Test
    void patchWithAnUnknownPositionCodeIsRejected() throws Exception {
        Long roleId = createRole("바꿔 볼 역할", "POSITION", null);

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + roleId), adminToken)
                                .content("{\"rolePstnCd\": \"CHAIRMAN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    /*
     * **빈 문자열은 생성과 수정에서 같은 뜻이어야 한다** — 지정 없음이다. 생성만 enum으로
     * 받으면 Jackson이 빈 문자열을 역직렬화에서 거절해, 화면의 빈 선택 상자가 보내는 같은
     * 본문이 생성에서는 400인데 수정에서는 해제로 통한다. 두 요청이 같은 변환을 쓰는지를
     * 이 한 줄이 지킨다.
     */
    @Test
    void createWithAnEmptyPositionCodeMeansNoneJustLikeOmittingIt() throws Exception {
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(
                                        "{\"roleNm\": \"직위 없는 역할\", \"roleClsfCd\":"
                                                + " \"POSITION\", \"rolePstnCd\": \"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rolePstnCd").doesNotExist());
    }

    // ------------------------------------------------------------------ 표시 순번

    /*
     * indctSeqno를 생략하면 **같은 분류 안의** 최대값 + 1이다. PROJECT 분류를 쓰는 것은 시드가
     * POSITION에만 역할을 넣어 두어 빈 분류의 첫 번호가 1인지까지 함께 볼 수 있어서다.
     *
     * 분류를 가르지 않고 최대값을 찾는 구현이면 첫 값이 1이 아니라 시드(POSITION 7번) 다음이
     * 되어 여기서 드러난다.
     */
    @Test
    void displayOrderIsFilledWithMaxPlusOneWithinTheSameClassification() throws Exception {
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(createBody("첫 프로젝트장", "PROJECT", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.indctSeqno").value(1));

        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(createBody("둘째 프로젝트장", "PROJECT", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.indctSeqno").value(2));

        // 명시한 값은 그대로 쓰고, 그 뒤의 자동 채번은 그 값을 최대값으로 본다
        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(createBody("일곱째 프로젝트장", "PROJECT", 7)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.indctSeqno").value(7));

        mockMvc.perform(
                        authorized(post(ROLES), adminToken)
                                .content(createBody("여덟째 프로젝트장", "PROJECT", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.indctSeqno").value(8));
    }

    /*
     * 분류를 바꾸면서 순번을 생략하면 **새 분류 기준으로** 다시 매긴다. 옛 분류의 숫자를 들고
     * 넘어가면 '분류 안에서 이어지는 표시 순번'이라는 성질이 그 자리에서 깨진다.
     */
    @Test
    void changingClassificationRenumbersWithinTheNewOne() throws Exception {
        createRole("스터디장 대행", "STUDY", 5);
        Long moved = createRole("옮겨 갈 역할", "PROJECT", 1);

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + moved), adminToken)
                                .content("{\"roleClsfCd\": \"STUDY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleClsfCd").value("STUDY"))
                .andExpect(jsonPath("$.data.indctSeqno").value(6));
    }

    // 순번을 명시하면 분류를 함께 바꿔도 그 값을 그대로 쓴다
    @Test
    void explicitDisplayOrderWinsOverRenumbering() throws Exception {
        createRole("스터디장 대행", "STUDY", 5);
        Long moved = createRole("옮겨 갈 역할", "PROJECT", 1);

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + moved), adminToken)
                                .content("{\"roleClsfCd\": \"STUDY\", \"indctSeqno\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indctSeqno").value(2));
    }

    // ------------------------------------------------------------------ 수정

    // null인 필드는 건드리지 않는다 — 이름만 보낸 요청이 분류나 순번을 지우면 안 된다
    @Test
    void patchOnlyTouchesTheFieldsItCarries() throws Exception {
        Long roleId = createRole("옛 이름", "PROJECT", 3);

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + roleId), adminToken)
                                .content("{\"roleNm\": \"새 이름\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleNm").value("새 이름"))
                .andExpect(jsonPath("$.data.roleClsfCd").value("PROJECT"))
                .andExpect(jsonPath("$.data.indctSeqno").value(3));
    }

    @Test
    void renamingToAnExistingNameIsRejected() throws Exception {
        Long roleId = createRole("홍보국장", "POSITION", null);

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + roleId), adminToken)
                                .content("{\"roleNm\": \"국장\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_DUPLICATED"));
    }

    // 자기 이름을 그대로 다시 보내는 것은 중복이 아니다 — 저장 버튼은 아무것도 안 바꾸고도 눌린다
    @Test
    void keepingItsOwnNameIsNotADuplicate() throws Exception {
        Long roleId = createRole("홍보국장", "POSITION", null);

        mockMvc.perform(
                        authorized(patch(ROLES + "/" + roleId), adminToken)
                                .content("{\"roleNm\": \"홍보국장\", \"indctSeqno\": 9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indctSeqno").value(9));
    }

    // ------------------------------------------------------------------ 조회

    /*
     * 목록·상세의 memberCount는 **지금 맡고 있는 사람**만 센다. 종료된 배정까지 세면 화면의
     * '3명 사용'이 실제 재임자와 갈리고, 반대로 삭제 가드는 종료된 배정도 이력으로 본다 —
     * 두 기준이 다르다는 것을 여기서 못 박아 둔다.
     */
    @Test
    void memberCountAndMembersCountOnlyCurrentAssignments() throws Exception {
        Long roleId = createRole("홍보국장", "POSITION", null);

        assign(roleId, staff, LocalDate.now().minusYears(1), null);
        MemberEntity retired = saveMember(UUID.randomUUID(), "20250101", "작년홍보국장");
        assign(roleId, retired, LocalDate.now().minusYears(2), LocalDate.now().minusDays(1));
        MemberEntity future = saveMember(UUID.randomUUID(), "20270101", "내년홍보국장");
        assign(roleId, future, LocalDate.now().plusDays(1), null);
        flushAndClear();

        mockMvc.perform(authorized(get(ROLES + "/" + roleId), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleNm").value("홍보국장"))
                .andExpect(jsonPath("$.data.memberCount").value(1))
                .andExpect(jsonPath("$.data.members", hasSize(1)))
                .andExpect(jsonPath("$.data.members[0].mbrNm").value("홍보국장"))
                .andExpect(jsonPath("$.data.members[0].stdntNo").value("20260302"));

        assertThat(memberCountOf(roleId)).isEqualTo(1L);
    }

    @Test
    void listCanBeFilteredByClassification() throws Exception {
        createRole("2026 신입모집 TF장", "PROJECT", null);

        mockMvc.perform(authorized(get(ROLES + "?roleClsfCd=PROJECT"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].roleNm").value("2026 신입모집 TF장"));
    }

    /*
     * **목록의 쿼리 수는 역할 수에 비례하지 않는다.** 역할 조회 1 + 보유 회원 수 집계 1로 끝나야
     * 한다 — 역할마다 count를 돌리는 구현이면 여기서 숫자가 튄다. 시드 역할 8종에 새로 만든
     * 다섯을 더한 상태로 재므로, 비례하는 구현이면 두 자리 수가 된다.
     *
     * MockMvc가 아니라 서비스를 직접 부르는 것은 컨트롤러를 거치면 인증·인가 조회가 함께 섞여
     * 목록 자체의 쿼리 수를 가릴 수 없기 때문이다 (FormControllerTest와 같은 방식).
     */
    @Test
    void listQueryCountDoesNotGrowWithTheNumberOfRoles() {
        for (int index = 1; index <= 5; index++) {
            MemberRoleEntity role = saveRole("프로젝트장 " + index, "PROJECT", index);
            assign(role.getId(), staff, LocalDate.now().minusDays(1), null);
        }
        flushAndClear();

        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        List<RoleResponse> roles = roleService.getRoles(null);

        assertThat(roles.size()).isGreaterThanOrEqualTo(13);
        assertThat(roles).anySatisfy(role -> assertThat(role.memberCount()).isEqualTo(1L));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void unknownRoleReturnsNotFound() throws Exception {
        mockMvc.perform(authorized(get(ROLES + "/999999"), adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 삭제

    // 아무에게도 붙은 적 없고 권한도 없는 역할만 지워진다
    @Test
    void unusedRoleIsDeleted() throws Exception {
        Long roleId = createRole("잘못 만든 역할", "PROJECT", null);

        mockMvc.perform(authorized(delete(ROLES + "/" + roleId), adminToken))
                .andExpect(status().isOk());

        flushAndClear();
        assertThat(memberRoleRepository.findById(roleId)).isEmpty();
    }

    /*
     * 배정 이력이 있으면 지울 수 없다. **종료된 배정도 이력이다** — 지우면 "그 사람이 언제
     * 국장이었는지"가 함께 사라진다. 현재 재임자만 보는 구현이면 여기서 200이 되어 드러난다.
     */
    @Test
    void roleWithEndedAssignmentHistoryCannotBeDeleted() throws Exception {
        Long roleId = createRole("작년 홍보국장", "POSITION", null);
        assign(roleId, staff, LocalDate.now().minusYears(2), LocalDate.now().minusYears(1));
        flushAndClear();

        mockMvc.perform(authorized(delete(ROLES + "/" + roleId), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
    }

    // 권한이 붙어 있으면 회수를 먼저 하게 한다 (#65 AUTHORITY_IN_USE와 같은 태도)
    @Test
    void roleWithGrantedAuthorityCannotBeDeleted() throws Exception {
        Long roleId = createRole("권한 붙은 역할", "PROJECT", null);
        roleAuthorityRelationRepository.saveAndFlush(
                RoleAuthorityRelationEntity.create(
                        memberRoleRepository.findById(roleId).orElseThrow(),
                        authorityRepository.findById("WORK_MANAGE").orElseThrow()));
        flushAndClear();

        mockMvc.perform(authorized(delete(ROLES + "/" + roleId), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
    }

    @Test
    void deletingAnUnknownRoleIsNotFound() throws Exception {
        mockMvc.perform(authorized(delete(ROLES + "/999999"), adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 인가

    /*
     * 조회도 ROLE_MANAGE를 요구한다 (VR-M12). 역할은 권한이 붙는 자리라 목록 하나가 열려 있으면
     * 조직 구조가 그대로 새어 나가고, 무엇보다 클래스 레벨 애노테이션에서 핸들러 하나가 빠지는
     * 실수를 여기서 잡는다.
     */
    @Test
    void callerWithoutRoleManageIsForbiddenOnEveryHandler() throws Exception {
        mockMvc.perform(authorized(get(ROLES), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorized(get(ROLES + "/1"), outsiderToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        authorized(post(ROLES), outsiderToken)
                                .content(createBody("몰래 만든 역할", "POSITION", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        authorized(patch(ROLES + "/1"), outsiderToken)
                                .content("{\"roleNm\": \"몰래 바꾼 이름\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorized(delete(ROLES + "/1"), outsiderToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 헬퍼

    private Long createRole(String name, String classificationCode, Integer displayOrder)
            throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(ROLES), adminToken)
                                        .content(
                                                createBody(name, classificationCode, displayOrder)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.roleId", Long.class);
    }

    private Long createRoleWithPositionCode(String name, String positionCode) throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(ROLES), adminToken)
                                        .content(
                                                ("{\"roleNm\": \"%s\", \"roleClsfCd\":"
                                                                + " \"POSITION\", \"rolePstnCd\":"
                                                                + " \"%s\"}")
                                                        .formatted(name, positionCode)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.roleId", Long.class);
    }

    private static String createBody(String name, String classificationCode, Integer displayOrder) {
        return "{\"roleNm\": \"%s\", \"roleClsfCd\": \"%s\", \"indctSeqno\": %s}"
                .formatted(name, classificationCode, displayOrder == null ? "null" : displayOrder);
    }

    private MemberRoleEntity saveRole(
            String name, String classificationCode, Integer displayOrder) {
        MemberRoleClassificationEntity classification =
                memberRoleClassificationRepository.findById(classificationCode).orElseThrow();
        return memberRoleRepository.saveAndFlush(
                MemberRoleEntity.create(displayOrder, name, classification));
    }

    private void assign(Long roleId, MemberEntity member, LocalDate startDate, LocalDate endDate) {
        MemberRoleAssignmentEntity assignment =
                MemberRoleAssignmentEntity.create(
                        member,
                        memberRoleRepository.findById(roleId).orElseThrow(),
                        startDate,
                        false);
        if (endDate != null) {
            assignment.end(endDate);
        }
        memberRoleAssignmentRepository.saveAndFlush(assignment);
    }

    private long memberCountOf(Long roleId) {
        return roleService.getRoles(null).stream()
                .filter(role -> role.roleId().equals(roleId))
                .findFirst()
                .orElseThrow()
                .memberCount();
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", token + "@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
