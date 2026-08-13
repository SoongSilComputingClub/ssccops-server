package org.sscc.ssccopsserver.domain.operation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;

/*
 * 실제 JWKS 없이 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록 대체한다.
 * WorkControllerTest와 같은 방식이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SubWorkControllerTest.StubJwtDecoderConfig.class)
@Transactional
class SubWorkControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    // data.sql이 넣는 유형. 1=예산지출(승인 필요)
    private static final long SUB_WORK_TYPE_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberService memberService;
    @Autowired private WorkService workService;

    private Long ownerId;
    private Long registrantId;
    private Long parentWorkId;

    @BeforeEach
    void setUp() {
        MemberEntity owner =
                memberService.findOrProvisionByAuthUserId(UUID.randomUUID(), "owner@sscc.org");
        ownerId = owner.getId();
        // 등록자는 토큰의 sub(AUTH_USER_ID)로 프로비저닝된 회원이며 담당자와 다른 사람이다
        MemberEntity registrant =
                memberService.findOrProvisionByAuthUserId(AUTH_USER_ID, "actor@sscc.org");
        registrantId = registrant.getId();
        parentWorkId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 동아리 박람회",
                                        WorkType.EVENT,
                                        ownerId,
                                        null,
                                        null,
                                        null,
                                        null),
                                registrant)
                        .workId();
    }

    @Test
    void createSubWorkReturns201WithLocation() throws Exception {
        String body =
                """
                {
                  "workId": %d,
                  "title": "부스 배치도 확정",
                  "subWorkTypeId": %d,
                  "ownerId": %d,
                  "startAt": "2026-09-01T18:00:00+09:00",
                  "endAt": "2026-09-01T20:00:00+09:00",
                  "dueAt": "2026-08-25T23:59:00+09:00",
                  "priority": "HIGH",
                  "content": "박람회 부스 위치와 동선을 확정한다",
                  "externalLink": "https://docs.example.com/booth"
                }
                """
                        .formatted(parentWorkId, SUB_WORK_TYPE_ID, ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subWorkId").isNumber())
                .andExpect(jsonPath("$.data.operationId").isNumber())
                .andExpect(jsonPath("$.data.workId").value(parentWorkId))
                .andExpect(jsonPath("$.data.subWorkTypeName").value("예산지출"))
                .andExpect(jsonPath("$.data.workStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.ownerId").value(ownerId))
                .andExpect(jsonPath("$.data.registrantId").value(registrantId))
                .andExpect(jsonPath("$.data.isDelayed").value(false))
                .andExpect(jsonPath("$.data.checklist.length()").value(4));
    }

    @Test
    void missingRequiredFieldReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "상위 업무 없는 하위 업무",
                  "subWorkTypeId": %d,
                  "ownerId": %d
                }
                """
                        .formatted(SUB_WORK_TYPE_ID, ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedExternalLinkReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "workId": %d,
                  "title": "링크 형식 오류",
                  "subWorkTypeId": %d,
                  "ownerId": %d,
                  "externalLink": "not-a-url"
                }
                """
                        .formatted(parentWorkId, SUB_WORK_TYPE_ID, ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownSubWorkTypeReturnsNotFound() throws Exception {
        String body =
                """
                {
                  "workId": %d,
                  "title": "유형 없는 하위 업무",
                  "subWorkTypeId": 999,
                  "ownerId": %d
                }
                """
                        .formatted(parentWorkId, ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unknownParentWorkReturnsNotFound() throws Exception {
        String body =
                """
                {
                  "workId": %d,
                  "title": "상위 업무 없는 하위 업무",
                  "subWorkTypeId": %d,
                  "ownerId": %d
                }
                """
                        .formatted(parentWorkId + 999, SUB_WORK_TYPE_ID, ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/v1/sub-works").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder authenticated(String body) {
        return post("/v1/sub-works")
                .header("Authorization", "Bearer any-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
                            .claim("email", "actor@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
