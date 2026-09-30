package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hamcrest.Matchers;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTransition;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionTransition;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
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
 *
 * generate_statistics는 목록 진행률이 N+1이 아닌지 재기 위한 것이다(#609). 컨텍스트를 새로
 * 띄우지 않도록 RoleControllerTest와 설정을 글자까지 맞췄다(#103 — 컨텍스트 수가 테스트 시간을
 * 지배한다).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
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
    @Autowired private SessionRepository sessionRepository;
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
                // 분모는 계획 항목 수다(#609) — 기록이 하나도 없어도 0이 아니라 2다
                .andExpect(jsonPath("$.data.progress.totalSessionCount").value(2))
                .andExpect(jsonPath("$.data.progress.approvedSessionCount").value(0))
                .andExpect(jsonPath("$.data.progress.ratio").value(0))
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
        AcademicProgramEntity academicProgram =
                createAcademicProgram("STUDY", "목록용 스터디", "1주차", "2주차");
        recordSessions(academicProgram, SessionStatus.APPROVED);

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].academicProgramId").value(academicProgram.getId()))
                .andExpect(jsonPath("$.data[0].title").value("목록용 스터디"))
                .andExpect(jsonPath("$.data[0].typeCd").value("STUDY"))
                .andExpect(jsonPath("$.data[0].sttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].leadrMbrNm").value("제출자"))
                // 계획 2개 중 1개 승인 (#609)
                .andExpect(jsonPath("$.data[0].progressRatio").value(50))
                .andExpect(jsonPath("$.data[0].isLeader").value(true))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.sort").value("-createdAt"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(1));
    }

    /*
     * 모집 카드가 쓰는 값이 목록에도 실린다 (#483).
     *
     * 그전까지 카드가 필요한 것(접수 상태·접수 기간·정원·지원 건수·문항 버전·승인일) 중
     * 하나도 없어 화면이 카드마다 활동 상세를 한 번 더 불러야 했다.
     *
     * **배지의 축은 sttsCd가 아니라 formReceiptStatus다** — 이 활동은 APPROVED이고 폼은
     * 아직 DRAFT라, 화면의 "모집 시작 전"이 그 둘을 함께 덮는다.
     */
    @Test
    void searchAcademicProgramsCarryRecruitmentCardFields() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "모집 카드용 스터디", "1주차");
        FormEntity recruitmentForm = linkRecruitmentForm(academicProgram);

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].formId").value(recruitmentForm.getId()))
                .andExpect(jsonPath("$.data[0].formReceiptStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].qitemVer").value(1))
                // 승인이 곧 생성이라 승인 일시를 담는 컬럼이 따로 없다 — acdm_actv.crt_dt다
                .andExpect(jsonPath("$.data[0].approvedAt").isNotEmpty())
                // 접수 전에도 0을 그대로 내린다 — "미모집" 같은 대체값을 만들지 않는다
                .andExpect(jsonPath("$.data[0].applicationCount").value(0))
                // 모집 시작 전이라 기간은 아직 없다
                .andExpect(jsonPath("$.data[0].rcptBgngDt").doesNotExist())
                .andExpect(jsonPath("$.data[0].rcptEndDt").doesNotExist());
    }

    /*
     * 지원 건수는 폼 목록의 responseCount와 **같은 기준**이다 (#483) — 작성 중(DRAFT)은 세지
     * 않는다. 기준을 학술 쪽에서 다시 세우면 같은 폼이 두 화면에서 다른 숫자로 보인다.
     */
    @Test
    void searchAcademicProgramsCountApplicationsExcludingDrafts() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "지원자 있는 스터디", "1주차");
        FormEntity recruitmentForm = linkRecruitmentForm(academicProgram);
        MemberEntity applicant = saveMember(UUID.randomUUID(), "20260811", "지원자");
        MemberEntity drafter = saveMember(UUID.randomUUID(), "20260812", "작성 중인 사람");

        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        recruitmentForm,
                        applicant,
                        ResponseContent.of(Map.of("q1", "지원합니다")),
                        Instant.parse("2026-09-01T03:00:00Z")));
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(
                        recruitmentForm, drafter, ResponseContent.of(Map.of("q1", "작성 중"))));

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applicationCount").value(1));
    }

    /*
     * 폼이 연결되지 않은 활동(이관 전·정합성 깨짐)도 목록에서 빠지지 않는다 (#483).
     *
     * 질의가 left join이라 행은 그대로 오고 폼에서 오는 값만 비어 있다 — inner join이면 그
     * 활동이 목록에서 통째로 사라지는데, 그것은 "폼이 없다"가 아니라 "활동이 없다"로 보인다.
     */
    @Test
    void searchAcademicProgramsWithoutLinkedFormLeavesFormFieldsNull() throws Exception {
        createAcademicProgram("STUDY", "폼 없는 스터디", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].formId").doesNotExist())
                .andExpect(jsonPath("$.data[0].formReceiptStatus").doesNotExist())
                .andExpect(jsonPath("$.data[0].qitemVer").doesNotExist())
                .andExpect(jsonPath("$.data[0].applicationCount").value(0));
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
     * 공유 링크 **폐기**는 리더(또는 학술국장)만 한다 (#556 · ssccops#501).
     *
     * 그전에는 이 핸들러가 상세 조회(인증만)를 태우는 것이 전부라 **가입한 아무 회원이나 남의
     * 모집 링크를 끊을 수 있었다.** 발급 쪽 논증(«볼 수 있는 사람이 공유할 수 있다»)은 한 줄도
     * 폐기를 다루지 않는데 같은 게이트가 그대로 적용돼 있었다.
     *
     * 결과로 보면 둘은 반대다 — 발급은 멱등이라 되돌릴 수 있지만 폐기는 **이미 퍼진 주소를
     * 죽이고**, 다시 발급하면 새 토큰이라 단톡방에 뿌린 링크는 살아나지 않는다.
     */
    @Test
    void revokeShareLinkRejectsAMemberWhoIsNeitherLeaderNorManager() throws Exception {
        AcademicProgramEntity program = createAcademicProgram("STUDY", "모집 중인 스터디", "1주차");

        mockMvc.perform(authorized(delete(PROGRAMS + "/" + program.getId() + "/share"), otherToken))
                .andExpect(status().isForbidden());
    }

    /* 리더 본인은 자기가 뿌린 링크를 거둘 수 있어야 한다 — 막으면 이 기능이 성립하지 않는다. */
    @Test
    void revokeShareLinkAllowsTheLeader() throws Exception {
        AcademicProgramEntity program = createAcademicProgram("STUDY", "내가 맡은 스터디", "1주차");

        mockMvc.perform(
                        authorized(
                                delete(PROGRAMS + "/" + program.getId() + "/share"), proposerToken))
                .andExpect(status().isOk());
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

    // ------------------------------------------------------------------ 진행률 (#609)
    //
    // 승인 회차 ÷ 계획 항목 × 100. #131~#608 동안 목록·상세 모두 언제나 0이었고, 위 테스트들이
    // 그 0을 기대값으로 못 박고 있어 CI가 잡지 못했다 — 그래서 여기서는 0이 아닌 값을 본다.

    // 계획 항목이 없으면 나눌 것이 없다 — 0으로 떨어지고 오류가 나지 않는다
    @Test
    void progressIsZeroWhenProgramHasNoCurriculumItems() throws Exception {
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "계획 없는 스터디");

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", academicProgram.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.totalSessionCount").value(0))
                .andExpect(jsonPath("$.data.progress.approvedSessionCount").value(0))
                .andExpect(jsonPath("$.data.progress.ratio").value(0))
                .andExpect(jsonPath("$.data.curriculumItemCount").value(0));

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].progressRatio").value(0));
    }

    /*
     * 분자는 승인된 회차뿐이고 분모는 계획 전부다. 제출됨(학술국장이 아직 보지 않은 기록)과
     * 수정요청은 세지 않고, 아직 기록이 없는 항목도 분모에서 빠지지 않는다 — 실적 행 수로
     * 나누면 이 활동은 1/3이 된다.
     */
    @Test
    void progressCountsOnlyApprovedSessionsOverEveryPlannedItem() throws Exception {
        AcademicProgramEntity academicProgram =
                createAcademicProgram("STUDY", "일부 승인 스터디", "OT", "1주차", "2주차", "3주차");
        recordSessions(
                academicProgram,
                SessionStatus.APPROVED,
                SessionStatus.SUBMITTED,
                SessionStatus.REVISION_REQUESTED);

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", academicProgram.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.totalSessionCount").value(4))
                .andExpect(jsonPath("$.data.progress.approvedSessionCount").value(1))
                .andExpect(jsonPath("$.data.progress.ratio").value(25))
                // totalSessionCount는 이름과 달리 계획 항목 수다 — 언제나 이 값과 같다
                .andExpect(jsonPath("$.data.curriculumItemCount").value(4));
    }

    @Test
    void progressReaches100WhenEveryPlannedItemIsApproved() throws Exception {
        AcademicProgramEntity academicProgram =
                createAcademicProgram("STUDY", "전부 승인 스터디", "1주차", "2주차");
        recordSessions(academicProgram, SessionStatus.APPROVED, SessionStatus.APPROVED);

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", academicProgram.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.totalSessionCount").value(2))
                .andExpect(jsonPath("$.data.progress.approvedSessionCount").value(2))
                .andExpect(jsonPath("$.data.progress.ratio").value(100));
    }

    // 목록의 progressRatio와 상세의 progress.ratio는 같은 값이다 — 반올림(소수 2자리)까지 같다
    @Test
    void listAndDetailCarryTheSameProgress() throws Exception {
        AcademicProgramEntity academicProgram =
                createAcademicProgram("STUDY", "목록 상세 비교 스터디", "1주차", "2주차", "3주차");
        recordSessions(
                academicProgram,
                SessionStatus.APPROVED,
                SessionStatus.APPROVED,
                SessionStatus.SUBMITTED);

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", academicProgram.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.ratio").value(66.67));

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].progressRatio").value(66.67));
    }

    /*
     * 목록은 페이지의 활동 id로 묶어 한 번에 센다 — 묶은 집계가 활동끼리 섞이지 않는지 본다.
     * 정렬(createdAt)은 CI의 시각 분해능에 흔들리므로(searchWithSizeOnePaginatesWithCursor 주석)
     * 순서가 아니라 제목으로 줄을 찾는다.
     */
    @Test
    void listCountsProgressPerProgramWithoutMixingThem() throws Exception {
        AcademicProgramEntity allApproved = createAcademicProgram("STUDY", "전부 승인", "1주차", "2주차");
        AcademicProgramEntity oneOfThree =
                createAcademicProgram("PROJECT", "셋 중 하나", "1주차", "2주차", "3주차");
        createAcademicProgram("STUDY", "계획 없음");
        recordSessions(allApproved, SessionStatus.APPROVED, SessionStatus.APPROVED);
        recordSessions(oneOfThree, SessionStatus.APPROVED, SessionStatus.SUBMITTED);

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(3)))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.title == '전부 승인')].progressRatio",
                                Matchers.contains(100.0)))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.title == '셋 중 하나')].progressRatio",
                                Matchers.contains(33.33)))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.title == '계획 없음')].progressRatio",
                                Matchers.contains(0.0)));
    }

    /*
     * 카드마다 진행률을 세면 100건 페이지가 N+1이다(DB-13). 한 건짜리 페이지와 세 건짜리
     * 페이지가 **같은 수의 질의**로 끝나는지 본다 — 절대 수를 박지 않는 것은 인증·건수 질의가
     * 바뀔 때 이 테스트가 함께 빨개지지 않게 하기 위해서다.
     *
     * 두 페이지는 keyword로 가른다. 데이터를 다 만든 뒤 flush·clear 하고 재므로 INSERT가 세어지지
     * 않고, 1차 캐시가 지연 로딩을 가려 주지도 않는다.
     */
    @Test
    void searchCountsProgressWithOneQueryRegardlessOfPageSize() throws Exception {
        AcademicProgramEntity alone = createAcademicProgram("STUDY", "단독 스터디", "1주차", "2주차");
        recordSessions(alone, SessionStatus.APPROVED);
        for (int i = 1; i <= 3; i++) {
            AcademicProgramEntity grouped =
                    createAcademicProgram("STUDY", "묶음 스터디 " + i, "1주차", "2주차");
            recordSessions(grouped, SessionStatus.APPROVED, SessionStatus.SUBMITTED);
        }

        long onePage =
                statementCountOf(
                        authorized(get(PROGRAMS), proposerToken).param("keyword", "단독"), 1);
        long threePage =
                statementCountOf(
                        authorized(get(PROGRAMS), proposerToken).param("keyword", "묶음"), 3);

        // 통계가 꺼져 있으면 둘 다 0이라 아래 단정이 거저 통과한다
        assertThat(onePage).isPositive();
        assertThat(threePage).isEqualTo(onePage);
    }

    // ------------------------------------------------------------------ 지연 (#610)
    //
    // 진행 중 · 운영 기간(event_end_dt)이 지남 · 진행률 100% 미만. 판정 시각은 주입된 Clock의
    // 지금이라 기간은 **지금을 기준으로 상대 일시**로 만든다 — Clock을 @MockitoBean으로 바꾸면
    // 이 클래스만의 스프링 컨텍스트가 새로 뜬다(모집 폼 편집 창 테스트와 같은 판단 · AGENTS.md).

    // 기간이 끝나기 전에는 진행률이 0이어도 지연이 아니다 — 아직 따라잡을 시간이 있다
    @Test
    void notDelayedBeforeTheEndDateEvenAtZeroPercent() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("아직 기간 중", daysFromNow(7), "1주차");

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", program.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.ratio").value(0))
                .andExpect(jsonPath("$.data.isDelayed").value(false));
    }

    // 기간이 지났으면 한 회차만 남아도 지연이다 — «거의 끝났다»를 봐주지 않는 것이 요청받은 기준이다
    @Test
    void delayedAfterTheEndDateWithOnePlannedItemLeft() throws Exception {
        AcademicProgramEntity program =
                createOngoingProgram("하나 남은", daysFromNow(-1), "1주차", "2주차", "3주차");
        recordSessions(program, SessionStatus.APPROVED, SessionStatus.APPROVED);

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", program.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.ratio").value(66.67))
                .andExpect(jsonPath("$.data.isDelayed").value(true));
    }

    @Test
    void notDelayedAfterTheEndDateAtHundredPercent() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("다 마친", daysFromNow(-1), "1주차", "2주차");
        recordSessions(program, SessionStatus.APPROVED, SessionStatus.APPROVED);

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", program.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDelayed").value(false));
    }

    // 제출됐지만 아직 승인되지 않은 회차는 진행률에 들지 않으므로 지연을 풀지 못한다(#609)
    @Test
    void submittedButUnapprovedSessionsDoNotClearTheDelay() throws Exception {
        AcademicProgramEntity program =
                createOngoingProgram("승인 대기", daysFromNow(-1), "1주차", "2주차");
        recordSessions(program, SessionStatus.APPROVED, SessionStatus.SUBMITTED);

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", program.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDelayed").value(true));
    }

    // 종료일이 없으면 «기간이 지났다»가 성립하지 않는다
    @Test
    void notDelayedWithoutAnEndDate() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("종료일 없음", null, "1주차");

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", program.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDelayed").value(false));
    }

    // 계획 항목이 없으면 진행률이 0이라(#609) 기간이 지나면 지연이다 — 나눌 것이 없다고 봐주지 않는다
    @Test
    void delayedAfterTheEndDateWithoutCurriculumItems() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("계획 없음", daysFromNow(-1));

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", program.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.totalSessionCount").value(0))
                .andExpect(jsonPath("$.data.isDelayed").value(true));
    }

    /*
     * 진행 중만 지연이 된다. 모집 전(APPROVED)은 시작도 하지 않은 건이라 지연이 아니라 폐지
     * 후보이고(ssccops#552), 종료(COMPLETED)는 학술국장이 이미 끝낸 것이다.
     */
    @Test
    void notDelayedUnlessOngoing() throws Exception {
        AcademicProgramEntity approved =
                createProgramEndingAt("모집 전인 채 기간 지남", daysFromNow(-1), "1주차");
        AcademicProgramEntity completed = createOngoingProgram("종료된", daysFromNow(-1), "1주차");
        completed.changeStatus(AcademicProgramTransition.APPROVE_COMPLETION);
        entityManager.flush();

        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", approved.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.isDelayed").value(false));
        mockMvc.perform(authorized(get(PROGRAMS + "/{id}", completed.getId()), proposerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sttsCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.isDelayed").value(false));
    }

    /*
     * **delayed=true의 결과와 응답의 isDelayed=true가 같은 집합이다.** 같은 정의를 엔티티
     * (isDelayedAt)와 JPQL(AcademicProgramRepositoryImpl.DELAYED)이 두 벌로 쓰므로, 위 경우를
     * 한 목록에 전부 깔아 두 결과를 대조한다 — 한쪽만 고치면 여기서 갈린다.
     */
    @Test
    void delayedFilterReturnsExactlyTheProgramsMarkedDelayed() throws Exception {
        seedDelayScenarios();

        List<String> markedDelayed =
                JsonPath.parse(listContent(authorized(get(PROGRAMS), proposerToken)))
                        .read("$.data[?(@.isDelayed == true)].title");
        String filteredContent =
                listContent(authorized(get(PROGRAMS), proposerToken).param("delayed", "true"));
        List<String> filtered = JsonPath.parse(filteredContent).read("$.data[*].title");

        assertThat(filtered)
                .containsExactlyInAnyOrderElementsOf(markedDelayed)
                .containsExactlyInAnyOrder("하나 남은", "승인 대기", "계획 없음");
        // 건수 질의도 같은 조건을 지난다
        assertThat(JsonPath.parse(filteredContent).read("$.page.totalCount", Integer.class))
                .isEqualTo(3);
        assertThat(JsonPath.parse(filteredContent).read("$.data[*].isDelayed", List.class))
                .containsOnly(true);
    }

    // sttsCd와 함께 주면 AND다 — 종료된 것 중 지연된 것은 정의상 없다
    @Test
    void delayedFilterIsAndedWithTheStatusFilter() throws Exception {
        seedDelayScenarios();

        mockMvc.perform(
                        authorized(get(PROGRAMS), proposerToken)
                                .param("sttsCd", "COMPLETED")
                                .param("delayed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalCount").value(0));
        mockMvc.perform(
                        authorized(get(PROGRAMS), proposerToken)
                                .param("sttsCd", "ONGOING")
                                .param("delayed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(3)));
    }

    // delayed=false는 «지연 아닌 것»이 아니라 필터 없음이다(하위 업무의 isOverdue와 같은 모양)
    @Test
    void delayedFalseAppliesNoFilter() throws Exception {
        seedDelayScenarios();

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("delayed", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(8)));
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

    /*
     * 지연 판정의 경우를 한 목록에 전부 깐다(#610). 지연인 것은 «하나 남은»·«승인 대기»·
     * «계획 없음» 셋이고 나머지 다섯은 각자 다른 이유로 아니다 — 대조 테스트가 어느 한 조건만
     * 빠뜨려도 집합이 달라지게 하려는 것이다.
     */
    private void seedDelayScenarios() {
        createOngoingProgram("아직 기간 중", daysFromNow(7), "1주차");
        recordSessions(
                createOngoingProgram("하나 남은", daysFromNow(-1), "1주차", "2주차", "3주차"),
                SessionStatus.APPROVED,
                SessionStatus.APPROVED);
        recordSessions(
                createOngoingProgram("다 마친", daysFromNow(-1), "1주차", "2주차"),
                SessionStatus.APPROVED,
                SessionStatus.APPROVED);
        recordSessions(
                createOngoingProgram("승인 대기", daysFromNow(-1), "1주차", "2주차"),
                SessionStatus.APPROVED,
                SessionStatus.SUBMITTED);
        createOngoingProgram("종료일 없음", null, "1주차");
        createOngoingProgram("계획 없음", daysFromNow(-1));
        createProgramEndingAt("모집 전인 채 기간 지남", daysFromNow(-1), "1주차");
        createOngoingProgram("종료된", daysFromNow(-1), "1주차")
                .changeStatus(AcademicProgramTransition.APPROVE_COMPLETION);
        entityManager.flush();
    }

    /*
     * 모집을 시작한(ONGOING) 활동. 전이 API(#133)를 태우지 않고 엔티티 전이를 직접 부른다 —
     * 그 API는 폼 오케스트레이션과 국장 권한을 함께 요구해, 지연 판정을 보려는 테스트가 그
     * 규칙까지 지고 가게 된다(recordSessions와 같은 판단).
     */
    private AcademicProgramEntity createOngoingProgram(
            String title, Instant eventEndDt, String... curriculumTitles) {
        AcademicProgramEntity program = createProgramEndingAt(title, eventEndDt, curriculumTitles);
        program.changeStatus(AcademicProgramTransition.START_RECRUITMENT);
        return program;
    }

    // eventEndDt가 null이면 종료일 없는 활동이다. 시작은 판정에 쓰이지 않아 한 달 전으로 둔다
    private AcademicProgramEntity createProgramEndingAt(
            String title, Instant eventEndDt, String... curriculumTitles) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                "STUDY",
                title,
                proposer,
                List.of(curriculumTitles),
                daysFromNow(-30),
                eventEndDt);
    }

    private static Instant daysFromNow(long days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    private String listContent(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.param("size", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
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
     * 계획 항목 앞에서부터 차례로 회차 기록을 심는다(#609). 뒤에 남는 항목은 기록이 없는
     * NOT_SUBMITTED다 — 그 상태는 sesn 행이 없다는 뜻이라 심을 수 없다(SessionStatus 주석).
     *
     * 승인·수정요청은 전이 API(#136)를 태우지 않고 엔티티 전이를 직접 부른다 — 이 클래스가
     * 국장 권한 부여까지 지고 가면 그 규칙이 바뀔 때 조회 테스트가 함께 빨개진다
     * (AcademicSessionControllerTest가 상태를 벌크 UPDATE로 심는 것과 같은 판단).
     */
    private void recordSessions(AcademicProgramEntity academicProgram, SessionStatus... statuses) {
        List<CurriculumItemEntity> items =
                curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(
                        academicProgram.getId());
        for (int i = 0; i < statuses.length; i++) {
            SessionEntity session =
                    SessionEntity.submit(
                            items.get(i), LocalDate.of(2026, 9, 15), "진행 내용", null, proposer);
            if (statuses[i] == SessionStatus.APPROVED) {
                session.changeStatus(SessionTransition.APPROVE, null);
            } else if (statuses[i] == SessionStatus.REVISION_REQUESTED) {
                session.changeStatus(SessionTransition.REQUEST_REVISION, "보완해 주세요");
            } else if (statuses[i] != SessionStatus.SUBMITTED) {
                throw new IllegalArgumentException(statuses[i] + "는 sesn 행으로 심을 수 없다");
            }
            sessionRepository.save(session);
        }
    }

    /*
     * 요청 하나가 DB에 보낸 문장 수. 심어 둔 데이터를 먼저 flush 해 INSERT가 세어지지 않게 하고,
     * clear 해 1차 캐시가 지연 로딩을 가리지 않게 한다 — 실제 요청은 빈 영속성 컨텍스트에서
     * 시작한다. 결과 건수를 함께 보는 것은 keyword가 빗나가 두 페이지가 모두 비면 질의 수가
     * 같아지는 것이 당연하기 때문이다.
     */
    private long statementCountOf(MockHttpServletRequestBuilder request, int expectedSize)
            throws Exception {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(expectedSize)));

        return statistics.getPrepareStatementCount();
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
