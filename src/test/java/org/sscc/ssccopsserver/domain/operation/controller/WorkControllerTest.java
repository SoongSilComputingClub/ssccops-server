package org.sscc.ssccopsserver.domain.operation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 실제 JWKS 없이 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록 대체한다.
 * SupabaseJwtAuthenticationIntegrationTest와 같은 방식이며,
 * SecurityMockMvcRequestPostProcessors.jwt()는 커스텀 컨버터를 우회하므로 쓰지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WorkControllerTest.StubJwtDecoderConfig.class)
@Transactional
class WorkControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    private Long ownerId;
    private Long registrantId;

    @BeforeEach
    void setUp() {
        ownerId = saveMember(UUID.randomUUID(), "20200001", "김도현", "owner@sscc.org").getId();
        // 등록자는 토큰의 sub(AUTH_USER_ID)와 연결된 회원이며 담당자와 다른 사람이다
        MemberEntity registrant = saveMember(AUTH_USER_ID, "20200002", "이서연", "actor@sscc.org");
        registrantId = registrant.getId();

        // 업무 API는 WORK_MANAGE를 요구한다 (#9). 국장이 OPERATOR로 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                registrant,
                MemberRoleFixture.DIRECTOR);
    }

    @Test
    void createWorkReturns201WithLocation() throws Exception {
        String body =
                """
                {
                  "title": "동아리 박람회 부스 운영",
                  "itemType": "EVENT",
                  "ownerId": %d,
                  "startAt": "2026-09-01T18:00:00+09:00",
                  "endAt": "2026-09-01T20:00:00+09:00",
                  "priority": "NORMAL",
                  "review": null
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workId").isNumber())
                .andExpect(jsonPath("$.data.operationId").isNumber())
                .andExpect(jsonPath("$.data.workStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data.itemType").value("EVENT"))
                .andExpect(jsonPath("$.data.priority").value("NORMAL"))
                .andExpect(jsonPath("$.data.ownerId").value(ownerId))
                .andExpect(jsonPath("$.data.registrantId").value(registrantId));
    }

    @Test
    void missingRequiredFieldReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "itemType": "EVENT",
                  "ownerId": %d
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownItemTypeReturnsInvalidCodeValue() throws Exception {
        String body =
                """
                {
                  "title": "코드값 밖 업무",
                  "itemType": "FESTIVAL",
                  "ownerId": %d
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void invertedPeriodReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "기간 역전 업무",
                  "itemType": "EVENT",
                  "ownerId": %d,
                  "startAt": "2026-09-01T20:00:00+09:00",
                  "endAt": "2026-09-01T18:00:00+09:00"
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownOwnerReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "담당자 없는 업무",
                  "itemType": "EVENT",
                  "ownerId": %d
                }
                """
                        .formatted(ownerId + 999);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 화면의 우선순위 버튼은 3종뿐이라 그 밖의 값은 기준 코드 위반이다
    @Test
    void unknownPriorityReturnsInvalidCodeValue() throws Exception {
        String body =
                """
                {
                  "title": "우선순위 코드값 밖",
                  "itemType": "EVENT",
                  "ownerId": %d,
                  "priority": "URGENT"
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(authenticated(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/v1/works").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
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

    /*
     * 상세 조회(OPS-003). 하위 업무가 없는 업무라 진행률은 0이고 목록은 빈 배열이다 —
     * 값이 없어도 필드는 내린다 (AP-15). 진행률 계산 자체는 서비스 테스트가 다룬다.
     */
    @Test
    void getWorkReturns200WithDetail() throws Exception {
        Long workId = createWork();

        mockMvc.perform(
                        get("/v1/works/{workId}", workId)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workId").value(workId))
                .andExpect(jsonPath("$.data.operationId").isNumber())
                .andExpect(jsonPath("$.data.operationType").value("WORK"))
                .andExpect(jsonPath("$.data.title").value("동아리 박람회 부스 운영"))
                .andExpect(jsonPath("$.data.workType").value("EVENT"))
                .andExpect(jsonPath("$.data.workStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data.priority").value("NORMAL"))
                .andExpect(jsonPath("$.data.owner.memberId").value(ownerId))
                .andExpect(jsonPath("$.data.owner.name").exists())
                .andExpect(jsonPath("$.data.registrant.memberId").value(registrantId))
                .andExpect(jsonPath("$.data.startAt").value("2026-09-01T18:00:00+09:00"))
                .andExpect(jsonPath("$.data.progressRate").value(0))
                .andExpect(jsonPath("$.data.subWorkCount").value(0))
                .andExpect(jsonPath("$.data.subWorks").isArray())
                .andExpect(jsonPath("$.data.subWorks").isEmpty());
    }

    @Test
    void getUnknownWorkReturns404() throws Exception {
        mockMvc.perform(
                        get("/v1/works/{workId}", 999_999L)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getWorkWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/works/{workId}", 1L)).andExpect(status().isUnauthorized());
    }

    /*
     * 기본 정보 수정(OPS-004). 값이 실제로 바뀌는지는 서비스 테스트가 다루므로 여기서는
     * 응답 형태·상태 코드만 확인한다.
     */
    @Test
    void updateWorkReturns200WithUpdatedDetail() throws Exception {
        Long workId = createWork();
        String body =
                """
                {
                  "title": "동아리 박람회 부스 운영 (수정)",
                  "itemType": "ROUTINE",
                  "ownerId": %d,
                  "startAt": "2026-09-02T18:00:00+09:00",
                  "endAt": "2026-09-02T20:00:00+09:00",
                  "priority": "HIGH",
                  "review": "부스 위치 확정"
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(
                        patch("/v1/works/{workId}", workId)
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workId").value(workId))
                .andExpect(jsonPath("$.data.title").value("동아리 박람회 부스 운영 (수정)"))
                .andExpect(jsonPath("$.data.workType").value("ROUTINE"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.generalReview").value("부스 위치 확정"))
                // workStatus는 요청 본문에 필드가 없어 바뀌지 않는다 (POL-003)
                .andExpect(jsonPath("$.data.workStatus").value("PLANNING"));
    }

    /*
     * workStatus는 요청 DTO에 필드가 없다(POL-003). Jackson이 미인식 필드를 기본적으로
     * 조용히 무시하므로(폼 도메인의 formSttsCd와 같은 규칙) 요청은 그대로 성공하고,
     * 실려 보낸 workStatus는 응답에 반영되지 않는다 — 거절이 아니라 무시다.
     */
    @Test
    void updateWorkIgnoresUnknownStatusField() throws Exception {
        Long workId = createWork();
        String body =
                """
                {
                  "title": "상태 변경 시도",
                  "itemType": "EVENT",
                  "ownerId": %d,
                  "workStatus": "DONE"
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(
                        patch("/v1/works/{workId}", workId)
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("상태 변경 시도"))
                .andExpect(jsonPath("$.data.workStatus").value("PLANNING"));
    }

    @Test
    void updateUnknownWorkReturns404() throws Exception {
        String body =
                """
                {
                  "title": "없는 업무 수정",
                  "itemType": "EVENT",
                  "ownerId": %d
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(
                        patch("/v1/works/{workId}", 999_999L)
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updateWorkWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        patch("/v1/works/{workId}", 1L)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * 목록 조회(OPS-020). 봉투가 단건과 다르다 — data가 배열이고 page가 그 옆에 온다 (AP-11).
     * 값 계산 자체는 서비스 테스트가 다루고 여기서는 응답 형태와 상태 코드를 본다.
     */
    @Test
    void searchWorksReturns200WithListEnvelope() throws Exception {
        Long workId = createWork();

        mockMvc.perform(get("/v1/works").header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].workId").value(workId))
                .andExpect(jsonPath("$.data[0].title").value("동아리 박람회 부스 운영"))
                .andExpect(jsonPath("$.data[0].workType").value("EVENT"))
                .andExpect(jsonPath("$.data[0].workStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data[0].owner.memberId").value(ownerId))
                .andExpect(jsonPath("$.data[0].owner.name").exists())
                .andExpect(jsonPath("$.data[0].startAt").value("2026-09-01T18:00:00+09:00"))
                .andExpect(jsonPath("$.data[0].progressRate").value(0))
                .andExpect(jsonPath("$.data[0].subWorkCount").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.sort").value("-createdAt"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(1));
    }

    // 결과가 없어도 200에 빈 배열이다. 404가 아니다
    @Test
    void searchWorksWithNoMatchReturnsEmptyArray() throws Exception {
        createWork();

        mockMvc.perform(
                        get("/v1/works")
                                .param("workType", "ROUTINE")
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalCount").value(0))
                .andExpect(jsonPath("$.page.overallCount").value(1));
    }

    @Test
    void searchWorksWithUnknownStatusReturnsInvalidCodeValue() throws Exception {
        mockMvc.perform(
                        get("/v1/works")
                                .param("workStatus", "기획")
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    // size 상한은 100이다 (AP-13). 넘기면 형식 오류로 막는다
    @Test
    void searchWorksWithTooLargeSizeReturnsValidationFailed() throws Exception {
        mockMvc.perform(
                        get("/v1/works")
                                .param("size", "101")
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchWorksWithMalformedCursorReturnsValidationFailed() throws Exception {
        mockMvc.perform(
                        get("/v1/works")
                                .param("cursor", "!!not-a-cursor!!")
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchWorksWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/works")).andExpect(status().isUnauthorized());
    }

    private Long createWork() throws Exception {
        String body =
                """
                {
                  "title": "동아리 박람회 부스 운영",
                  "itemType": "EVENT",
                  "ownerId": %d,
                  "startAt": "2026-09-01T18:00:00+09:00",
                  "endAt": "2026-09-01T20:00:00+09:00"
                }
                """
                        .formatted(ownerId);

        String response =
                mockMvc.perform(authenticated(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.workId", Long.class);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authenticated(String body) {
        return post("/v1/works")
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
