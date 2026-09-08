package org.sscc.ssccopsserver.domain.share.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 공유 링크(ssccops#200 · ADR-0016) 통합 검증.
 *
 * 두 층을 한 클래스에서 본다 — 발급·폐기는 인증 경로이고 미리보기는 익명 경로인데, **그 둘이
 * 이어지는지가 이 기능의 전부**라서다(발급한 토큰이 익명으로 열리고 폐기하면 닫힌다).
 *
 * **미리보기 요청에는 Authorization 헤더가 없다.** permitAll이 실제로 걸려 있는지는 필터체인을
 * 통째로 태워 봐야만 확인되고, 토큰을 붙이면 SecurityConfig의 규칙이 사라져도 초록으로 남는다
 * (PublicEventControllerTest·PublicFormMetaControllerTest와 같은 이유).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class ShareLinkControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    private static final long SUB_WORK_TYPE_ID = 1L;

    private static final String CONTENT = "박람회 부스 위치와 동선을 확정한다";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private WorkService workService;

    private Long ownerId;
    private Long parentWorkId;

    @BeforeEach
    void setUp() {
        MemberEntity owner = saveMember(UUID.randomUUID(), "20200001", "김도현", "owner@sscc.org");
        ownerId = owner.getId();
        MemberEntity registrant = saveMember(AUTH_USER_ID, "20200002", "이서연", "actor@sscc.org");
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

    /* ── 발급 → 익명 미리보기 ──────────────────────────────── */

    /*
     * 발급한 토큰이 **토큰 없이** 열리고, 실리는 것은 제목·요약과 대상 좌표뿐이다.
     *
     * 상태·진행률·마감일 키가 없다는 것을 함께 못 박는다 — 그 값들이 실리면 카드가 굳은 뒤에도
     * 사실이 아닌 것을 말하게 되고(ssccops#194 제약 ②), 그 실수는 응답을 눈으로 보지 않으면
     * 드러나지 않는다.
     */
    @Test
    void issuedTokenIsReadableByAnonymousAndCarriesOnlyStableValues() throws Exception {
        Long subWorkId = createSubWork();
        String token = issueShareToken(subWorkId);

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trgtSeCd").value("SUB_WORK"))
                .andExpect(jsonPath("$.data.trgtId").value(subWorkId))
                .andExpect(jsonPath("$.data.title").value("부스 배치도 확정"))
                .andExpect(jsonPath("$.data.summary").value(CONTENT))
                // 시간에 따라 변하는 값은 계약상 없다 — 카드가 한 번 굳는다
                .andExpect(jsonPath("$.data.workSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.aprvSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.prgrsRt").doesNotExist())
                .andExpect(jsonPath("$.data.ddlnDt").doesNotExist())
                .andExpect(jsonPath("$.data.dlyYn").doesNotExist());
    }

    /*
     * 발급은 멱등이다. 만료가 없으므로(ADR-0016) 누를 때마다 새로 만들면 죽지 않는 링크가 쌓이고,
     * 화면이 그중 무엇을 보여줄지에 답이 없다.
     */
    @Test
    void issuingTwiceReturnsTheSameToken() throws Exception {
        Long subWorkId = createSubWork();

        assertThat(issueShareToken(subWorkId)).isEqualTo(issueShareToken(subWorkId));
    }

    /* ── 폐기 ──────────────────────────────────────────────── */

    /*
     * 폐기하면 그 링크로는 아무것도 열리지 않는다 — 만료를 두지 않기로 했으므로 이것이 링크를
     * 거두는 유일한 길이다.
     */
    @Test
    void revokedTokenIsNoLongerReadable() throws Exception {
        Long subWorkId = createSubWork();
        String token = issueShareToken(subWorkId);

        mockMvc.perform(
                        delete("/v1/sub-works/{subWorkId}/share", subWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * 폐기된 토큰과 없는 토큰은 **상태 코드도 오류 코드도 같다.** 나누면 어느 토큰이 한때
     * 존재했는지가 드러나 토큰을 무작위로 둔 이유가 절반 무효가 된다.
     */
    @Test
    void unknownTokenIsIndistinguishableFromRevokedOne() throws Exception {
        mockMvc.perform(get("/public/v1/share/{token}", "does-not-exist-at-all"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /* ── 공유 상태 조회 ────────────────────────────────────── */

    /*
     * 공유한 적이 없으면 404가 아니라 **data가 null인 200**이다 — '공유 중이 아니다'는 오류가
     * 아니라 정상적인 조회 결과다. 폐기 뒤에도 같은 상태로 돌아간다.
     */
    @Test
    void shareStateIsNullBeforeIssuingAndAfterRevoking() throws Exception {
        Long subWorkId = createSubWork();

        mockMvc.perform(
                        get("/v1/sub-works/{subWorkId}/share", subWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        issueShareToken(subWorkId);

        mockMvc.perform(
                        get("/v1/sub-works/{subWorkId}/share", subWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shrTkn").isNotEmpty());

        mockMvc.perform(
                        delete("/v1/sub-works/{subWorkId}/share", subWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/v1/sub-works/{subWorkId}/share", subWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /* ── 업무 (ssccops#306) ────────────────────────────────── */

    /*
     * 업무도 같은 두 층을 지난다 — 발급한 토큰이 익명으로 열리고 대상 좌표가 `WORK`로 온다.
     *
     * **요약이 조립된 문자열이라는 것을 여기서 못 박는다.** 업무에는 본문이 없어 유형과 기간으로
     * 만드는데(ssccops#251), 이 표본은 기간이 비어 있어 **유형만** 남아야 한다 — 등록 화면에서
     * 기간이 선택 입력이라 실제로 흔한 모양이고, 재료가 없을 때 구분자만 남으면 카드가 잘린
     * 것처럼 보인다. 조합 넷 전부는 `WorkSharePreviewProviderTest`가 본다.
     */
    @Test
    void issuedWorkTokenCarriesTheAssembledSummary() throws Exception {
        String token = issueWorkShareToken();

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trgtSeCd").value("WORK"))
                .andExpect(jsonPath("$.data.trgtId").value(parentWorkId))
                .andExpect(jsonPath("$.data.title").value("2026 동아리 박람회"))
                // 기간이 없으므로 유형만 — `행사 · ` 처럼 재료 없는 구분자가 남으면 안 된다
                .andExpect(jsonPath("$.data.summary").value("행사"))
                .andExpect(jsonPath("$.data.workSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.prgrsRt").doesNotExist());
    }

    // 하위 업무와 같은 이유로 멱등이다 — 만료가 없어 누를 때마다 발급하면 죽지 않는 링크가 쌓인다
    @Test
    void issuingWorkShareTwiceReturnsTheSameToken() throws Exception {
        assertThat(issueWorkShareToken()).isEqualTo(issueWorkShareToken());
    }

    @Test
    void revokedWorkTokenIsNoLongerReadable() throws Exception {
        String token = issueWorkShareToken();

        mockMvc.perform(
                        delete("/v1/works/{workId}/share", parentWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * **삭제된 운영 건은 없는 것으로 답한다.** 폐기하지 않은 살아 있는 토큰인데도 404인 것은
     * 미리보기 제공자가 대상을 찾지 못하기 때문이다 — 지운 업무의 제목이 링크로 계속 열리면
     * "지웠다"는 화면의 표시가 사실이 아니게 된다.
     */
    @Test
    void tokenOfADeletedWorkIsNoLongerReadable() throws Exception {
        String token = issueWorkShareToken();

        mockMvc.perform(
                        delete("/v1/works/{workId}", parentWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * 없는 업무에는 토큰이 발급되지 않는다. `shr_lnk`에 FK가 없어 DB가 막아 주지 않으므로
     * **대상 조회를 먼저 태우는 것이 유일한 방어**다.
     */
    @Test
    void issuingShareForAnUnknownWorkIs404() throws Exception {
        mockMvc.perform(
                        post("/v1/works/{workId}/share", 999_999L)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isNotFound());
    }

    /*
     * 공유한 적이 없으면 404가 아니라 data가 null인 200이다 — 하위 업무와 같은 판단이며,
     * 화면이 '공유하기'와 '공유 중지' 중 무엇을 그릴지 이 값으로 정한다.
     */
    @Test
    void workShareStateIsNullBeforeIssuing() throws Exception {
        mockMvc.perform(
                        get("/v1/works/{workId}/share", parentWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        issueWorkShareToken();

        mockMvc.perform(
                        get("/v1/works/{workId}/share", parentWorkId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shrTkn").isNotEmpty());
    }

    /* ── 발급은 익명이 아니다 ──────────────────────────────── */

    // 미리보기만 익명이다. 토큰을 만드는 것은 그 자원을 볼 수 있는 사람의 일이다
    @Test
    void issuingWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/v1/sub-works/{subWorkId}/share", 1L))
                .andExpect(status().isUnauthorized());
    }

    /* ── 표본 ───────────────────────────────────────────────── */

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

    private Long createSubWork() throws Exception {
        String body =
                """
                {
                  "workId": %d,
                  "title": "부스 배치도 확정",
                  "subWorkTypeId": %d,
                  "ownerId": %d,
                  "dueAt": "2099-01-01T23:59:00+09:00",
                  "content": "%s"
                }
                """
                        .formatted(parentWorkId, SUB_WORK_TYPE_ID, ownerId, CONTENT);

        String response =
                mockMvc.perform(
                                post("/v1/sub-works")
                                        .header("Authorization", "Bearer " + AUTH_USER_ID)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.subWorkId", Long.class);
    }

    private String issueWorkShareToken() throws Exception {
        String response =
                mockMvc.perform(
                                post("/v1/works/{workId}/share", parentWorkId)
                                        .header("Authorization", "Bearer " + AUTH_USER_ID))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.shrTkn", String.class);
    }

    private String issueShareToken(Long subWorkId) throws Exception {
        String response =
                mockMvc.perform(
                                post("/v1/sub-works/{subWorkId}/share", subWorkId)
                                        .header("Authorization", "Bearer " + AUTH_USER_ID))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.shrTkn", String.class);
    }
}
