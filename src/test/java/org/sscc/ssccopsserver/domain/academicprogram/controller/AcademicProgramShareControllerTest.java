package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 학술 프로그램·세션 공유 (ssccops#311 · ADR-0016) 통합 검증.
 *
 * 두 층이 이어지는지를 본다 — 발급·폐기는 인증 경로이고 미리보기는 익명 경로인데, **발급한
 * 토큰이 익명으로 열리고 폐기하면 닫히는 것**이 이 기능의 전부다.
 *
 * **미리보기 요청에는 Authorization 헤더가 없다.** permitAll이 실제로 걸려 있는지는 필터체인을
 * 통째로 태워 봐야만 확인되고, 토큰을 붙이면 SecurityConfig의 규칙이 사라져도 초록으로 남는다
 * (ShareLinkControllerTest와 같은 이유).
 *
 * 이 클래스가 `ShareLinkControllerTest`에 얹히지 않은 것은 그쪽이 운영(operation) 도메인의
 * 픽스처 위에 서 있고, 학술 픽스처는 event·form·회차까지 함께 세워야 하기 때문이다 —
 * 대상이 늘 때마다 한 클래스가 모든 도메인의 픽스처를 지고 가면 그 클래스가 곧 병목이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramShareControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";
    private static final String PREVIEW = "/public/v1/share/{token}";

    private static final String GOAL = "목표";
    private static final String NOTICE = "다음 주까지 3장 예제 풀어 오기";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private SessionRepository sessionRepository;

    private UUID leaderToken;
    private AcademicProgramEntity academicProgram;
    private SessionEntity session;

    @BeforeEach
    void setUp() {
        leaderToken = UUID.randomUUID();
        MemberEntity leader = saveMember(leaderToken, "20260401", "스터디장");
        academicProgram =
                AcademicProgramFixture.save(
                        eventRepository,
                        eventClassificationRepository,
                        academicProgramRepository,
                        academicProgramTypeRepository,
                        curriculumItemRepository,
                        formRepository,
                        formResponseHistoryRepository,
                        "STUDY",
                        "알고리즘 스터디",
                        leader,
                        List.of("재귀와 동적계획법"));
        CurriculumItemEntity item =
                curriculumItemRepository
                        .findByAcademicProgramIdOrderBySeqnoAsc(academicProgram.getId())
                        .get(0);
        session =
                sessionRepository.save(
                        SessionEntity.submit(
                                item, LocalDate.of(2026, 9, 15), "3회차 진행 내용", NOTICE, leader));
    }

    /* ── 프로그램 ──────────────────────────────────────────── */

    /*
     * 발급한 토큰이 **토큰 없이** 열리고, 실리는 것은 제목·요약과 대상 좌표뿐이다.
     *
     * 제목이 event의 것이고(acdm_actv에 제목 컬럼이 없다) 요약이 `goal_cn` 그대로라는 것을
     * 여기서 못 박는다. **모집 상태·정원 키가 없다는 것도 함께 본다** — 그 값들이 실리면 카드가
     * 굳은 뒤에도 사실이 아닌 것을 말하게 되고(ssccops#194 제약 ②), 그 실수는 응답을 눈으로
     * 보지 않으면 드러나지 않는다.
     */
    @Test
    void issuedProgramTokenIsReadableByAnonymousAndCarriesOnlyStableValues() throws Exception {
        String token = issueProgramShareToken();

        mockMvc.perform(get(PREVIEW, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trgtSeCd").value("ACADEMIC_PROGRAM"))
                .andExpect(jsonPath("$.data.trgtId").value(academicProgram.getId()))
                .andExpect(jsonPath("$.data.title").value("알고리즘 스터디"))
                .andExpect(jsonPath("$.data.summary").value(GOAL))
                // 시간에 따라 변하는 값은 계약상 없다 — 카드는 한 번 굳는다
                .andExpect(jsonPath("$.data.acdmActvSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.formReceiptStatus").doesNotExist())
                .andExpect(jsonPath("$.data.pscpMaxCnt").doesNotExist())
                .andExpect(jsonPath("$.data.atndRt").doesNotExist());
    }

    // 발급은 멱등이다 — 만료가 없어(ADR-0016) 누를 때마다 발급하면 죽지 않는 링크가 쌓인다
    @Test
    void issuingProgramShareTwiceReturnsTheSameToken() throws Exception {
        assertThat(issueProgramShareToken()).isEqualTo(issueProgramShareToken());
    }

    @Test
    void revokedProgramTokenIsNoLongerReadable() throws Exception {
        String token = issueProgramShareToken();

        mockMvc.perform(authorized(delete(programShare()))).andExpect(status().isOk());

        mockMvc.perform(get(PREVIEW, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * 공유한 적이 없으면 404가 아니라 **data가 null인 200**이다 — '공유 중이 아니다'는 오류가
     * 아니라 정상적인 조회 결과이며, 화면이 '공유하기'와 '공유 중지' 중 무엇을 그릴지 정한다.
     */
    @Test
    void programShareStateIsNullBeforeIssuing() throws Exception {
        mockMvc.perform(authorized(get(programShare())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        issueProgramShareToken();

        mockMvc.perform(authorized(get(programShare())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shrTkn").isNotEmpty());
    }

    /* ── 세션 ──────────────────────────────────────────────── */

    /*
     * **프로그램과 별개 대상이다.** 대상 좌표가 ACADEMIC_SESSION으로 오고 제목은 계획의 주제,
     * 요약은 실시일 + 공지다 — 실시일은 이미 일어난 일의 날짜라 담을 수 있다(ssccops#251·#252와
     * 같은 판단).
     */
    @Test
    void issuedSessionTokenCarriesTheCurriculumTitleAndTheRealDate() throws Exception {
        String token = issueSessionShareToken();

        mockMvc.perform(get(PREVIEW, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trgtSeCd").value("ACADEMIC_SESSION"))
                .andExpect(jsonPath("$.data.trgtId").value(session.getId()))
                .andExpect(jsonPath("$.data.title").value("재귀와 동적계획법"))
                .andExpect(jsonPath("$.data.summary").value("2026-09-15 · " + NOTICE))
                // 승인 상태·출석은 조회 시점마다 달라진다 — 카드에 실을 것이 아니다
                .andExpect(jsonPath("$.data.sttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.attendances").doesNotExist());
    }

    @Test
    void issuingSessionShareTwiceReturnsTheSameToken() throws Exception {
        assertThat(issueSessionShareToken()).isEqualTo(issueSessionShareToken());
    }

    @Test
    void revokedSessionTokenIsNoLongerReadable() throws Exception {
        String token = issueSessionShareToken();

        mockMvc.perform(authorized(delete(sessionShare()))).andExpect(status().isOk());

        mockMvc.perform(get(PREVIEW, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * **두 대상이 서로를 덮지 않는다.** 프로그램과 세션은 별개 대상이라 프로그램 공유를 중지해도
     * 세션 링크는 그대로 살아 있다 — 뿌리는 단위가 다른데 한쪽을 거두는 것이 다른 쪽까지
     * 거두면, 두 값으로 나눈 이유가 무효가 된다.
     */
    @Test
    void revokingTheProgramShareLeavesTheSessionShareAlive() throws Exception {
        String sessionToken = issueSessionShareToken();
        issueProgramShareToken();

        mockMvc.perform(authorized(delete(programShare()))).andExpect(status().isOk());

        mockMvc.perform(get(PREVIEW, sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trgtSeCd").value("ACADEMIC_SESSION"));
    }

    @Test
    void sessionShareStateIsNullBeforeIssuing() throws Exception {
        mockMvc.perform(authorized(get(sessionShare())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /* ── 404를 먼저 끊는다 ─────────────────────────────────── */

    /*
     * 없는 활동에는 토큰이 발급되지 않는다. `shr_lnk`에 FK가 없어 DB가 막아 주지 않으므로
     * **대상 조회를 먼저 태우는 것이 유일한 방어**다.
     */
    @Test
    void issuingShareForAnUnknownProgramIs404() throws Exception {
        mockMvc.perform(authorized(post(PROGRAMS + "/999999/share")))
                .andExpect(status().isNotFound());
    }

    /*
     * 다른 활동의 회차 식별자로 부르면 404다 — 회차 상세 조회가 경로의 활동으로 좁혀 읽는
     * 것과 같은 판정이고, 없는 회차와 남의 활동 회차는 같은 답이다.
     */
    @Test
    void issuingShareForASessionOfAnotherProgramIs404() throws Exception {
        mockMvc.perform(
                        authorized(
                                post(PROGRAMS + "/999999/sessions/" + session.getId() + "/share")))
                .andExpect(status().isNotFound());
    }

    /* ── 발급은 익명이 아니다 ──────────────────────────────── */

    // 미리보기만 익명이다. 토큰을 만드는 것은 그 자원을 볼 수 있는 사람의 일이다
    @Test
    void issuingWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post(programShare())).andExpect(status().isUnauthorized());
    }

    /* ── 표본 ───────────────────────────────────────────────── */

    private String programShare() {
        return PROGRAMS + "/" + academicProgram.getId() + "/share";
    }

    private String sessionShare() {
        return PROGRAMS + "/" + academicProgram.getId() + "/sessions/" + session.getId() + "/share";
    }

    private String issueProgramShareToken() throws Exception {
        return issueShareToken(programShare());
    }

    private String issueSessionShareToken() throws Exception {
        return issueShareToken(sessionShare());
    }

    private String issueShareToken(String path) throws Exception {
        String response =
                mockMvc.perform(authorized(post(path)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.shrTkn", String.class);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + leaderToken);
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
}
