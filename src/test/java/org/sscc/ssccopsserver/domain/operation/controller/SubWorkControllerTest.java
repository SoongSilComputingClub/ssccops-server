package org.sscc.ssccopsserver.domain.operation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;

import com.jayway.jsonpath.JsonPath;

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

    private static final UUID SPB_USER_ID = UUID.randomUUID();

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
                memberService.findOrProvisionBySpbUserId(UUID.randomUUID(), "owner@sscc.org");
        ownerId = owner.getId();
        // 등록자는 토큰의 sub(SPB_USER_ID)로 프로비저닝된 회원이며 담당자와 다른 사람이다
        MemberEntity registrant =
                memberService.findOrProvisionBySpbUserId(SPB_USER_ID, "actor@sscc.org");
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

    // 상세 화면(OPS-SCR-002)이 진입 시 호출하는 조회. 화면이 쓰는 값이 다 담겨 나와야 한다
    @Test
    void getSubWorkReturns200WithDetail() throws Exception {
        Long subWorkId = createSubWork();

        mockMvc.perform(
                        get("/v1/sub-works/{subWorkId}", subWorkId)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subWorkId").value(subWorkId))
                .andExpect(jsonPath("$.data.workId").value(parentWorkId))
                .andExpect(jsonPath("$.data.operationType").value("SUB_WORK"))
                .andExpect(jsonPath("$.data.title").value("부스 배치도 확정"))
                .andExpect(jsonPath("$.data.subWorkTypeName").value("예산지출"))
                .andExpect(jsonPath("$.data.workStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.approvalRequired").value(true))
                .andExpect(jsonPath("$.data.owner.memberId").value(ownerId))
                .andExpect(jsonPath("$.data.owner.name").isNotEmpty())
                .andExpect(jsonPath("$.data.registrant.memberId").value(registrantId))
                .andExpect(jsonPath("$.data.collaborators").isEmpty())
                .andExpect(jsonPath("$.data.isDelayed").value(false))
                .andExpect(jsonPath("$.data.checklist.length()").value(4))
                .andExpect(jsonPath("$.data.checklist[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data.checklistSummary.completedCount").value(0))
                .andExpect(jsonPath("$.data.checklistSummary.totalCount").value(4))
                // 기수는 프론트 디자인에서 빠져 응답에도 두지 않는다
                .andExpect(jsonPath("$.data.owner.generationNumber").doesNotExist());
    }

    @Test
    void getUnknownSubWorkReturns404() throws Exception {
        mockMvc.perform(get("/v1/sub-works/{subWorkId}", 999).header("Authorization", "Bearer any"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getSubWorkWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/sub-works/{subWorkId}", 1)).andExpect(status().isUnauthorized());
    }

    /*
     * 상세 화면(OPS-SCR-002)의 '완료 승인' 버튼이 부르는 경로. 여기서는 체크리스트를 채우지
     * 않은 채 검토까지만 올려 두고, 완료 승인은 다음 테스트에서 409로 막히는지 본다.
     */
    @Test
    void transitionReturns200WithChangedStatus() throws Exception {
        Long subWorkId = createSubWork();

        transition(subWorkId, "START", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subWorkId").value(subWorkId))
                .andExpect(jsonPath("$.data.transition").value("START"))
                .andExpect(jsonPath("$.data.previousWorkStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data.workStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.isSelfApproval").value(false))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        transition(subWorkId, "REQUEST_REVIEW", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workStatus").value("REVIEW"));
    }

    // 화면은 체크리스트가 미충족이면 버튼을 비활성해야 한다. 눌러도 서버가 409로 막는다
    @Test
    void approveCompleteWithUnfinishedChecklistReturns409() throws Exception {
        Long subWorkId = createSubWork();
        transition(subWorkId, "START", null).andExpect(status().isOk());
        transition(subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());

        transition(subWorkId, "APPROVE_COMPLETE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMPLETION_CRITERIA_UNMET"));
    }

    // 전이표에 없는 조합은 409다 (기획 상태에서 곧장 완료 승인)
    @Test
    void transitionOutsideTransitionTableReturns409() throws Exception {
        Long subWorkId = createSubWork();

        transition(subWorkId, "APPROVE_COMPLETE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED"));
    }

    // 반려 사유 누락은 400(VALIDATION_FAILED)이 아니라 422다 (VR-O06)
    @Test
    void rejectWithoutReasonReturns422() throws Exception {
        Long subWorkId = createSubWork();
        transition(subWorkId, "START", null).andExpect(status().isOk());
        transition(subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());

        transition(subWorkId, "REJECT", "  ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }

    // 기준 코드에 없는 전이 액션은 enum 역직렬화 단계에서 걸린다 (VL-09)
    @Test
    void unknownTransitionActionReturns400() throws Exception {
        Long subWorkId = createSubWork();

        transition(subWorkId, "CANCEL", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void transitionOnUnknownSubWorkReturns404() throws Exception {
        transition(999L, "START", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void transitionWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        post("/v1/sub-works/{subWorkId}/transitions", 1)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"transition\": \"START\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions transition(Long subWorkId, String action, String reason)
            throws Exception {
        String body =
                reason == null
                        ? "{\"transition\": \"%s\"}".formatted(action)
                        : "{\"transition\": \"%s\", \"reason\": \"%s\"}".formatted(action, reason);
        return mockMvc.perform(
                post("/v1/sub-works/{subWorkId}/transitions", subWorkId)
                        .header("Authorization", "Bearer any-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private Long createSubWork() throws Exception {
        String body =
                """
                {
                  "workId": %d,
                  "title": "부스 배치도 확정",
                  "subWorkTypeId": %d,
                  "ownerId": %d,
                  "dueAt": "2099-01-01T23:59:00+09:00",
                  "content": "박람회 부스 위치와 동선을 확정한다"
                }
                """
                        .formatted(parentWorkId, SUB_WORK_TYPE_ID, ownerId);

        String response =
                mockMvc.perform(authenticated(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.subWorkId", Long.class);
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
                            .subject(SPB_USER_ID.toString())
                            .claim("email", "actor@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
