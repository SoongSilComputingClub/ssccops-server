package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

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
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
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
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 회원 역할 부여·종료 API (#81 · ssccops#22).
 *
 * **확인의 중심은 "부여가 실제로 무엇을 바꾸는가"다.** 행이 저장됐는지만 보면 "목록에는 뜨는데
 * 권한은 안 열리는" 구현도, "종료했는데 여전히 통과하는" 구현도 통과한다. 그래서 부여·종료의
 * 앞뒤로 그 권한을 요구하는 실제 엔드포인트(/v1/works, WORK_MANAGE)를 두드려 본다 — 재로그인
 * 없이 바뀌어야 한다는 것이 BR-M31이다.
 *
 * 나머지 축은 이력 보존이다. 종료는 삭제가 아니므로 끝난 배정도 current=false 목록에 남아야
 * 하고, 기간이 겹치지 않는 재임은 두 행으로 남아야 한다.
 *
 * 자기 잠금 방지(VR-M13)는 여기 없다 — 트랜잭션을 건 테스트에서는 서비스의 롤백이 실제로
 * 일어나지 않아 검증할 수 없어 MemberRoleSelfLockTest가 따로 맡는다.
 *
 * 클래스에 @Transactional이 걸려 있으므로 **실패하는 요청은 테스트 하나에 하나씩만** 둔다.
 * 서비스가 예외를 던지면 참여 트랜잭션이 rollback-only로 표시돼 뒤이은 요청이
 * UnexpectedRollbackException을 만난다 (RoleControllerTest와 같은 이유). 인가 거절(403)은
 * 애스펙트가 트랜잭션 밖에서 던지므로 이 제약을 받지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberRoleAssignmentControllerTest.StubJwtDecoderConfig.class)
@Transactional
class MemberRoleAssignmentControllerTest {

    /** WORK_MANAGE를 요구하는 엔드포인트. 부여·종료가 인가에 즉시 닿는지 확인하는 데 쓴다 */
    private static final String WORKS = "/v1/works";

    private static final String SESSION = "/v1/auth/session";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
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
    private UUID memberManagerToken;
    private MemberEntity staff;

    /** WORK_MANAGE가 붙은 역할. 부여하면 그 회원이 /v1/works를 부를 수 있게 된다 */
    private Long workRoleId;

    /** 권한이 하나도 붙지 않은 역할. 대표 역할 단일성처럼 인가와 무관한 규칙에 쓴다 */
    private Long plainRoleId;

    @BeforeEach
    void setUp() {
        adminToken = UUID.randomUUID();
        grant(saveMember(adminToken, "20260501", "최고운영자"), AuthorityCode.ROLE_MANAGE);

        staffToken = UUID.randomUUID();
        staff = saveMember(staffToken, "20260502", "홍보국장");

        memberManagerToken = UUID.randomUUID();
        grant(saveMember(memberManagerToken, "20260503", "회원담당"), AuthorityCode.MEMBER_MANAGE);

        workRoleId = saveRole("업무 담당 역할").getId();
        roleAuthorityRelationRepository.saveAndFlush(
                RoleAuthorityRelationEntity.create(
                        memberRoleRepository.findById(workRoleId).orElseThrow(),
                        authorityRepository
                                .findById(AuthorityCode.WORK_MANAGE.code())
                                .orElseThrow()));

        plainRoleId = saveRole("권한 없는 역할").getId();
    }

    // ------------------------------------------------------------------ 즉시 반영 (BR-M31)

    /*
     * **이 이슈의 핵심.** 역할을 부여하면 그 회원의 권한이 **재로그인 없이** 바뀐다 — 인가 판정이
     * 요청마다 DB를 보기 때문이다. 세션 응답의 capabilities와 실제 엔드포인트 두 곳을 모두 보는
     * 것은 둘이 같은 AuthorityPolicy를 쓰는지까지 확인하기 위해서다(버튼은 보이는데 누르면 403인
     * 상태를 여기서 잡는다).
     */
    @Test
    void assignedRoleChangesCapabilitiesWithoutReLogin() throws Exception {
        mockMvc.perform(authorized(get(SESSION), staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.capabilities", not(hasItem("WORK_MANAGE"))));
        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isForbidden());

        assign(staff.getId(), workRoleId, LocalDate.now().minusDays(30), null);

        // 토큰은 그대로다 — 다시 로그인하지 않았는데 다음 요청부터 달라진다
        mockMvc.perform(authorized(get(SESSION), staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.capabilities", hasItem("WORK_MANAGE")))
                .andExpect(jsonPath("$.data.member.roles[*].roleName", hasItem("업무 담당 역할")));

        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isOk());
    }

    /*
     * 종료도 즉시 반영된다. **종료일을 채우는 것만으로** 권한이 닫혀야 한다 — 행을 지우지 않고도
     * 인가가 따라오는지가 '종료는 삭제가 아니다'가 성립하는 조건이다.
     */
    @Test
    void endingAnAssignmentClosesItsAuthorityImmediately() throws Exception {
        Long assignmentId = assign(staff.getId(), workRoleId, LocalDate.now().minusDays(30), null);
        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isOk());

        mockMvc.perform(
                        authorized(patch(rolesOf(staff) + "/" + assignmentId), adminToken)
                                .content(
                                        "{\"roleEndYmd\": \"%s\"}"
                                                .formatted(LocalDate.now().minusDays(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(false));

        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 겹침

    /*
     * 같은 역할을 기간이 겹치게 두 번 주지 않는다. 앞선 배정이 무기한(종료일 NULL)이므로 이후의
     * 어떤 시작일과도 겹친다.
     */
    @Test
    void assigningTheSameRoleWithAnOverlappingPeriodIsRejected() throws Exception {
        assign(staff.getId(), plainRoleId, LocalDate.now().minusDays(30), null);

        mockMvc.perform(
                        authorized(post(rolesOf(staff)), adminToken)
                                .content(assignBody(plainRoleId, LocalDate.now(), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_ALREADY_ASSIGNED"));
    }

    /*
     * **기간이 겹치지 않는 재임은 허용한다.** 작년 국장이 올해 다시 국장이 되는 것은 정상이고,
     * 두 행이 따로 남아야 "언제부터 언제까지였는가"가 보존된다. 같은 (회원, 역할)이면 무조건
     * 막는 구현이면 여기서 409가 되어 드러난다.
     */
    @Test
    void reassigningTheSameRoleAfterItEndedIsAllowed() throws Exception {
        Long first = assign(staff.getId(), plainRoleId, LocalDate.now().minusDays(30), null);
        end(staff.getId(), first, LocalDate.now().minusDays(1));

        Long second = assign(staff.getId(), plainRoleId, LocalDate.now(), null);
        assertThat(second).isNotEqualTo(first);

        mockMvc.perform(authorized(get(rolesOf(staff)), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    // ------------------------------------------------------------------ 대표 역할

    /*
     * 대표는 회원당 유효한 것 중 최대 1건이다. 새로 지정하면 기존 대표가 같은 트랜잭션에서
     * 내려간다 — 두 건이 남으면 사이드바가 무엇을 걸어야 할지 알 수 없다.
     */
    @Test
    void designatingARepresentativeDemotesThePreviousOne() throws Exception {
        Long first = assign(staff.getId(), plainRoleId, LocalDate.now().minusDays(30), true);
        Long second = assign(staff.getId(), workRoleId, LocalDate.now().minusDays(10), true);

        assertThat(representativeOf(first)).isFalse();
        assertThat(representativeOf(second)).isTrue();

        // 수정으로 되돌려도 단일성은 그대로 지켜진다
        mockMvc.perform(
                        authorized(patch(rolesOf(staff) + "/" + first), adminToken)
                                .content("{\"rprsRoleYn\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rprsRoleYn").value(true));

        flushAndClear();
        assertThat(representativeOf(first)).isTrue();
        assertThat(representativeOf(second)).isFalse();
    }

    /*
     * 이미 끝난 배정을 대표로 표시해도 **지금의 대표는 내려가지 않는다.** 단일성은 '유효한 것 중
     * 최대 1건'이라 지난 임기에 붙은 표시는 오늘의 사이드바와 겨루지 않는다 — 여기서 내리면
     * 대표가 하나도 없는 구간이 생긴다.
     */
    @Test
    void designatingAnEndedAssignmentDoesNotDemoteTheCurrentRepresentative() throws Exception {
        Long ended = assign(staff.getId(), plainRoleId, LocalDate.now().minusDays(30), null);
        end(staff.getId(), ended, LocalDate.now().minusDays(1));
        Long current = assign(staff.getId(), workRoleId, LocalDate.now().minusDays(10), true);

        mockMvc.perform(
                        authorized(patch(rolesOf(staff) + "/" + ended), adminToken)
                                .content("{\"rprsRoleYn\": true}"))
                .andExpect(status().isOk());

        flushAndClear();
        assertThat(representativeOf(current)).isTrue();
    }

    // ------------------------------------------------------------------ 목록

    /*
     * **종료된 배정도 목록에는 남는다.** current=true는 지금 유효한 것만 고르고(BR-M25),
     * 생략하면 지난 임기까지 전부다. 종료를 삭제로 구현하면 여기서 목록이 비어 드러난다.
     */
    @Test
    void endedAssignmentsStayInTheFullListButNotInTheCurrentOne() throws Exception {
        Long ended = assign(staff.getId(), plainRoleId, LocalDate.now().minusDays(30), null);
        end(staff.getId(), ended, LocalDate.now().minusDays(1));
        assign(staff.getId(), workRoleId, LocalDate.now().minusDays(10), null);

        mockMvc.perform(authorized(get(rolesOf(staff) + "?current=true"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].roleNm").value("업무 담당 역할"))
                .andExpect(jsonPath("$.data[0].current").value(true));

        mockMvc.perform(authorized(get(rolesOf(staff)), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].roleNm").value(hasItem("권한 없는 역할")))
                .andExpect(jsonPath("$.data[*].current").value(hasItem(false)));
    }

    /*
     * 시작일이 미래인 배정은 아직 유효하지 않다 (BR-M25). 종료일만 보는 구현이면 current=true
     * 목록에 섞여 들어온다.
     */
    @Test
    void futureAssignmentIsNotCurrentYet() throws Exception {
        assign(staff.getId(), plainRoleId, LocalDate.now().plusDays(1), null);

        mockMvc.perform(authorized(get(rolesOf(staff) + "?current=true"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(authorized(get(rolesOf(staff)), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].current").value(false));
    }

    // ------------------------------------------------------------------ 오류

    @Test
    void assigningToAnUnknownMemberIsNotFound() throws Exception {
        mockMvc.perform(
                        authorized(post("/v1/members/999999/roles"), adminToken)
                                .content(assignBody(plainRoleId, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void assigningAnUnknownRoleIsNotFound() throws Exception {
        mockMvc.perform(
                        authorized(post(rolesOf(staff)), adminToken)
                                .content(assignBody(999999L, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    @Test
    void roleIdIsRequired() throws Exception {
        mockMvc.perform(authorized(post(rolesOf(staff)), adminToken).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 비교 대상인 시작일이 저장된 행에 있어 애노테이션으로는 막을 수 없는 검증이다
    @Test
    void endDateEarlierThanStartDateIsRejected() throws Exception {
        Long assignmentId = assign(staff.getId(), plainRoleId, LocalDate.now(), null);

        mockMvc.perform(
                        authorized(patch(rolesOf(staff) + "/" + assignmentId), adminToken)
                                .content(
                                        "{\"roleEndYmd\": \"%s\"}"
                                                .formatted(LocalDate.now().minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /*
     * 경로의 회원과 배정이 어긋나면 404다. 배정 식별자만 보는 구현이면 여기서 200이 되어
     * /v1/members/{남}/roles/{내 배정}이 남의 역할을 끝낸다.
     */
    @Test
    void updatingAnAssignmentThatBelongsToAnotherMemberIsNotFound() throws Exception {
        MemberEntity other = saveMember(UUID.randomUUID(), "20260504", "다른회원");
        Long assignmentId = assign(staff.getId(), plainRoleId, LocalDate.now(), null);

        mockMvc.perform(
                        authorized(patch(rolesOf(other) + "/" + assignmentId), adminToken)
                                .content("{\"rprsRoleYn\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_ROLE_ASSIGNMENT_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 인가

    /*
     * **MEMBER_MANAGE만으로는 통과하지 못한다** — 역할 부여는 회원 정보 변경이 아니라 인가
     * 조작이기 때문이다. 요구 권한을 MEMBER_MANAGE로 잘못 잡으면 회원 정보를 고칠 수 있는
     * 사람이 스스로에게 임원 역할을 붙일 수 있게 되고 그 권한이 사실상 최고 권한이 된다.
     *
     * 조회도 예외가 아니다 (VR-M12) — 클래스 레벨 애노테이션에서 핸들러 하나가 빠지는 실수를
     * 여기서 잡는다.
     */
    @Test
    void callerWithOnlyMemberManageIsForbiddenOnEveryHandler() throws Exception {
        mockMvc.perform(authorized(get(rolesOf(staff)), memberManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(
                        authorized(post(rolesOf(staff)), memberManagerToken)
                                .content(assignBody(plainRoleId, null, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        authorized(patch(rolesOf(staff) + "/1"), memberManagerToken)
                                .content("{\"rprsRoleYn\": true}"))
                .andExpect(status().isForbidden());

        // 아무 권한도 없는 회원도 마찬가지다
        mockMvc.perform(authorized(get(rolesOf(staff)), staffToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 헬퍼

    private Long assign(Long memberId, Long roleId, LocalDate startDate, Boolean representative)
            throws Exception {

        String response =
                mockMvc.perform(
                                authorized(post("/v1/members/" + memberId + "/roles"), adminToken)
                                        .content(assignBody(roleId, startDate, representative)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        flushAndClear();
        return JsonPath.parse(response).read("$.data.mbrRoleId", Long.class);
    }

    private void end(Long memberId, Long assignmentId, LocalDate endDate) throws Exception {
        mockMvc.perform(
                        authorized(
                                        patch("/v1/members/" + memberId + "/roles/" + assignmentId),
                                        adminToken)
                                .content("{\"roleEndYmd\": \"%s\"}".formatted(endDate)))
                .andExpect(status().isOk());
        flushAndClear();
    }

    private static String assignBody(Long roleId, LocalDate startDate, Boolean representative) {
        return "{\"roleId\": %s, \"roleBgngYmd\": %s, \"rprsRoleYn\": %s}"
                .formatted(
                        roleId,
                        startDate == null ? "null" : "\"%s\"".formatted(startDate),
                        representative == null ? "null" : representative);
    }

    private static String rolesOf(MemberEntity member) {
        return "/v1/members/" + member.getId() + "/roles";
    }

    private boolean representativeOf(Long assignmentId) {
        return Boolean.TRUE.equals(
                memberRoleAssignmentRepository
                        .findById(assignmentId)
                        .orElseThrow()
                        .getRepresentative());
    }

    private MemberRoleEntity saveRole(String name) {
        MemberRoleClassificationEntity classification =
                memberRoleClassificationRepository.findById("POSITION").orElseThrow();
        return memberRoleRepository.saveAndFlush(MemberRoleEntity.create(99, name, classification));
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
