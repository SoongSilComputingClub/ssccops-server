package org.sscc.ssccopsserver.domain.event.controller;

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
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 게시 전 행사의 공유 링크(ssccops#312 · ADR-0016) 통합 검증.
 *
 * 두 층이 이어지는지가 이 기능의 전부라 한 클래스에서 본다 — 발급·폐기는 EVENT_MANAGE를
 * 지나는 인증 경로이고 미리보기는 익명 경로다(`ShareLinkControllerTest`와 같은 구성).
 * **미리보기 요청에는 Authorization 헤더가 없다** — permitAll이 실제로 걸려 있는지는
 * 필터체인을 통째로 태워야만 확인되고, 토큰을 붙이면 규칙이 사라져도 초록으로 남는다.
 *
 * 행사가 앞의 세 대상과 갈리는 지점을 이 클래스가 지킨다: **발급은 DRAFT에서만 되고, 게시된
 * 뒤에도 이미 나간 링크는 살아 있으며, 보관하면 닫힌다.**
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class EventShareControllerTest {

    private static final String EVENTS = "/v1/events";

    private static final String CONTENT = "# 준비 중\n부스 위치와 동선을 이렇게 잡으려 한다.";

    private UUID managerToken;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        MemberEntity manager = saveMember(managerToken, "20260001", "행사운영자");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                manager,
                MemberRoleFixture.DIRECTOR);
    }

    /* ── 발급 → 익명 미리보기 ──────────────────────────────── */

    /*
     * 게시 전 행사의 토큰이 **토큰 없이** 열리고, 실리는 것은 제목·요약과 대상 좌표뿐이다.
     *
     * 요약은 본문을 자른 것이며 **일시·장소를 앞에 붙이지 않는다** — 게시 전 행사의 일시는
     * 아직 사람이 정하는 중인 값이라 #251·#252가 기간을 담은 근거("확정된 사실")가 여기서는
     * 성립하지 않는다. 접수 상태·잔여 정원 같은 변하는 값에 자리가 없다는 것도 함께 못 박는다 —
     * 카드는 한 번 굳는다(ssccops#194 제약 ②).
     */
    @Test
    void issuedTokenIsReadableByAnonymousAndCarriesOnlyStableValues() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "2026 신입생 오리엔테이션");
        String token = issueShareToken(eventId);

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trgtSeCd").value("EVENT"))
                .andExpect(jsonPath("$.data.trgtId").value(eventId))
                .andExpect(jsonPath("$.data.title").value("2026 신입생 오리엔테이션"))
                .andExpect(jsonPath("$.data.summary").value(CONTENT))
                // 변하는 값은 계약상 없다 — 메신저는 카드를 한 번 캐싱하면 갱신하지 않는다
                .andExpect(jsonPath("$.data.eventSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.receiptStatus").doesNotExist())
                .andExpect(jsonPath("$.data.confirmedCount").doesNotExist())
                .andExpect(jsonPath("$.data.ptcpLmtCnt").doesNotExist())
                .andExpect(jsonPath("$.data.eventBgngDt").doesNotExist())
                .andExpect(jsonPath("$.data.plcNm").doesNotExist())
                // 이미지는 이 이슈에서 열지 않는다 — 게시 전 이미지는 별개의 보안 결정이다
                .andExpect(jsonPath("$.data.thmbUrlAddr").doesNotExist());
    }

    /*
     * 본문이 상한(500자)을 넘으면 잘린다. **자르는 이유는 표시가 아니라 새는 양이다** — 행사
     * 본문은 10만 자까지 갈 수 있어(D12) 그대로 흘리면 카드 두 줄을 위해 원고 한 편이 익명
     * 경로로 나간다.
     */
    @Test
    void longContentIsTruncatedBeforeItLeavesThroughTheAnonymousPath() throws Exception {
        Long eventId = createEvent("SEMINAR", "본문이 긴 행사", "가".repeat(1_000));
        String token = issueShareToken(eventId);

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("가".repeat(500)));
    }

    /*
     * 본문이 공백뿐이면 요약은 null이다 — **서버가 대체 문구를 만들지 않는다.** 채워 버리면
     * "본문이 없다"와 "서버가 그 문구를 줬다"를 웹이 구별할 수 없다.
     */
    @Test
    void blankContentYieldsNoSummaryInsteadOfAServerMadeOne() throws Exception {
        Long eventId = createEvent("SEMINAR", "본문이 빈 행사", "   ");
        String token = issueShareToken(eventId);

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("본문이 빈 행사"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());
    }

    // 만료가 없으므로(ADR-0016) 누를 때마다 발급하면 죽지 않는 링크가 쌓인다 — 멱등이다
    @Test
    void issuingTwiceReturnsTheSameToken() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "멱등 표본");

        assertThat(issueShareToken(eventId)).isEqualTo(issueShareToken(eventId));
    }

    /* ── 게시 상태가 가르는 것 ─────────────────────────────── */

    /*
     * **게시된 행사에는 발급하지 않는다.** 이미 `/events/{eventId}`로 익명이 여는 주소가 있어
     * 토큰이 더하는 것은 폐기 기능뿐인데, 그 폐기가 원본 공개 URL을 막지 못한다 — 지키지
     * 못하는 것을 지킨다고 말하는 버튼이 된다.
     *
     * 400이 아니라 409인 것은 대상의 현재 상태가 문제라서다. 게시를 철회하면 같은 요청이
     * 통과한다는 것을 이어서 확인한다 — 거절이 영구적인 금지가 아니라는 것이 그 판단의 근거다.
     */
    @Test
    void issuingForAPublishedEventIsRejectedButPassesAgainAfterRetracting() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "게시된 행사");
        changeStatus(eventId, "PUBLISH").andExpect(status().isOk());

        mockMvc.perform(authorized(post(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_SHARE_NOT_DRAFT"));

        changeStatus(eventId, "RETRACT").andExpect(status().isOk());

        mockMvc.perform(authorized(post(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shrTkn").isNotEmpty());
    }

    /*
     * **보관된 행사에도 발급하지 않는다.** 삭제가 없어진 뒤로(ADR-0014) 보관은 잘못 만든
     * 행사를 치우는 유일한 길이라, 치운 것을 익명에게 다시 여는 것은 새로 만드는 노출이다.
     */
    @Test
    void issuingForAnArchivedEventIsRejected() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "보관된 행사");
        changeStatus(eventId, "ARCHIVE").andExpect(status().isOk());

        mockMvc.perform(authorized(post(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_SHARE_NOT_DRAFT"));
    }

    /*
     * **게시 전에 나눈 링크는 게시된 뒤에도 열린다.** 게시는 이 행사가 나아가는 정상 경로이므로
     * 그 순간 카드가 깨지면 볼 수 있게 된 시점에 이미 나간 링크가 죽는다.
     *
     * 발급을 막는 것과 어긋나지 않는다 — 막는 쪽은 **새 노출을 만드는 일**이고 이쪽은 이미
     * 나간 링크를 유지하는 일이다.
     */
    @Test
    void tokenIssuedBeforePublishingKeepsWorkingAfterwards() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "게시될 행사");
        String token = issueShareToken(eventId);

        changeStatus(eventId, "PUBLISH").andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("게시될 행사"));
    }

    /*
     * **보관하면 살아 있는 토큰도 닫힌다.** 폐기하지 않았는데도 404인 것은 미리보기 제공자가
     * 대상을 찾지 못하기 때문이다 — 공개 상세가 보관된 행사를 404로 답하는데 카드만 계속
     * 열리면 익명에게 답하는 두 층이 서로 다른 말을 한다.
     */
    @Test
    void archivingClosesAnAlreadyIssuedToken() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "보관될 행사");
        String token = issueShareToken(eventId);

        changeStatus(eventId, "ARCHIVE").andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 폐기 · 상태 조회 ──────────────────────────────────── */

    // 만료를 두지 않기로 했으므로(ADR-0016) 폐기가 링크를 거두는 유일한 길이다
    @Test
    void revokedTokenIsNoLongerReadable() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "폐기할 행사");
        String token = issueShareToken(eventId);

        mockMvc.perform(authorized(delete(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * **게시된 뒤에도 조회와 폐기는 열려 있다.** 발급만 막으므로, 게시 전에 낸 링크를 화면이
     * 보고 거둘 수 있어야 한다 — 보이지 않으면 폐기할 수단이 없어진다.
     */
    @Test
    void publishedEventStillShowsAndRevokesItsExistingLink() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "게시 뒤 거두는 행사");
        String token = issueShareToken(eventId);
        changeStatus(eventId, "PUBLISH").andExpect(status().isOk());

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shrTkn").value(token));

        mockMvc.perform(authorized(delete(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token)).andExpect(status().isNotFound());
    }

    /*
     * 공유한 적이 없으면 404가 아니라 **data가 null인 200**이다 — '공유 중이 아니다'는 오류가
     * 아니라 정상적인 조회 결과이고, 화면은 이 값으로 '공유하기'와 '공유 중지' 중 무엇을 그릴지
     * 정한다. 폐기 뒤에도 같은 상태로 돌아간다.
     */
    @Test
    void shareStateIsNullBeforeIssuingAndAfterRevoking() throws Exception {
        Long eventId = createDraftEvent("SEMINAR", "상태를 보는 행사");

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        issueShareToken(eventId);

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shrTkn").isNotEmpty());

        mockMvc.perform(authorized(delete(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/share")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /* ── 404를 먼저 끊는다 ─────────────────────────────────── */

    /*
     * 없는 행사에는 토큰이 발급되지 않는다. `shr_lnk`에 FK가 없어 DB가 막아 주지 않으므로
     * **대상 조회를 먼저 태우는 것이 유일한 방어**다. 세 경로가 모두 같은 404여야 한다.
     */
    @Test
    void shareEndpointsOfAnUnknownEventAre404() throws Exception {
        mockMvc.perform(authorized(post(EVENTS + "/999999/share")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mockMvc.perform(authorized(get(EVENTS + "/999999/share")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mockMvc.perform(authorized(delete(EVENTS + "/999999/share")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    // 미리보기만 익명이다. 토큰을 만드는 것은 그 행사를 볼 수 있는 사람의 일이다(ADR-0016)
    @Test
    void issuingWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post(EVENTS + "/1/share")).andExpect(status().isUnauthorized());
    }

    /* ── 표본 ───────────────────────────────────────────────── */

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }

    private Long createDraftEvent(String classificationCode, String title) throws Exception {
        return createEvent(classificationCode, title, CONTENT);
    }

    private Long createEvent(String classificationCode, String title, String content)
            throws Exception {
        String body =
                """
                {
                  "eventClsfCd": "%s",
                  "eventTtl": "%s",
                  "mtxtCn": "%s",
                  "eventBgngDt": "2026-09-15T10:00:00+09:00",
                  "eventEndDt": "2026-09-15T18:00:00+09:00",
                  "plcNm": "정보과학관 21203",
                  "ptcpLmtCnt": 40
                }
                """
                        .formatted(classificationCode, title, content.replace("\n", "\\n").strip());
        String response =
                mockMvc.perform(authorized(post(EVENTS)).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.eventId", Long.class);
    }

    private String issueShareToken(Long eventId) throws Exception {
        String response =
                mockMvc.perform(authorized(post(EVENTS + "/" + eventId + "/share")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.shrTkn", String.class);
    }

    private ResultActions changeStatus(Long eventId, String action) throws Exception {
        return mockMvc.perform(
                authorized(post(EVENTS + "/" + eventId + "/status"))
                        .content("{\"action\": \"" + action + "\"}"));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
