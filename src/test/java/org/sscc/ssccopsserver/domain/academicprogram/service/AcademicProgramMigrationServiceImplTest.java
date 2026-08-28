package org.sscc.ssccopsserver.domain.academicprogram.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeeder;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

/*
 * 승인된 기획안 → 학술 활동 이관 (#150).
 *
 * **API로 검증한다.** 이 이슈가 만드는 것은 서비스 메서드 하나가 아니라 "폼 응답을 승인하면
 * 활동이 생긴다"는 경로 전체이며, 그 경로에는 폼 도메인의 훅 조회(SystemFormApprovalHooks)와
 * 학술 도메인의 구현체 등록(ProposalApprovalHook)이라는 배선이 들어 있다 — 서비스만 직접
 * 부르면 그 배선이 끊겨도 초록으로 남는다.
 *
 * 시드된 PROPOSAL 폼을 그대로 쓴다. 폼을 테스트가 직접 만들면 qitemId가 시드와 갈릴 수 있고,
 * 이 이슈가 실제로 읽는 것은 운영에 세워지는 그 폼이다. 기동 시점에는 회원이 없어 시드가
 * 건너뛰어졌으므로(ProposalFormSeeder — creatr_mbr_id가 NOT NULL이다) 회원을 만든 뒤 시드를
 * 직접 부른다. 시드는 멱등해서 이미 세워져 있으면 아무것도 하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramMigrationServiceImplTest.StubJwtDecoderConfig.class)
@Transactional
class AcademicProgramMigrationServiceImplTest {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private static final String CURRICULUM =
            """
            1회차 | 오리엔테이션 | 2026-03-05
            2회차 | React, 그리고 상태관리
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;

    @Autowired private ProposalFormSeeder proposalFormSeeder;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;

    private UUID reviewerToken;
    private FormEntity proposalForm;

    @BeforeEach
    void setUp() {
        reviewerToken = UUID.randomUUID();
        MemberEntity reviewer = saveMember(reviewerToken, "20260101", "김검토");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                reviewer,
                MemberRoleFixture.DIRECTOR);
        entityManager.flush();

        proposalFormSeeder.seed();
        proposalForm =
                formRepository
                        .findBySystemFormCode(ProposalFormSeed.SYSTEM_FORM_CODE)
                        .orElseThrow();
    }

    /*
     * 승인 한 번에 Event + AcademicProgram + CurriculumItem[]이 생기고, 곧바로 #133의 승인 후속
     * 처리(리더 역할·빈 모집 폼)까지 이어진다 — 전부 같은 트랜잭션이다.
     */
    @Test
    void acceptingProposalCreatesEventProgramAndCurriculum() throws Exception {
        MemberEntity proposer = saveMember("20260201", "이제안");
        FormResponseHistoryEntity response = saveResponse(proposer, answers(CURRICULUM));

        accept(response).andExpect(status().isOk());
        flushAndClear();

        AcademicProgramEntity program = findMigrated(response);
        assertThat(program.getStatus()).isEqualTo(AcademicProgramStatus.APPROVED);
        assertThat(program.getType().getCode()).isEqualTo("STUDY");
        assertThat(program.getGoalContent()).isEqualTo("알고리즘 문제 풀이 근육을 만든다");
        assertThat(program.getPrepContent()).isEqualTo("노트북");
        assertThat(program.getScheduleText()).isEqualTo("매주 화요일 19:00");
        assertThat(program.getCapacityMinCount()).isEqualTo(4);
        assertThat(program.getCapacityMaxCount()).isEqualTo(12);

        // 제출자가 곧 리더다 — 승인이 곧 생성이라 "승인 전" 구간이 없다 (#133)
        assertThat(program.getProposer().getId()).isEqualTo(proposer.getId());
        assertThat(program.getLeader().getId()).isEqualTo(proposer.getId());

        EventEntity event = program.getEvent();
        assertThat(event.getTitle()).isEqualTo("알고리즘 스터디");
        assertThat(event.getPlaceName()).isEqualTo("전산관 401호");
        assertThat(event.getBeginAt())
                .isEqualTo(LocalDate.of(2026, 3, 2).atStartOfDay(SERVICE_ZONE).toInstant());
        /*
         * 종료는 종료일 그날의 끝이다. 시작과 같이 자정으로 두면 종료일 당일에 이미 '종료'
         * (EventPhase.ENDED)로 파생되고, 반대로 다음 날 0시로 두면 화면의 종료 일시가 하루 밀린다.
         */
        assertThat(event.getEndAt())
                .isEqualTo(
                        LocalDate.of(2026, 6, 30)
                                .atTime(23, 59, 59)
                                .atZone(SERVICE_ZONE)
                                .toInstant());

        assertThat(curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(program.getId()))
                .extracting(
                        CurriculumItemEntity::getSeqno,
                        CurriculumItemEntity::getTitle,
                        CurriculumItemEntity::getPlanDate)
                .containsExactly(
                        tuple(1, "오리엔테이션", LocalDate.of(2026, 3, 5)),
                        tuple(2, "React, 그리고 상태관리", null));

        // #133의 승인 후속 처리가 실제로 불렸는가 — 역할 부여와 빈 모집 폼 연결
        List<MemberRoleAssignmentEntity> assignments =
                memberRoleAssignmentRepository.findCurrentByMemberId(proposer.getId());
        assertThat(assignments)
                .extracting(assignment -> assignment.getRole().getName())
                .contains(MemberRoleFixture.STUDY_LEADER);
        assertThat(event.getForm()).isNotNull();
        assertThat(event.getForm().getTitle()).isEqualTo("알고리즘 스터디 모집");
    }

    /*
     * 파싱 실패는 승인 실패다. 400으로 끊기고 사유가 응답 메시지에 실린다 — 검토자가 그 문장을
     * 그대로 수정요청(#141)에 옮겨 적을 수 있어야 한다.
     */
    @Test
    void rejectsAcceptWhenCurriculumCannotBeParsed() throws Exception {
        MemberEntity proposer = saveMember("20260202", "박형식");
        FormResponseHistoryEntity response = saveResponse(proposer, answers("1회차 오리엔테이션"));

        accept(response)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROPOSAL_MIGRATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("1번째 줄")));
    }

    /*
     * 유형 문자열이 기준정보(acdm_actv_type.type_nm)의 어느 이름과도 맞지 않으면 이관이
     * 성립하지 않는다. 조용히 아무 유형이나 고르지 않는 것이 이 이슈의 규칙이며, 사유는 운영진이
     * 폼 선택지와 기준정보 중 어느 쪽을 고쳐야 하는지 알 수 있게 적는다.
     */
    @Test
    void rejectsAcceptWhenProgramTypeCannotBeMapped() throws Exception {
        MemberEntity proposer = saveMember("20260203", "최유형");
        Map<String, Object> answers = answers(CURRICULUM);
        answers.put(ProposalFormSeed.QITEM_PROGRAM_TYPE, "세미나");
        FormResponseHistoryEntity response = saveResponse(proposer, answers);

        accept(response)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROPOSAL_MIGRATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("세미나")));
    }

    // 이관이 실패했으면 승인도 없던 일이 되어야 한다 — 같은 트랜잭션이라는 것의 뜻이다
    @Test
    void doesNotCreateAnythingWhenMigrationFails() throws Exception {
        MemberEntity proposer = saveMember("20260204", "정롤백");
        FormResponseHistoryEntity response = saveResponse(proposer, answers("형식이 틀린 줄"));
        long programsBefore = academicProgramRepository.count();

        accept(response).andExpect(status().isBadRequest());
        flushAndClear();

        assertThat(academicProgramRepository.count()).isEqualTo(programsBefore);
        assertThat(academicProgramRepository.existsByFormResponse(response)).isFalse();
    }

    /*
     * 검토 화면이 보는 미리보기와 실제 이관 결과가 같아야 한다 (#150의 핵심 규칙). 같은 파서를
     * 쓴다는 것을 값으로 확인한다 — 승인 전 미리보기의 회차가 승인 후 만들어진 회차와 같다.
     */
    @Test
    void previewShowsExactlyWhatMigrationWillCreate() throws Exception {
        MemberEntity proposer = saveMember("20260205", "한미리");
        FormResponseHistoryEntity response = saveResponse(proposer, answers(CURRICULUM));

        mockMvc.perform(authorized(get(responsePath(response))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramPreview.typeCd").value("STUDY"))
                .andExpect(jsonPath("$.data.academicProgramPreview.migratable").value(true))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.failureReason").value(nullValue()))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.curriculumItems.length()").value(2))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.curriculumItems[0].seqno").value(1))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.curriculumItems[0].ttl")
                                .value("오리엔테이션"))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.curriculumItems[0].planYmd")
                                .value("2026-03-05"))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.curriculumItems[1].planYmd")
                                .value(nullValue()));

        accept(response).andExpect(status().isOk());
        flushAndClear();

        assertThat(
                        curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(
                                findMigrated(response).getId()))
                .extracting(CurriculumItemEntity::getSeqno, CurriculumItemEntity::getTitle)
                .containsExactly(tuple(1, "오리엔테이션"), tuple(2, "React, 그리고 상태관리"));
    }

    // 승인이 막힐 응답은 승인 전에 그 사유가 보인다 — 검토자가 400을 만나기 전에 알아야 한다
    @Test
    void previewCarriesTheFailureReasonInsteadOfThrowing() throws Exception {
        MemberEntity proposer = saveMember("20260206", "오실패");
        FormResponseHistoryEntity response = saveResponse(proposer, answers("1회차 오리엔테이션"));

        mockMvc.perform(authorized(get(responsePath(response))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramPreview.migratable").value(false))
                .andExpect(jsonPath("$.data.academicProgramPreview.typeCd").value(nullValue()))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.curriculumItems.length()").value(0))
                .andExpect(
                        jsonPath("$.data.academicProgramPreview.failureReason")
                                .value(containsString("1번째 줄")));
    }

    // 기획안이 아닌 폼의 응답에는 미리보기가 붙지 않는다 — 훅이 없는 폼이 정상이다
    @Test
    void previewIsAbsentForOrdinaryForms() throws Exception {
        MemberEntity proposer = saveMember("20260207", "남평범");
        FormEntity ordinary =
                formRepository.save(
                        FormEntity.create(
                                proposer,
                                "2026 신규모집 지원서",
                                proposalForm.getQuestionComposition().deepCopy(),
                                null,
                                null));
        FormResponseHistoryEntity response =
                formResponseHistoryRepository.save(
                        FormResponseHistoryEntity.createSubmitted(
                                ordinary,
                                proposer,
                                ResponseContent.of(answers(CURRICULUM)),
                                Instant.now()));
        entityManager.flush();

        mockMvc.perform(
                        authorized(
                                get(
                                        "/v1/forms/"
                                                + ordinary.getId()
                                                + "/responses/"
                                                + response.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramPreview").value(nullValue()));
    }

    // ------------------------------------------------------------------ 헬퍼

    private AcademicProgramEntity findMigrated(FormResponseHistoryEntity response) {
        return academicProgramRepository.findAll().stream()
                .filter(program -> program.getFormResponse().getId().equals(response.getId()))
                .findFirst()
                .orElseThrow();
    }

    private ResultActions accept(FormResponseHistoryEntity response) throws Exception {
        return mockMvc.perform(
                authorized(post(responsePath(response) + "/reviews"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rspnsSttsCd\": \"ACCEPTED\", \"rvwOpnnCn\": \"좋은 기획입니다.\"}"));
    }

    private String responsePath(FormResponseHistoryEntity response) {
        return "/v1/forms/" + proposalForm.getId() + "/responses/" + response.getId();
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + reviewerToken);
    }

    /*
     * 기획안 한 벌. 시드의 qitemId 상수를 그대로 쓴다 — 계약 자체(문자열이 무엇인가)는
     * ProposalFormSeedTest가 리터럴로 못 박고, 여기서 검증하는 것은 그 계약을 읽는 쪽이다.
     */
    private static Map<String, Object> answers(String curriculum) {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put(ProposalFormSeed.QITEM_PROGRAM_TYPE, "스터디");
        answers.put(ProposalFormSeed.QITEM_PROGRAM_TITLE, "알고리즘 스터디");
        answers.put(ProposalFormSeed.QITEM_GOAL_CONTENT, "알고리즘 문제 풀이 근육을 만든다");
        answers.put(ProposalFormSeed.QITEM_PREP_CONTENT, "노트북");
        answers.put(ProposalFormSeed.QITEM_PERIOD_BEGIN_DATE, "2026-03-02");
        answers.put(ProposalFormSeed.QITEM_PERIOD_END_DATE, "2026-06-30");
        answers.put(ProposalFormSeed.QITEM_SCHEDULE_TEXT, "매주 화요일 19:00");
        answers.put(ProposalFormSeed.QITEM_CAPACITY_MIN_COUNT, "4");
        answers.put(ProposalFormSeed.QITEM_CAPACITY_MAX_COUNT, "12");
        answers.put(ProposalFormSeed.QITEM_PLACE_NAME, "전산관 401호");
        answers.put(ProposalFormSeed.QITEM_CURRICULUM, curriculum);
        return answers;
    }

    private FormResponseHistoryEntity saveResponse(
            MemberEntity proposer, Map<String, Object> answers) {
        FormResponseHistoryEntity response =
                formResponseHistoryRepository.save(
                        FormResponseHistoryEntity.createSubmitted(
                                proposalForm,
                                proposer,
                                ResponseContent.of(answers),
                                Instant.now()));
        entityManager.flush();
        return response;
    }

    private MemberEntity saveMember(String studentNumber, String name) {
        return saveMember(UUID.randomUUID(), studentNumber, name);
    }

    /*
     * 스텁 JwtDecoder가 토큰 문자열을 그대로 sub로 쓰므로, 인증이 필요한 회원은 authUserId를
     * 토큰과 같은 값으로 만든다 (다른 컨트롤러 테스트와 같은 방식).
     */
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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
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
