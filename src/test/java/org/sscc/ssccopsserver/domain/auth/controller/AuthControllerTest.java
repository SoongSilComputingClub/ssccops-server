package org.sscc.ssccopsserver.domain.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 실제 JWKS 없이 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록 대체한다.
 * 각 테스트는 트랜잭션 롤백되므로, 회원을 만들지 않은 테스트는 그대로 미가입 상태가 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AuthControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @Test
    void signedUpUserGetsMemberProfile() throws Exception {
        MemberEntity member = saveMember();

        mockMvc.perform(session())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.signedUp").value(true))
                .andExpect(jsonPath("$.data.authUser.id").value(AUTH_USER_ID.toString()))
                .andExpect(jsonPath("$.data.authUser.email").value("test@sscc.org"))
                .andExpect(jsonPath("$.data.member.memberId").value(member.getId()))
                .andExpect(jsonPath("$.data.member.studentNumber").value("20200001"))
                .andExpect(jsonPath("$.data.member.name").value("김도현"))
                .andExpect(jsonPath("$.data.member.membershipGradeCode").value("TEMP"))
                .andExpect(jsonPath("$.data.member.membershipGradeName").value("임시회원"))
                .andExpect(jsonPath("$.data.member.membershipStatusCode").value("ENROLLED"))
                .andExpect(jsonPath("$.data.member.roles").isEmpty());
    }

    /*
     * 로그인은 됐지만 아직 가입하지 않은 사용자. 오류가 아니라 정상 세션 상태이므로 200이며,
     * 프론트는 authUser로 가입 화면을 채운다.
     */
    @Test
    void unregisteredUserGetsSessionWithoutMember() throws Exception {
        mockMvc.perform(session())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signedUp").value(false))
                .andExpect(jsonPath("$.data.member").isEmpty())
                .andExpect(jsonPath("$.data.authUser.id").value(AUTH_USER_ID.toString()))
                .andExpect(jsonPath("$.data.authUser.email").value("test@sscc.org"))
                .andExpect(jsonPath("$.data.authUser.name").value("김도현"))
                .andExpect(jsonPath("$.data.authUser.provider").value("google"));
    }

    // 사이드바가 대표 역할을 표시하므로 현재 역할(종료일 없는 배정)만 담겨야 한다
    @Test
    void currentRolesAreIncludedAndEndedOnesAreNot() throws Exception {
        MemberEntity member = saveMember();
        MemberRoleClassificationEntity classification =
                memberRoleClassificationRepository.save(
                        MemberRoleClassificationEntity.create("EXEC", "집행부", 1));
        MemberRoleEntity director =
                memberRoleRepository.save(MemberRoleEntity.create(1, "기획국장", classification));
        MemberRoleEntity formerRole =
                memberRoleRepository.save(MemberRoleEntity.create(2, "전임 총무", classification));

        memberRoleAssignmentRepository.save(
                MemberRoleAssignmentEntity.create(
                        member, director, LocalDate.of(2026, 3, 1), true));
        MemberRoleAssignmentEntity ended =
                MemberRoleAssignmentEntity.create(
                        member, formerRole, LocalDate.of(2025, 3, 1), false);
        ended.end(LocalDate.of(2026, 2, 28));
        memberRoleAssignmentRepository.save(ended);

        mockMvc.perform(session())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.roles.length()").value(1))
                .andExpect(jsonPath("$.data.member.roles[0].roleName").value("기획국장"))
                .andExpect(jsonPath("$.data.member.roles[0].representative").value(true));
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/auth/session")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder session() {
        return get("/v1/auth/session").header("Authorization", "Bearer any-token");
    }

    private MemberEntity saveMember() {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                AUTH_USER_ID,
                "20200001",
                "김도현",
                "member@sscc.org");
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(AUTH_USER_ID.toString())
                            .claim("email", "test@sscc.org")
                            .claim("user_metadata", Map.of("full_name", "김도현"))
                            .claim("app_metadata", Map.of("provider", "google"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
