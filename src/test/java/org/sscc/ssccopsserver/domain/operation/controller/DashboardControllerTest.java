package org.sscc.ssccopsserver.domain.operation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;

/*
 * 운영 대시보드 API (OPS-038 · ssccops-web#60).
 *
 * 인가·연동만 확인한다 — 각 영역이 담는 값의 규칙(필터·정렬·미리보기 크기)은
 * DashboardServiceImplTest가 이미 못 박아 뒀다. 실제 JWKS 없이 필터체인 전체를 태우기 위해
 * JwtDecoder만 고정 Jwt를 반환하도록 대체한다(WorkControllerTest와 같은 방식).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DashboardControllerTest.StubJwtDecoderConfig.class)
@Transactional
class DashboardControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private WorkService workService;
    @Autowired private SubWorkService subWorkService;

    private MemberEntity actor;

    @BeforeEach
    void setUp() {
        actor = saveMember(AUTH_USER_ID, "20200001", "이서연", "actor@sscc.org");
        // 대시보드는 WORK_MANAGE를 요구한다(#9, WorkController·SubWorkController와 같은 권한)
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                actor,
                MemberRoleFixture.DIRECTOR);
    }

    @Test
    void getDashboardReturns200WithThreeSections() throws Exception {
        Long workId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 하반기 MT",
                                        WorkType.EVENT,
                                        actor.getId(),
                                        null,
                                        null,
                                        null,
                                        null),
                                actor)
                        .workId();
        long approvalFreeTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
        subWorkService.createSubWork(
                new SubWorkCreateRequest(
                        workId,
                        "장소 선정",
                        approvalFreeTypeId,
                        actor.getId(),
                        null,
                        null,
                        OffsetDateTime.now().plusDays(1),
                        null,
                        null,
                        null),
                actor);

        mockMvc.perform(get("/v1/dashboard").header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pendingApproval").isArray())
                .andExpect(jsonPath("$.data.upcomingDeadlines").isArray())
                .andExpect(jsonPath("$.data.upcomingDeadlines.length()").value(1))
                .andExpect(jsonPath("$.data.upcomingDeadlines[0].title").value("장소 선정"))
                .andExpect(jsonPath("$.data.myTasks").isArray())
                .andExpect(jsonPath("$.data.myTasks.length()").value(1))
                .andExpect(jsonPath("$.data.myTasks[0].title").value("장소 선정"));
    }

    @Test
    void getDashboardWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/dashboard")).andExpect(status().isUnauthorized());
    }

    // 운영진이 아닌 회원(스터디장)은 WORK_MANAGE가 없어 인가 단계에서 막힌다
    @Test
    void getDashboardWithoutWorkManageReturns403() throws Exception {
        UUID outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20200002", "박현우", "outsider@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                outsider,
                MemberRoleFixture.STUDY_LEADER);

        mockMvc.perform(get("/v1/dashboard").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                email);
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(resolveSubject(token))
                            .claim("email", "actor@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }

        // 기본 액터는 고정 토큰("any-token")을 쓰고, 인가 실패 테스트만 회원의 authUserId를 토큰으로 쓴다
        private String resolveSubject(String token) {
            return "any-token".equals(token) ? AUTH_USER_ID.toString() : token;
        }
    }
}
