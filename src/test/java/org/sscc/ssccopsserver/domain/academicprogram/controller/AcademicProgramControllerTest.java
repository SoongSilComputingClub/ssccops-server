package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 학술 활동(스터디/프로젝트) 조회 API (#131). 등록(POST) 경로는 없다 — 2026-08-23 설계 변경으로
 * 기획안 접수가 폼 도메인 이관(ssccops#148)으로 대체됐고, 이 이슈에는 조회만 남았다. 테스트
 * 데이터는 HTTP로 만들지 않고 AcademicProgramFixture로 직접 심는다.
 *
 * 요청 주체를 요청마다 바꿔야 해서(제출자 · 다른 회원) AcademicProgramTypeControllerTest와
 * 같은 JwtDecoder 스텁을 쓴다. typeCd는 S0(#130)이 시드하는 STUDY/PROJECT를 그대로 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramControllerTest.StubJwtDecoderConfig.class)
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

    private UUID proposerToken;
    private MemberEntity proposer;
    private UUID otherToken;

    @BeforeEach
    void setUp() {
        proposerToken = UUID.randomUUID();
        proposer = saveMember(proposerToken, "20260401", "제출자");

        otherToken = UUID.randomUUID();
        saveMember(otherToken, "20260402", "다른회원");
    }

    // ------------------------------------------------------------------ 단건 조회

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
                .andExpect(jsonPath("$.data.sttsCd").value("PROPOSED"))
                .andExpect(jsonPath("$.data.prpsrMbrId").value(proposer.getId()))
                .andExpect(jsonPath("$.data.prpsrMbrNm").value("제출자"))
                .andExpect(jsonPath("$.data.leadrMbrId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.formId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.progress.totalSessionCount").value(0))
                .andExpect(jsonPath("$.data.progress.approvedSessionCount").value(0))
                .andExpect(jsonPath("$.data.curriculumItemCount").value(2))
                .andExpect(jsonPath("$.data.isProposer").value(true))
                .andExpect(jsonPath("$.data.isLeader").value(false));
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
                .andExpect(jsonPath("$.data[0].sttsCd").value("PROPOSED"))
                .andExpect(jsonPath("$.data[0].leadrMbrNm").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].progressRatio").value(0))
                .andExpect(jsonPath("$.data[0].isLeader").value(false))
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

    // 픽스처가 만드는 학술 활동은 전부 PROPOSED다 — 다른 상태로 필터링하면 결과가 없어야 한다(404가 아니다)
    @Test
    void searchWithNonMatchingSttsCdReturnsEmptyArray() throws Exception {
        createAcademicProgram("STUDY", "상태 필터용 스터디", "1주차");

        mockMvc.perform(authorized(get(PROGRAMS), proposerToken).param("sttsCd", "APPROVED"))
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

    // ------------------------------------------------------------------ 헬퍼

    private AcademicProgramEntity createAcademicProgram(
            String typeCd, String title, String... curriculumTitles) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
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
                typeCd,
                title,
                proposer,
                List.of("1주차"),
                eventBgngDt,
                eventBgngDt.plusSeconds(60 * 60 * 24 * 30));
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

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", token + "@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
