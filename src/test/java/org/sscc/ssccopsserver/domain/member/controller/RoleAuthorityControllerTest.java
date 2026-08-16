package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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

/*
 * 역할↔권한 부여·회수 API (#65 · ssccops#70).
 *
 * 확인의 중심은 **"바뀐 부여가 다음 요청부터 그대로 인가에 나타난다"**이다 (BR-M31). 그래서
 * 부여·회수 뒤에 실제 업무 엔드포인트를 같은 토큰으로 다시 부른다 — 서비스 응답만 보면
 * "저장은 됐는데 판정은 그대로"인 구현도 통과해 버린다.
 *
 * 자기 잠금 방지(VR-M13)는 실패 뒤 상태까지 봐야 하므로 트랜잭션 없이 도는
 * RoleAuthoritySelfLockTest가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RoleAuthorityControllerTest.StubJwtDecoderConfig.class)
@Transactional
class RoleAuthorityControllerTest {

    /** WORK_MANAGE를 요구하는 엔드포인트 */
    private static final String WORKS = "/v1/works";

    /** SUB_WORK_TYPE_READ(OPERATOR의 자식)를 요구하는 엔드포인트 */
    private static final String SUB_WORK_TYPES = "/v1/sub-work-types";

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
    private UUID outsiderToken;
    private Long targetRoleId;

    @BeforeEach
    void setUp() {
        adminToken = UUID.randomUUID();
        grant(saveMember(adminToken, "20260301", "최고운영자"), AuthorityCode.ROLE_MANAGE);

        // 권한이 하나도 붙지 않은 새 역할과 그 역할만 가진 회원 — 부여의 효과가 그대로 드러난다
        staffToken = UUID.randomUUID();
        MemberRoleEntity targetRole =
                AuthorityFixture.grantRoleWithoutAuthority(
                        memberRoleRepository,
                        memberRoleClassificationRepository,
                        memberRoleAssignmentRepository,
                        saveMember(staffToken, "20260302", "홍보국장"),
                        "홍보국장");
        targetRoleId = targetRole.getId();

        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260303", "업무담당"), AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ 즉시 반영

    /*
     * **이 이슈의 핵심.** 부여 직후 그 역할을 가진 회원이 같은 토큰으로 통과하고, 회수하면 같은
     * 토큰이 403이 된다 — 세션 재발급도 재로그인도 없다. 인가가 요청마다 DB를 보기 때문이며,
     * 인증 시점에 권한을 GrantedAuthority로 굳혔다면 이 테스트가 실패했을 것이다.
     */
    @Test
    void grantAndRevokeTakeEffectWithoutReissuingTheSession() throws Exception {
        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isForbidden());

        mockMvc.perform(replace(targetRoleId, "WORK_MANAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants", hasSize(1)))
                .andExpect(jsonPath("$.data.grants[0].authrtCd").value("WORK_MANAGE"));

        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isOk());

        mockMvc.perform(replace(targetRoleId)).andExpect(status().isOk());

        mockMvc.perform(authorized(get(WORKS), staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 상위(묶음) 권한 하나를 부여하면 자손이 함께 열린다 — 자손을 개별로 또 부여할 필요가 없다는
     * 것이 응답의 effectiveAuthrtCds로도, 실제 요청으로도 같이 드러나야 한다.
     */
    @Test
    void ancestorGrantOpensDescendants() throws Exception {
        mockMvc.perform(replace(targetRoleId, "OPERATOR"))
                .andExpect(status().isOk())
                // 직접 부여된 것은 하나뿐이다
                .andExpect(jsonPath("$.data.grants", hasSize(1)))
                .andExpect(
                        jsonPath("$.data.effectiveAuthrtCds")
                                .value(
                                        hasItems(
                                                "OPERATOR",
                                                "WORK_MANAGE",
                                                "SUB_WORK_TYPE_READ",
                                                "FORM_MANAGE",
                                                "FORM_READ")))
                // 펼침은 아래로만 간다 — 부모(EXECUTIVE)와 그 다른 자식은 열리지 않는다
                .andExpect(
                        jsonPath("$.data.effectiveAuthrtCds")
                                .value(not(hasItems("EXECUTIVE", "ROLE_MANAGE"))));

        mockMvc.perform(authorized(get(WORKS), staffToken)).andExpect(status().isOk());
        mockMvc.perform(authorized(get(SUB_WORK_TYPES), staffToken)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 전체 교체

    /*
     * 교체의 세 갈래를 한 번에 본다 — 요청에 없는 것은 회수, 새로 온 것은 부여, 유지되는 것은
     * 손대지 않는다. 유지된 부여가 같은 role_authrt_id와 같은 crt_dt를 갖는지가 핵심이다.
     * 지우고 다시 넣는 구현이면 "언제 이 권한이 붙었는가"를 저장할 때마다 잃는다.
     */
    @Test
    void replacesGrantsAndPreservesCreatedAtOfSurvivingOnes() throws Exception {
        mockMvc.perform(replace(targetRoleId, "WORK_MANAGE", "FORM_READ"))
                .andExpect(status().isOk());

        flushAndClear();
        RoleAuthorityRelationEntity kept = grantOf(targetRoleId, "WORK_MANAGE");
        Long keptId = kept.getId();
        Instant keptCreatedAt = kept.getCreatedAt();

        mockMvc.perform(replace(targetRoleId, "WORK_MANAGE", "MEMBER_MANAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants", hasSize(2)))
                .andExpect(
                        jsonPath("$.data.grants[*].authrtCd")
                                .value(containsInAnyOrder("MEMBER_MANAGE", "WORK_MANAGE")));

        flushAndClear();
        List<RoleAuthorityRelationEntity> after =
                roleAuthorityRelationRepository.findAllByRoleId(targetRoleId);
        assertThat(after).hasSize(2);

        RoleAuthorityRelationEntity keptAfter = grantOf(targetRoleId, "WORK_MANAGE");
        assertThat(keptAfter.getId()).isEqualTo(keptId);
        assertThat(keptAfter.getCreatedAt()).isEqualTo(keptCreatedAt);
    }

    // 같은 요청을 두 번 보내도 행이 늘거나 지웠다 다시 생기지 않는다 — 저장 버튼은 두 번 눌린다
    @Test
    void repeatingTheSameReplaceRequestIsIdempotent() throws Exception {
        mockMvc.perform(replace(targetRoleId, "WORK_MANAGE", "FORM_READ"))
                .andExpect(status().isOk());
        flushAndClear();
        List<Long> firstIds = grantIdsOf(targetRoleId);

        mockMvc.perform(replace(targetRoleId, "WORK_MANAGE", "FORM_READ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants", hasSize(2)));

        flushAndClear();
        assertThat(grantIdsOf(targetRoleId)).isEqualTo(firstIds);
    }

    // 같은 코드를 두 번 실어 보내도 한 번으로 본다 — 화면 실수가 UNIQUE 위반으로 번지면 안 된다
    @Test
    void duplicatedCodeInRequestCreatesOneGrant() throws Exception {
        mockMvc.perform(replace(targetRoleId, "WORK_MANAGE", "WORK_MANAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants", hasSize(1)));

        flushAndClear();
        assertThat(roleAuthorityRelationRepository.findAllByRoleId(targetRoleId)).hasSize(1);
    }

    // 빈 배열은 오류가 아니라 "전부 회수"다
    @Test
    void emptyListRevokesEverything() throws Exception {
        mockMvc.perform(replace(targetRoleId, "WORK_MANAGE")).andExpect(status().isOk());
        flushAndClear();

        mockMvc.perform(replace(targetRoleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants", hasSize(0)))
                .andExpect(jsonPath("$.data.effectiveAuthrtCds", hasSize(0)));

        flushAndClear();
        assertThat(roleAuthorityRelationRepository.findAllByRoleId(targetRoleId)).isEmpty();
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void getReturnsDirectGrantsAndExpandedSet() throws Exception {
        roleAuthorityRelationRepository.saveAndFlush(
                RoleAuthorityRelationEntity.create(
                        memberRoleRepository.findById(targetRoleId).orElseThrow(),
                        authorityRepository.findById("FORM_MANAGE").orElseThrow()));

        mockMvc.perform(authorized(get(rolePath(targetRoleId)), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleId").value(targetRoleId))
                .andExpect(jsonPath("$.data.roleNm").value("홍보국장"))
                .andExpect(jsonPath("$.data.grants", hasSize(1)))
                .andExpect(jsonPath("$.data.grants[0].authrtCd").value("FORM_MANAGE"))
                .andExpect(jsonPath("$.data.grants[0].crtDt").exists())
                .andExpect(
                        jsonPath("$.data.effectiveAuthrtCds")
                                .value(
                                        containsInAnyOrder(
                                                "FORM_MANAGE",
                                                "FORM_READ",
                                                "FORM_STATUS_CHANGE",
                                                "FORM_WRITE")));
    }

    // ------------------------------------------------------------------ 거절

    @Test
    void callerWithoutRoleManageIsForbidden() throws Exception {
        mockMvc.perform(authorized(get(rolePath(targetRoleId)), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unknownRoleReturnsNotFound() throws Exception {
        mockMvc.perform(authorized(get(rolePath(999999L)), adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    @Test
    void unknownAuthorityInReplaceReturnsNotFound() throws Exception {
        mockMvc.perform(replace(targetRoleId, "NO_SUCH_AUTHORITY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTHORITY_NOT_FOUND"));
    }

    // authrtCds 자체가 빠지면 "건드리지 마라"인지 "전부 회수하라"인지 알 수 없다
    @Test
    void missingAuthrtCdsIsRejected() throws Exception {
        mockMvc.perform(authorized(put(rolePath(targetRoleId)), adminToken).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private static String rolePath(Long roleId) {
        return "/v1/roles/" + roleId + "/authorities";
    }

    private MockHttpServletRequestBuilder replace(Long roleId, String... codes) {
        String body =
                "{\"authrtCds\": [%s]}"
                        .formatted(
                                String.join(
                                        ", ",
                                        java.util.Arrays.stream(codes)
                                                .map("\"%s\""::formatted)
                                                .toList()));
        return authorized(put(rolePath(roleId)), adminToken).content(body);
    }

    private RoleAuthorityRelationEntity grantOf(Long roleId, String code) {
        return roleAuthorityRelationRepository.findAllByRoleId(roleId).stream()
                .filter(relation -> relation.getAuthority().getCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private List<Long> grantIdsOf(Long roleId) {
        return roleAuthorityRelationRepository.findAllByRoleId(roleId).stream()
                .map(RoleAuthorityRelationEntity::getId)
                .sorted()
                .toList();
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
