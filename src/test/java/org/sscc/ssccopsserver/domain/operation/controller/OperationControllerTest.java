package org.sscc.ssccopsserver.domain.operation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingCategory;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.service.MeetingService;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;

/*
 * 운영 통합 조회 API (OPS-001 · ssccops-web#63).
 *
 * 인가·연동과 세 배열의 조립만 확인한다 — 각 배열이 담는 값의 규칙(진행률·지연 판정·집계)은
 * WorkServiceImplSearchTest·SubWorkServiceImplSearchTest·DashboardServiceImplTest가 이미
 * 못 박아 뒀다. 실제 JWKS 없이 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를
 * 반환하도록 대체한다(DashboardControllerTest와 같은 방식).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OperationControllerTest.StubJwtDecoderConfig.class)
@Transactional
class OperationControllerTest {

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
    @Autowired private MeetingService meetingService;

    private MemberEntity actor;

    @BeforeEach
    void setUp() {
        actor = saveMember(AUTH_USER_ID, "20200001", "이서연", "actor@sscc.org");
        // 운영 통합은 WORK_MANAGE를 요구한다(#9, 대시보드·업무·하위 업무와 같은 권한)
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                actor,
                MemberRoleFixture.DIRECTOR);
    }

    @Test
    void getOperationHubReturns200WithThreeSections() throws Exception {
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
        meetingService.createMeeting(
                new MeetingCreateRequest(
                        "8월 정례 회의",
                        MeetingCategory.REGULAR,
                        actor.getId(),
                        OffsetDateTime.now().plusDays(2),
                        null,
                        null,
                        null,
                        null,
                        List.of()),
                actor);

        mockMvc.perform(get("/v1/operations").header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.works.length()").value(1))
                .andExpect(jsonPath("$.data.works[0].workId").value(workId))
                .andExpect(jsonPath("$.data.works[0].title").value("2026 하반기 MT"))
                .andExpect(jsonPath("$.data.works[0].subWorkCount").value(1))
                .andExpect(jsonPath("$.data.subWorks.length()").value(1))
                .andExpect(jsonPath("$.data.subWorks[0].title").value("장소 선정"))
                // 트리의 연결 고리 — 하위 업무 행이 상위 업무 식별자를 실어야 화면이 묶는다
                .andExpect(jsonPath("$.data.subWorks[0].work.workId").value(workId))
                .andExpect(jsonPath("$.data.meetings.length()").value(1))
                .andExpect(jsonPath("$.data.meetings[0].title").value("8월 정례 회의"));
    }

    // 아무것도 등록되지 않은 환경에서도 404가 아니라 빈 배열 세 개다
    @Test
    void getOperationHubWithNoDataReturnsEmptyArrays() throws Exception {
        mockMvc.perform(get("/v1/operations").header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.works").isEmpty())
                .andExpect(jsonPath("$.data.subWorks").isEmpty())
                .andExpect(jsonPath("$.data.meetings").isEmpty());
    }

    @Test
    void getOperationHubWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/operations")).andExpect(status().isUnauthorized());
    }

    // 운영진이 아닌 회원(스터디장)은 WORK_MANAGE가 없어 인가 단계에서 막힌다
    @Test
    void getOperationHubWithoutWorkManageReturns403() throws Exception {
        UUID outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20200002", "박현우", "outsider@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                outsider,
                MemberRoleFixture.STUDY_LEADER);

        mockMvc.perform(get("/v1/operations").header("Authorization", "Bearer " + outsiderToken))
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
