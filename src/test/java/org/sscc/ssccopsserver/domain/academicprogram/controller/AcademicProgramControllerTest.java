package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
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
 * 학술 활동(스터디/프로젝트) 조회 API (#131) + 커리큘럼 계획 조회 (#134). 등록(POST) 경로는 없다 — 2026-08-23 설계 변경으로
 * 기획안 접수가 폼 도메인 이관(ssccops#148)으로 대체됐고, 이 이슈에는 조회만 남았다. 테스트
 * 데이터는 HTTP로 만들지 않고 AcademicProgramFixture로 직접 심는다.
 *
 * 요청 주체를 요청마다 바꿔야 해서(제출자 · 다른 회원) AcademicProgramTypeControllerTest와
 * 같은 JwtDecoder 스텁을 쓴다. typeCd는 S0(#130)이 시드하는 STUDY/PROJECT를 그대로 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FormRepository formRepository;
    @PersistenceContext private EntityManager entityManager;

    private UUID proposerToken;
    private MemberEntity proposer;
    private UUID otherToken;
    private MemberEntity other;

    @BeforeEach
    void setUp() {
        proposerToken = UUID.randomUUID();
        proposer = saveMember(proposerToken, "20260401", "제출자");

        otherToken = UUID.randomUUID();
        other = saveMember(otherToken, "20260402", "다른회원");
    }

    // ------------------------------------------------------------------ 단건 조회

    // 픽스처는 모집 폼을 event에 연결하지 않는다(승인 이관 #148을 거치지 않은 활동) — 그 경우
    // formId·formReceiptStatus는 null로 안전하게 내려간다(#186)
    @Test
    void getAcademicProgramReturns200WithDetail() throws Exception {
        AcademicProgramEntity academicProgram =
                createAcademicProgram("STUDY", "조회용 스터디", "OT", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", academicProgram.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramId").value(academicProgram.getId()))
                .andExpect(jsonPath("$.data.title").value("조회용 스터디"))
                .andExpect(jsonPath("$.data.typeCd").value("STUDY"))
                .andExpect(jsonPath("$.data.typeNm").value("스터디"))
                .andExpect(jsonPath("$.data.sttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.prpsrMbrId").value(proposer.getId()))
                .andExpect(jsonPath("$.data.prpsrMbrNm").value("제출자"))
                .andExpect(jsonPath("$.data.leadrMbrId").value(proposer.getId()))
                .andExpect(jsonPath("$.data.leadrMbrNm").value("제출자"))
                .andExpect(jsonPath("$.data.formId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.progress.totalSessionCount").value(0))
                .andExpect(jsonPath("$.data.progress.approvedSessionCount").value(0))
                .andExpect(jsonPath("$.data.curriculumItemCount").value(2))
                .andExpect(jsonPath("$.data.isProposer").value(true))
                .andExpect(jsonPath("$.data.isLeader").value(true));
    }

    /*
     * 승인 이관(#148)이 만든 활동은 event에 모집 폼이 연결돼 있다 — 상세 응답이 그 폼의 id와
     * 파생 접수 상태를 실어야 웹이 신청서 편집 링크(/forms/{formId}/edit)를 그릴 수 있다(#186).
     * DRAFT 폼이므로 formReceiptStatus는 "DRAFT"이고, 문자열 형식은 전이 응답의 것과 같다.
     */
    @Test
    void getAcademicProgramExposesLinkedFormIdAndReceiptStatus() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "폼 연결 스터디", "1주차");
        FormEntity recruitmentForm = linkRecruitmentForm(academicProgram);

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", academicProgram.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").value(recruitmentForm.getId()))
                .andExpect(jsonPath("$.data.formReceiptStatus").value("DRAFT"));
    }

    // 제출자가 아닌 회원이 조회하면 isProposer가 false다 — 서버가 본인 여부를 판정한다(설계 결정 #4)
    @Test
    void getAcademicProgramAsOtherMemberShowsIsProposerFalse() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "타인 조회용 스터디", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", academicProgram.getId()), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isProposer").value(false))
                .andExpect(jsonPath("$.data.isLeader").value(false));
    }

    @Test
    void getUnknownAcademicProgramReturns404() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", 999_999L), proposerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    @Test
    void getAcademicProgramWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(PROGRAMS + "/{id}", 1L)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 목록 조회

    @Test
    void searchAcademicProgramsReturnsListEnvelope() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "목록용 스터디", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].academicProgramId").value(academicProgram.getId()))
                .andExpect(jsonPath("$.data[0].title").value("목록용 스터디"))
                .andExpect(jsonPath("$.data[0].typeCd").value("STUDY"))
                .andExpect(jsonPath("$.data[0].sttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].leadrMbrNm").value("제출자"))
                .andExpect(jsonPath("$.data[0].progressRatio").value(0))
                .andExpect(jsonPath("$.data[0].isLeader").value(true))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.sort").value("-createdAt"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(1));
    }

    @Test
    void searchWithTypeCdFilterExcludesOtherTypes() throws Exception {
        createAcademicProgram("STUDY", "필터용 스터디", "1주차");
        createAcademicProgram("PROJECT", "필터용 프로젝트", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("typeCd", "PROJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("필터용 프로젝트"));
    }

    // 픽스처가 만드는 학술 활동은 전부 APPROVED다 — 다른 상태로 필터링하면 결과가 없어야 한다(404가 아니다)
    @Test
    void searchWithNonMatchingSttsCdReturnsEmptyArray() throws Exception {
        createAcademicProgram("STUDY", "상태 필터용 스터디", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("sttsCd", "ONGOING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalCount").value(0));
    }

    @Test
    void searchWithUnknownSttsCdReturnsInvalidCodeValue() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("sttsCd", "무효"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    // mine=true는 leadrMbrId 또는 prpsrMbrId가 나인 것만 본다 — 제출만 한 다른 회원은 걸리지 않는다
    @Test
    void searchWithMineFilterReturnsOnlyOwnPrograms() throws Exception {
        createAcademicProgram("STUDY", "내 기획안", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("mine", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)));

        mockMvc.perform(authorized(get(PROGRAMS), otherToken).param("mine", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /*
     * mine=leader는 스터디장 본인의 활동만 본다 (#215). 응답의 isLeader와 **같은 기준**이라
     * 결과가 비어 있는지로 "이 사람이 스터디장인가"를 판정해도 어긋나지 않는다 — mine=true로는
     * 그 판정을 할 수 없다(바로 아래 테스트가 그 이유다).
     */
    @Test
    void searchWithMineLeaderExcludesProgramsLedByOthers() throws Exception {
        AcademicProgramEntity handedOver = createAcademicProgram("STUDY", "넘겨준 스터디", "1주차");
        createAcademicProgram("STUDY", "내가 맡은 스터디", "1주차");
        handOverLeadership(handedOver, other);

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("mine", "leader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("내가 맡은 스터디"))
                .andExpect(jsonPath("$.data[0].isLeader").value(true))
                .andExpect(jsonPath("$.page.totalCount").value(1));
    }

    /*
     * 함정의 회귀 방지 (#215). mine=true는 리더가 아닌 **제출자**도 통과시키며 그 행의 isLeader는
     * false다 — 이 조합이 "mine 결과가 비어 있지 않으면 스터디장"이라는 판정을 깨뜨린다.
     * mine=true의 뜻은 어드민·lms가 이미 쓰고 있어 그대로 둔다.
     */
    @Test
    void searchWithMineTrueStillIncludesProgramsProposedButNotLed() throws Exception {
        AcademicProgramEntity handedOver = createAcademicProgram("STUDY", "넘겨준 스터디", "1주차");
        handOverLeadership(handedOver, other);

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("mine", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("넘겨준 스터디"))
                .andExpect(jsonPath("$.data[0].isLeader").value(false));
    }

    // mine=proposer는 반대편이다 — 리더 자리를 넘겨도 제출자에게 남고, 넘겨받은 쪽에는 리더로만 보인다
    @Test
    void searchWithMineProposerFollowsProposerNotLeader() throws Exception {
        AcademicProgramEntity handedOver = createAcademicProgram("STUDY", "넘겨준 스터디", "1주차");
        handOverLeadership(handedOver, other);

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("mine", "proposer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)));

        mockMvc.perform(authorized(get(PROGRAMS), otherToken).param("mine", "proposer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(authorized(get(PROGRAMS), otherToken).param("mine", "leader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].isLeader").value(true));
    }

    // Boolean 바인딩 시절 mine=false는 "필터 없음"이었다 — 역할 표기가 된 뒤에도 그 뜻을 유지한다
    @Test
    void searchWithMineFalseAppliesNoFilter() throws Exception {
        createAcademicProgram("STUDY", "남의 스터디", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), otherToken).param("mine", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)));
    }

    // 알 수 없는 표기는 조용히 기본값으로 떨어뜨리지 않는다(sttsCd·sort와 같은 판단)
    @Test
    void searchWithUnknownMineRoleReturnsInvalidCodeValue() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("mine", "무효"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void searchWithKeywordFilterMatchesTitle() throws Exception {
        createAcademicProgram("STUDY", "네트워크 스터디", "1주차");
        createAcademicProgram("STUDY", "백엔드 스터디", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("keyword", "네트워크"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("네트워크 스터디"));
    }

    /*
     * 기본 정렬(createdAt desc)은 두 행을 빠르게 연달아 만들면 CI 환경의 시각 분해능에 따라
     * 같은 값으로 찍혀 순서가 흔들릴 수 있다(실제로 CI에서 이 이유로 떨어졌다) — 그래서 여기서는
     * 픽스처가 직접 통제할 수 있는 eventBgngDt 오름차순으로 정렬해 순서를 결정론적으로 만든다.
     */
    @Test
    void searchWithSizeOnePaginatesWithCursor() throws Exception {
        createAcademicProgramWithPeriod("STUDY", "페이지1", Instant.parse("2026-09-01T00:00:00Z"));
        createAcademicProgramWithPeriod("STUDY", "페이지2", Instant.parse("2026-09-08T00:00:00Z"));

        String firstPage =
                mockMvc.perform(
                                authorized(get(PROGRAMS), proposerToken)
                                        .param("size", "1")
                                        .param("sort", "eventBgngDt"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                        .andExpect(jsonPath("$.data[0].title").value("페이지1"))
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(firstPage).read("$.page.nextCursor", String.class);

        mockMvc.perform(
                        authorized(get(PROGRAMS), proposerToken)
                                .param("size", "1")
                                .param("sort", "eventBgngDt")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("페이지2"))
                .andExpect(jsonPath("$.page.hasNext").value(false));
    }

    @Test
    void searchWithMalformedCursorReturns400ValidationFailed() throws Exception {
        mockMvc.perform(
                        authorized(get(PROGRAMS), proposerToken)
                                .param("cursor", "!!not-a-cursor!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchAcademicProgramsWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(PROGRAMS)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 커리큘럼 계획 조회 (#134)

    /*
     * 실적(sesn) 행이 없어도 sesnSttsCd는 비지 않는다 — 서버가 NOT_SUBMITTED를 합성해
     * 내리므로 클라이언트에 null 분기가 없다(설계 결정 #1). Session 엔티티가 아직 없어(#135)
     * 지금은 모든 줄이 이 상태다.
     */
    @Test
    void getCurriculumItemsReturnsPlanRowsWithNotSubmittedSession() throws Exception {
        AcademicProgramEntity academicProgram =
                createAcademicProgram("STUDY", "커리큘럼 조회용 스터디", "OT", "1주차", "2주차");

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/{id}/curriculum-items", academicProgram.getId()),
                                proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.data[0].curriculumItemId").isNumber())
                .andExpect(jsonPath("$.data[0].seqno").value(1))
                .andExpect(jsonPath("$.data[0].ttl").value("OT"))
                .andExpect(jsonPath("$.data[0].planYmd").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.data[0].sessionId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].sesnSttsCd").value("NOT_SUBMITTED"))
                .andExpect(jsonPath("$.data[0].actlYmd").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].prgrsCn").value(Matchers.nullValue()))
                // 회차 순서로 내려간다 — 화면이 회차 이력 표라 등록 순서가 아니라 seqno가 줄 순서다
                .andExpect(jsonPath("$.data[1].seqno").value(2))
                .andExpect(jsonPath("$.data[1].ttl").value("1주차"))
                .andExpect(jsonPath("$.data[2].seqno").value(3))
                .andExpect(jsonPath("$.data[2].ttl").value("2주차"));
    }

    // isEditable은 스터디장 본인 & 기록 가능한 상태(NOT_SUBMITTED/REVISION_REQUESTED) 둘 다 만족할 때만 true다
    @Test
    void getCurriculumItemsAsLeaderShowsIsEditableTrue() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "본인 조회", "1주차");

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/{id}/curriculum-items", academicProgram.getId()),
                                proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isEditable").value(true));
    }

    /*
     * 스터디장이 아닌 회원은 표를 보되 편집할 수 없다(인증만 요구하는 조회라 403이 아니다).
     * 상태 조건은 만족하지만 본인이 아니므로 isEditable이 false — 두 조건의 곱이다.
     */
    @Test
    void getCurriculumItemsAsOtherMemberShowsIsEditableFalse() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "타인 조회", "1주차");

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/{id}/curriculum-items", academicProgram.getId()),
                                otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sesnSttsCd").value("NOT_SUBMITTED"))
                .andExpect(jsonPath("$.data[0].isEditable").value(false));
    }

    // 다른 활동의 커리큘럼이 섞여 들어오지 않는다 — 질의가 academicProgramId로 애초에 좁혀 읽는다
    @Test
    void getCurriculumItemsExcludesOtherProgramsItems() throws Exception {
        AcademicProgramEntity target = createAcademicProgram("STUDY", "내 스터디", "내 1주차", "내 2주차");
        createAcademicProgram("STUDY", "남의 스터디", "남의 1주차");

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/{id}/curriculum-items", target.getId()),
                                proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[*].ttl", Matchers.contains("내 1주차", "내 2주차")));
    }

    // 커리큘럼이 0건인 활동은 404가 아니라 빈 배열이다
    @Test
    void getCurriculumItemsOfProgramWithoutItemsReturnsEmptyArray() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "커리큘럼 없는 스터디");

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/{id}/curriculum-items", academicProgram.getId()),
                                proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // 활동 자체가 없으면 빈 배열이 아니라 404다 — 커리큘럼 0건과 구별돼야 한다
    @Test
    void getCurriculumItemsOfUnknownProgramReturns404() throws Exception {
        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/{id}/curriculum-items", 999_999L), proposerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    @Test
    void getCurriculumItemsWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(PROGRAMS + "/{id}/curriculum-items", 1L))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 헬퍼

    private AcademicProgramEntity createAcademicProgram(
            String typeCd, String title, String... curriculumTitles) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                typeCd,
                title,
                proposer,
                List.of(curriculumTitles));
    }

    private AcademicProgramEntity createAcademicProgramWithPeriod(
            String typeCd, String title, Instant eventBgngDt) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                typeCd,
                title,
                proposer,
                List.of("1주차"),
                eventBgngDt,
                eventBgngDt.plusSeconds(60 * 60 * 24 * 30));
    }

    /*
     * 승인 후속 처리(AcademicProgramApprovalEffectsServiceImpl.linkRecruitmentForm)가 하는 일을
     * 흉내 낸다 — 문항 0개 DRAFT 폼을 만들어 활동의 Event에 연결한다. 여는 것은 모집 시작
     * 전이의 몫이므로 상태는 DRAFT로 둔다.
     */
    private FormEntity linkRecruitmentForm(AcademicProgramEntity academicProgram) {
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                proposer,
                                academicProgram.getEvent().getTitle() + " 모집",
                                new QuestionCompositionContent(null, List.of()),
                                null,
                                null,
                                FormStatus.DRAFT));
        EventEntity event = academicProgram.getEvent();
        event.linkForm(form);
        eventRepository.saveAndFlush(event);
        return form;
    }

    /*
     * 리더 자리를 다른 회원에게 넘긴다 — 제출자 ≠ 스터디장인 활동을 만드는 자리다 (#215).
     * 앱에는 아직 리더를 바꾸는 경로가 없어(AcademicProgramEntity.create가 언제나 제출자를
     * 리더로 세운다) 이 상태를 HTTP로는 만들 수 없지만, leadr_mbr_id는 updatable이고 위임이
     * 생기면 그때 실제로 나타난다 — mine 필터가 두 역할을 가르는지는 그 전에 못 박아 둔다.
     *
     * 벌크 update는 영속성 컨텍스트를 지나치므로 앞뒤로 flush·clear가 필요하다. clear가 테스트
     * 필드의 엔티티를 준영속으로 만들기 때문에 활동은 넘기기 **전에** 다 만들어 둔다.
     */
    private void handOverLeadership(AcademicProgramEntity academicProgram, MemberEntity leader) {
        entityManager.flush();
        entityManager
                .createQuery(
                        "update AcademicProgramEntity a set a.leader = :leader where a.id = :id")
                .setParameter("leader", leader)
                .setParameter("id", academicProgram.getId())
                .executeUpdate();
        entityManager.clear();
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

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
