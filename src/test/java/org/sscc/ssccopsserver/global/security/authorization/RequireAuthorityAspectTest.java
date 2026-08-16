package org.sscc.ssccopsserver.global.security.authorization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hamcrest.Matchers;
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
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
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
 * @RequireAuthority가 실제 요청에서 어떤 응답을 내는지 확인한다 (#9 · ssccops#68 수용 기준).
 *
 * 판정 규칙 자체는 AuthorityPolicyTest가 다루고, 여기서는 필터체인·애스펙트·예외 핸들러를
 * 통째로 지나온 뒤의 상태 코드와 코드 문자열을 본다 — 401 / 403 SIGNUP_REQUIRED /
 * 403 FORBIDDEN이 실제로 갈리는지가 요점이다. **어떤 경우에도 404가 아니다** (VR-M10).
 *
 * 토큰 없이 실제 JWKS를 부를 수 없으므로 JwtDecoder를 대체한다. 스텁이 토큰 문자열을 그대로
 * sub로 쓰므로, 테스트는 Bearer 자리에 인증 사용자 UUID를 넣어 주체를 바꾼다 — 한 테스트
 * 클래스 안에서 여러 회원(과 미가입 주체)을 번갈아 흉내 내야 하기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RequireAuthorityAspectTest.StubJwtDecoderConfig.class)
@Transactional
class RequireAuthorityAspectTest {

    /** WORK_MANAGE를 요구하는 엔드포인트 */
    private static final String WORKS = "/v1/works";

    /** SUB_WORK_TYPE_READ(OPERATOR의 자식)를 요구하는 엔드포인트 */
    private static final String SUB_WORK_TYPES = "/v1/sub-work-types";

    /*
     * SUB_WORK_TYPE_MANAGE(EXECUTIVE 직속)를 요구하는 엔드포인트. 본문이 유효해야 인가까지
     * 도달한다 — 애스펙트는 핸들러 호출 직전에 돌므로 @Valid 검증이 먼저 끝난다.
     */
    private static final String SUB_WORK_TYPE_ACTIVATION = "/v1/sub-work-types/999/activation";

    private static final String ACTIVATION_BODY = "{\"useYn\": false}";

    /** 권한 요구가 없는 엔드포인트. 인증(과 가입)만으로 통과해야 한다 */
    private static final String FORM_LABELS = "/v1/form-labels";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    /* ── 인증·가입 단계 ──────────────────────────────────── */

    // 권한 검사 이전에 끊긴다. 토큰이 없으면 애스펙트까지 오지 않는다
    @Test
    void requestWithoutTokenIs401() throws Exception {
        mockMvc.perform(get(WORKS)).andExpect(status().isUnauthorized());
    }

    /*
     * 인증은 됐지만 mbr 행이 없는 주체. 권한 부족(FORBIDDEN)과 상태 코드는 같지만 코드 문자열이
     * 달라야 한다 — 프론트가 한쪽은 가입 화면으로, 다른 쪽은 "권한 없음"으로 안내한다.
     */
    @Test
    void authenticatedButNotSignedUpIs403SignupRequired() throws Exception {
        mockMvc.perform(authorized(get(WORKS), UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    /* ── 권한 부족 ───────────────────────────────────────── */

    @Test
    void signedUpMemberWithoutAnyRoleIs403Forbidden() throws Exception {
        UUID authUserId = UUID.randomUUID();
        saveMember(authUserId, "20260101", "임시회원");

        mockMvc.perform(authorized(get(WORKS), authUserId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 권한이 하나도 붙지 않은 새 역할은 아무것도 못 한다 — 그것이 기본값이다
    @Test
    void roleWithoutAnyAuthorityIs403Forbidden() throws Exception {
        UUID authUserId = UUID.randomUUID();
        MemberEntity member = saveMember(authUserId, "20260102", "신설역할");
        AuthorityFixture.grantRoleWithoutAuthority(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                member,
                "신설국장");

        mockMvc.perform(authorized(get(WORKS), authUserId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 임기가 끝난 역할의 권한은 인정되지 않는다 (BR-M25). 배정 행 자체는 남아 있으므로
     * "역할이 있다"만 보는 구현이었다면 이 요청이 통과했을 것이다.
     */
    @Test
    void expiredRoleIs403Forbidden() throws Exception {
        UUID authUserId = UUID.randomUUID();
        MemberEntity member = saveMember(authUserId, "20260103", "임기만료");
        grantForPeriod(
                member,
                AuthorityCode.EXECUTIVE,
                LocalDate.now().minusYears(2),
                LocalDate.now().minusDays(1));

        mockMvc.perform(authorized(get(WORKS), authUserId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* ── 통과 ────────────────────────────────────────────── */

    @Test
    void directGrantPasses() throws Exception {
        UUID authUserId = UUID.randomUUID();
        MemberEntity member = saveMember(authUserId, "20260104", "업무담당");
        grant(member, AuthorityCode.WORK_MANAGE);

        mockMvc.perform(authorized(get(WORKS), authUserId)).andExpect(status().isOk());
    }

    /** 상위(묶음) 권한만 받아도 자손까지 펼쳐져 통과한다 */
    @Test
    void ancestorGrantPassesThroughExpansion() throws Exception {
        UUID authUserId = UUID.randomUUID();
        MemberEntity member = saveMember(authUserId, "20260105", "임원");
        grant(member, AuthorityCode.EXECUTIVE);

        mockMvc.perform(authorized(get(WORKS), authUserId)).andExpect(status().isOk());
        mockMvc.perform(authorized(get(SUB_WORK_TYPES), authUserId)).andExpect(status().isOk());
    }

    /*
     * **펼침이 위로 가지 않는다는 것을 요청 하나로 보여주는 자리다.**
     *
     * OPERATOR를 받은 회원은 그 자손인 SUB_WORK_TYPE_READ에 닿아 조회는 통과하지만,
     * 부모(EXECUTIVE)의 다른 자식인 SUB_WORK_TYPE_MANAGE에는 닿지 않아 사용 여부 전환은 403이다.
     * 하위를 가졌다고 상위가 생긴다면 이 요청이 통과했을 것이다(그랬다면 없는 유형이라 404였다).
     */
    @Test
    void descendantGrantDoesNotReachAnAncestorOnlyEndpoint() throws Exception {
        UUID authUserId = UUID.randomUUID();
        MemberEntity member = saveMember(authUserId, "20260106", "운영자");
        grant(member, AuthorityCode.OPERATOR);

        mockMvc.perform(authorized(get(SUB_WORK_TYPES), authUserId)).andExpect(status().isOk());

        mockMvc.perform(
                        authorized(patch(SUB_WORK_TYPE_ACTIVATION), authUserId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ACTIVATION_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 권한 요구가 선언되지 않은 엔드포인트는 인증(과 가입)만으로 통과한다 — 기본값은 인증이며
     * 애스펙트가 모든 요청에 끼어들지 않는다.
     */
    @Test
    void endpointWithoutRequirementPassesWithMereAuthentication() throws Exception {
        UUID authUserId = UUID.randomUUID();
        saveMember(authUserId, "20260107", "역할없음");

        mockMvc.perform(authorized(get(FORM_LABELS), authUserId)).andExpect(status().isOk());
    }

    /* ── capabilities와 판정의 일치 ──────────────────────── */

    /*
     * 세션 응답의 capabilities는 애스펙트가 내리는 판정과 같은 계산이어야 한다 (BR-M28).
     * 목록에 있는 권한의 엔드포인트는 통과하고 없는 권한의 엔드포인트는 403이라는 것이
     * "같다"의 뜻이다 — 어긋나면 버튼은 보이는데 누르면 403이 되는 화면이 생긴다.
     */
    @Test
    void capabilitiesAgreeWithTheAspectDecision() throws Exception {
        UUID authUserId = UUID.randomUUID();
        MemberEntity member = saveMember(authUserId, "20260108", "운영자");
        grant(member, AuthorityCode.OPERATOR);

        mockMvc.perform(authorized(get("/v1/auth/session"), authUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.capabilities").isArray())
                // 펼쳐진 자손이 들어 있다
                .andExpect(
                        jsonPath("$.data.member.capabilities")
                                .value(
                                        Matchers.hasItems(
                                                "OPERATOR",
                                                "WORK_MANAGE",
                                                "SUB_WORK_TYPE_READ",
                                                "FORM_MANAGE",
                                                "FORM_READ",
                                                "RESPONSE_REVIEW")))
                // 상위(EXECUTIVE)와 그 다른 자식은 들어 있지 않다 — 펼침은 아래로만 간다
                .andExpect(
                        jsonPath("$.data.member.capabilities")
                                .value(
                                        Matchers.not(
                                                Matchers.hasItems(
                                                        "EXECUTIVE",
                                                        "SUB_WORK_TYPE_MANAGE",
                                                        "FORM_LABEL_MANAGE"))));

        // 목록에 있는 권한은 실제로 통과하고, 없는 권한은 실제로 403이다
        mockMvc.perform(authorized(get(SUB_WORK_TYPES), authUserId)).andExpect(status().isOk());
        mockMvc.perform(
                        authorized(patch(SUB_WORK_TYPE_ACTIVATION), authUserId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ACTIVATION_BODY))
                .andExpect(status().isForbidden());
    }

    /** 역할이 없는 회원의 capabilities는 빈 배열이다(null이 아니다) */
    @Test
    void memberWithoutRoleHasEmptyCapabilities() throws Exception {
        UUID authUserId = UUID.randomUUID();
        saveMember(authUserId, "20260109", "임시회원");

        mockMvc.perform(authorized(get("/v1/auth/session"), authUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.capabilities").isArray())
                .andExpect(jsonPath("$.data.member.capabilities").isEmpty());
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authorized(
                    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                            builder,
                    UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId);
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

    private void grantForPeriod(
            MemberEntity member, AuthorityCode authority, LocalDate start, LocalDate end) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority,
                start,
                end);
    }

    /*
     * 토큰 문자열을 그대로 sub로 쓰는 스텁. 다른 컨트롤러 테스트들이 고정 sub를 쓰는 것과 달리
     * 여기서는 주체를 요청마다 바꿔야 해서(미가입·역할 없음·권한 있음 …) 토큰에 실어 보낸다.
     */
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
