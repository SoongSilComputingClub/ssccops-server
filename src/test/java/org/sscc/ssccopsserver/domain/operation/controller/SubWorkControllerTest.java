package org.sscc.ssccopsserver.domain.operation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

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

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    // data.sql이 넣는 유형. 1=예산지출(승인 필요)
    private static final long SUB_WORK_TYPE_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private WorkService workService;

    private Long ownerId;
    private Long registrantId;
    private Long parentWorkId;

    @BeforeEach
    void setUp() {
        MemberEntity owner = saveMember(UUID.randomUUID(), "20200001", "김도현", "owner@sscc.org");
        ownerId = owner.getId();
        // 등록자는 토큰의 sub(AUTH_USER_ID)와 연결된 회원이며 담당자와 다른 사람이다
        MemberEntity registrant = saveMember(AUTH_USER_ID, "20200002", "이서연", "actor@sscc.org");
        registrantId = registrant.getId();
        /*
         * 승인·반려는 유형이 지정한 승인자만 할 수 있다 (#47). 이 클래스가 쓰는 유형
         * '예산지출'의 승인자가 총무라, 토큰 주체에게 그 역할을 붙여 승인 경로를 열어 둔다.
         */
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                registrant,
                MemberRoleFixture.TREASURER);
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
                /*
                 * 승인·반려 버튼을 그릴지는 서버가 답한다 (#58). 토큰 주체가 이 유형(예산지출)의
                 * 승인자인 총무라 둘 다 true다 — 상태·정족수는 여기 섞지 않는다.
                 */
                .andExpect(jsonPath("$.data.canApprove").value(true))
                .andExpect(jsonPath("$.data.canReject").value(true))
                // 단독 유형이라 진행바를 그리지 않는다. 나머지 정족수 값은 0이 아니라 NULL이다
                .andExpect(jsonPath("$.data.quorum.needed").value(false))
                .andExpect(jsonPath("$.data.quorum.requiredCount").doesNotExist())
                .andExpect(jsonPath("$.data.myVote").doesNotExist())
                // 반려된 적이 없으면 보여줄 사유도 없다 (값은 NULL이다)
                .andExpect(jsonPath("$.data.latestRejection").doesNotExist())
                // 기수는 프론트 디자인에서 빠져 응답에도 두지 않는다
                .andExpect(jsonPath("$.data.owner.generationNumber").doesNotExist())
                // 목록 봉투는 단건 응답에 실리지 않는다 (AP-11)
                .andExpect(jsonPath("$.page").doesNotExist());
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
     * 기본 정보 수정(OPS-030). 값이 실제로 바뀌는지·응답이 조회와 같은 승인 판단 근거를
     * 싣는지는 서비스 테스트가 다루므로 여기서는 응답 형태·상태 코드만 확인한다.
     */
    @Test
    void updateSubWorkReturns200WithUpdatedDetail() throws Exception {
        Long subWorkId = createSubWork();
        String body =
                """
                {
                  "title": "부스 배치도 확정 (수정)",
                  "ownerId": %d,
                  "dueAt": "2099-02-01T23:59:00+09:00",
                  "priority": "HIGH",
                  "content": "동선을 다시 확정한다",
                  "completionCriteria": "동선도 확정 및 배치 승인",
                  "externalLink": "https://docs.example.com/updated"
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(
                        patch("/v1/sub-works/{subWorkId}", subWorkId)
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subWorkId").value(subWorkId))
                .andExpect(jsonPath("$.data.title").value("부스 배치도 확정 (수정)"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.content").value("동선을 다시 확정한다"))
                .andExpect(jsonPath("$.data.completionCriteria").value("동선도 확정 및 배치 승인"))
                .andExpect(
                        jsonPath("$.data.externalLink").value("https://docs.example.com/updated"))
                // 상태·유형·상위 업무는 요청 본문에 필드가 없어 바뀌지 않는다 (POL-003)
                .andExpect(jsonPath("$.data.workStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data.subWorkTypeName").value("예산지출"))
                .andExpect(jsonPath("$.data.workId").value(parentWorkId));
    }

    @Test
    void updateSubWorkWithUnknownOwnerReturnsValidationFailed() throws Exception {
        Long subWorkId = createSubWork();
        String body =
                """
                {
                  "title": "담당자 없는 수정",
                  "ownerId": %d
                }
                """
                        .formatted(ownerId + 999);

        mockMvc.perform(
                        patch("/v1/sub-works/{subWorkId}", subWorkId)
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateUnknownSubWorkReturns404() throws Exception {
        String body =
                """
                {
                  "title": "없는 하위 업무 수정",
                  "ownerId": %d
                }
                """
                        .formatted(ownerId);

        mockMvc.perform(
                        patch("/v1/sub-works/{subWorkId}", 999_999L)
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updateSubWorkWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        patch("/v1/sub-works/{subWorkId}", 1L)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // 목록 화면(하위 업무)이 진입 시 호출하는 조회. 목록 응답은 data 배열 + page 봉투다 (AP-11)
    @Test
    void searchSubWorksReturns200WithListEnvelope() throws Exception {
        Long firstId = createSubWork();
        createSubWork();

        searchSubWorks()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].subWorkId").value(firstId))
                .andExpect(jsonPath("$.data[0].title").value("부스 배치도 확정"))
                .andExpect(jsonPath("$.data[0].work.workId").value(parentWorkId))
                .andExpect(jsonPath("$.data[0].work.title").value("2026 동아리 박람회"))
                .andExpect(jsonPath("$.data[0].subWorkTypeName").value("예산지출"))
                .andExpect(jsonPath("$.data[0].owner.memberId").value(ownerId))
                .andExpect(jsonPath("$.data[0].workStatus").value("PLANNING"))
                .andExpect(jsonPath("$.data[0].approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[0].progressRate").isNumber())
                .andExpect(jsonPath("$.data[0].isDelayed").value(false))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.sort").value("dueAt"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.totalCount").value(2))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    // 필터를 걸면 걸러진 건수만 줄고 전체 건수는 그대로다 — 화면의 'N건 · 전체 M건'
    @Test
    void searchSubWorksAppliesFilterAndKeepsOverallCount() throws Exception {
        createSubWork();

        searchSubWorks("workStatus", "DONE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalCount").value(0))
                .andExpect(jsonPath("$.page.overallCount").value(1));
    }

    // 커서를 그대로 되돌려 보내면 다음 페이지가 이어진다
    @Test
    void searchSubWorksFollowsCursorToNextPage() throws Exception {
        createSubWork();
        Long secondId = createSubWork();

        String firstPage =
                searchSubWorks("size", "1")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(1))
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andExpect(jsonPath("$.page.nextCursor").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(firstPage).read("$.page.nextCursor", String.class);

        searchSubWorks("size", "1", "cursor", cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subWorkId").value(secondId))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist());
    }

    /*
     * 화면 '승인대기' 칩. 승인 상태를 여러 개 넘겨야 대기와 재승인필요를 함께 볼 수 있다.
     * 반복 파라미터(approvalStatus=A&approvalStatus=B)와 쉼표 표기 둘 다 같게 받는다.
     */
    @Test
    void searchSubWorksAcceptsMultipleApprovalStatuses() throws Exception {
        Long subWorkId = createSubWork();
        transition(subWorkId, "START", null).andExpect(status().isOk());
        transition(subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());

        searchSubWorks(
                        "workStatus", "REVIEW",
                        "approvalStatus", "PENDING",
                        "approvalStatus", "REAPPROVAL_REQUIRED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].subWorkId").value(subWorkId))
                .andExpect(jsonPath("$.data[0].approvalStatus").value("PENDING"));

        searchSubWorks("workStatus", "REVIEW", "approvalStatus", "PENDING,REAPPROVAL_REQUIRED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // 기준 코드 밖의 값은 형식 오류와 구분해 돌려준다 (03_오류_코드)
    @Test
    void searchSubWorksWithUnknownStatusReturns400() throws Exception {
        searchSubWorks("workStatus", "진행")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void searchSubWorksWithUnknownSortReturns400() throws Exception {
        searchSubWorks("sort", "dueDate")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    // AP-13 — size 최대 100
    @Test
    void searchSubWorksOverMaxSizeReturns400() throws Exception {
        searchSubWorks("size", "101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchSubWorksWithMalformedCursorReturns400() throws Exception {
        searchSubWorks("cursor", "!!not-a-cursor!!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchSubWorksWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/sub-works")).andExpect(status().isUnauthorized());
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

    // 상세 화면(OPS-SCR-002) 체크박스가 부르는 경로. 응답의 요약이 '1/4 완료' 표기가 된다
    @Test
    void updateChecklistItemReturns200WithSummary() throws Exception {
        Long subWorkId = createSubWork();
        Long itemId = firstChecklistItemId(subWorkId);

        updateChecklistItem(subWorkId, itemId, "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subWorkId").value(subWorkId))
                .andExpect(jsonPath("$.data.item.checklistItemId").value(itemId))
                .andExpect(jsonPath("$.data.item.isCompleted").value(true))
                .andExpect(jsonPath("$.data.item.sortOrder").value(1))
                .andExpect(jsonPath("$.data.checklistSummary.completedCount").value(1))
                .andExpect(jsonPath("$.data.checklistSummary.totalCount").value(4));

        updateChecklistItem(subWorkId, itemId, "false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.isCompleted").value(false))
                .andExpect(jsonPath("$.data.checklistSummary.completedCount").value(0));
    }

    /*
     * isCompleted 누락은 400이다. 원시 boolean으로 받으면 false(해제)로 역직렬화돼
     * 의도하지 않은 체크 해제가 되므로 요청 DTO가 Boolean + @NotNull이다.
     */
    @Test
    void updateChecklistItemWithoutIsCompletedReturns400() throws Exception {
        Long subWorkId = createSubWork();
        Long itemId = firstChecklistItemId(subWorkId);

        mockMvc.perform(
                        patch("/v1/sub-works/{subWorkId}/checklist/{itemId}", subWorkId, itemId)
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 다른 하위 업무의 항목은 존재를 알려주지 않고 404다 (403이 아니다)
    @Test
    void updateChecklistItemOfAnotherSubWorkReturns404() throws Exception {
        Long subWorkId = createSubWork();
        Long otherItemId = firstChecklistItemId(createSubWork());

        updateChecklistItem(subWorkId, otherItemId, "true")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updateChecklistItemOnUnknownSubWorkReturns404() throws Exception {
        updateChecklistItem(999L, 1L, "true")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 완료된 건의 체크는 되돌릴 수 없다 (409). 완료 승인까지 화면 흐름을 그대로 태워 확인한다
    @Test
    void updateChecklistItemOnCompletedSubWorkReturns409() throws Exception {
        Long subWorkId = createSubWork();
        transition(subWorkId, "START", null).andExpect(status().isOk());
        transition(subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());
        for (Long itemId : checklistItemIds(subWorkId)) {
            updateChecklistItem(subWorkId, itemId, "true").andExpect(status().isOk());
        }
        // 체크리스트를 다 채웠으므로 이번에는 409가 아니라 통과한다 (이 이슈의 존재 이유)
        transition(subWorkId, "APPROVE_COMPLETE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workStatus").value("DONE"));

        updateChecklistItem(subWorkId, firstChecklistItemId(subWorkId), "false")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED"));
    }

    @Test
    void updateChecklistItemWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        patch("/v1/sub-works/{subWorkId}/checklist/{itemId}", 1, 1)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"isCompleted\": true}"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * 쿼리 파라미터는 이름·값을 짝으로 받아 붙인다. 목록 테스트마다 파라미터 조합이 하나씩만
     * 달라서, 조합마다 헬퍼를 두는 것보다 이 편이 어떤 조건을 보는 테스트인지 잘 드러난다.
     */
    private ResultActions searchSubWorks(String... nameAndValuePairs) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/v1/sub-works").header("Authorization", "Bearer any-token");
        for (int index = 0; index < nameAndValuePairs.length; index += 2) {
            request = request.param(nameAndValuePairs[index], nameAndValuePairs[index + 1]);
        }
        return mockMvc.perform(request);
    }

    private ResultActions updateChecklistItem(Long subWorkId, Long itemId, String isCompleted)
            throws Exception {
        return mockMvc.perform(
                patch("/v1/sub-works/{subWorkId}/checklist/{itemId}", subWorkId, itemId)
                        .header("Authorization", "Bearer any-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isCompleted\": %s}".formatted(isCompleted)));
    }

    private List<Long> checklistItemIds(Long subWorkId) throws Exception {
        String response =
                mockMvc.perform(
                                get("/v1/sub-works/{subWorkId}", subWorkId)
                                        .header("Authorization", "Bearer any-token"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        // JsonPath는 정수를 Integer로 읽으므로 Long으로 직접 좁힌다
        List<Number> itemIds = JsonPath.parse(response).read("$.data.checklist[*].checklistItemId");
        return itemIds.stream().map(Number::longValue).toList();
    }

    private Long firstChecklistItemId(Long subWorkId) throws Exception {
        return checklistItemIds(subWorkId).get(0);
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

    // ------------------------------------------------------------------ 삭제 (#125)

    @Test
    void deleteSubWorkReturns200() throws Exception {
        Long subWorkId = createSubWork();

        mockMvc.perform(
                        delete("/v1/sub-works/{subWorkId}", subWorkId)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(
                        get("/v1/sub-works/{subWorkId}", subWorkId)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deleteUnknownSubWorkReturns404() throws Exception {
        mockMvc.perform(
                        delete("/v1/sub-works/{subWorkId}", 999_999L)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deleteAlreadyDeletedSubWorkReturns409() throws Exception {
        Long subWorkId = createSubWork();
        mockMvc.perform(
                        delete("/v1/sub-works/{subWorkId}", subWorkId)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        delete("/v1/sub-works/{subWorkId}", subWorkId)
                                .header("Authorization", "Bearer any-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DELETED"));
    }

    @Test
    void deleteSubWorkWithoutTokenReturns401() throws Exception {
        mockMvc.perform(delete("/v1/sub-works/{subWorkId}", 1L))
                .andExpect(status().isUnauthorized());
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
                            .subject(AUTH_USER_ID.toString())
                            .claim("email", "actor@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
