package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseReviewHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeeder;
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

/*
 * 이관이 실패하면 승인 자체가 없던 일이 되어야 한다 (#150 · ssccops#148 BR).
 *
 * "승인은 됐는데 활동이 없는" 응답이 남으면 되돌릴 방법이 없다 — ACCEPTED는 종결 상태라
 * 다시 승인할 수도, 반려로 되돌릴 수도 없다(#141). 그래서 이관 실패는 조용한 건너뜀이 아니라
 * 승인 실패다.
 *
 * ── @Transactional을 걸 수 없다 ────────────────────────────────
 * 테스트에 트랜잭션을 걸면 실제 커밋·롤백이 일어나지 않아 이 규칙을 검증할 수 없다. 롤백된 척만
 * 하는 영속성 컨텍스트에는 바뀐 상태가 그대로 남아 있어 테스트가 초록인 채 결함이 살아 있게 된다
 * (FormResponseReviewRollbackTest·MemberChangeRollbackTest와 같은 이유).
 *
 * ── 그래서 DB를 따로 쓴다 ──────────────────────────────────────
 * 커밋한 회원·폼·응답이 남으므로 공용 H2(testdb)를 쓰면 "회원이 한 명도 없는 상태"를 전제하는
 * 부트스트랩 테스트가 실행 순서에 따라 깨진다. URL을 바꿔 이 클래스만의 DB를 띄운다.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:proposal-migration-rollback;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
class ProposalMigrationRollbackTest {

    private static final UUID REVIEWER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;

    @Autowired private ProposalFormSeeder proposalFormSeeder;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FormResponseReviewHistoryRepository formResponseReviewHistoryRepository;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @Autowired private EventRepository eventRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;

    private Long formId;
    private Long formResponseId;

    @BeforeEach
    void setUp() {
        MemberEntity reviewer = saveMember(REVIEWER, "20200001", "김검토", "reviewer@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                reviewer,
                MemberRoleFixture.DIRECTOR);

        proposalFormSeeder.seed();
        FormEntity form =
                formRepository
                        .findBySystemFormCode(ProposalFormSeed.SYSTEM_FORM_CODE)
                        .orElseThrow();
        formId = form.getId();

        MemberEntity proposer =
                saveMember(UUID.randomUUID(), "20260001", "이제안", "proposer@sscc.org");
        formResponseId =
                formResponseHistoryRepository
                        .saveAndFlush(
                                FormResponseHistoryEntity.createSubmitted(
                                        form,
                                        proposer,
                                        ResponseContent.of(brokenCurriculumAnswers()),
                                        Instant.now()))
                        .getId();
    }

    @Test
    void acceptIsRolledBackWhenMigrationFails() throws Exception {
        long eventsBefore = eventRepository.count();

        mockMvc.perform(
                        post("/v1/forms/" + formId + "/responses/" + formResponseId + "/reviews")
                                .header("Authorization", "Bearer " + REVIEWER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"rspnsSttsCd\": \"ACCEPTED\","
                                                + " \"rvwOpnnCn\": \"좋은 기획입니다.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROPOSAL_MIGRATION_FAILED"));

        /*
         * 트랜잭션 밖에서 다시 읽는다. 상태가 SUBMITTED 그대로여야 검토자가 수정요청으로
         * 다시 처리할 수 있다 — ACCEPTED로 굳었다면 그 응답은 영원히 활동 없는 승인으로 남는다.
         */
        FormResponseHistoryEntity response =
                formResponseHistoryRepository.findById(formResponseId).orElseThrow();
        assertThat(response.getStatus()).isEqualTo(ResponseStatus.SUBMITTED);

        // 검토 이력도 남지 않는다 — 일어나지 않은 승인의 기록이 타임라인에 쌓이면 안 된다
        assertThat(
                        formResponseReviewHistoryRepository
                                .findAllByResponseOrderByProcessedAtAscIdAsc(response))
                .isEmpty();

        // 절반만 만들어진 것도 없다
        assertThat(eventRepository.count()).isEqualTo(eventsBefore);
        assertThat(academicProgramRepository.existsByFormResponse(response)).isFalse();
    }

    /*
     * 커리큘럼만 형식이 어긋난 기획안. 나머지 문항은 전부 정상이라 이 400의 원인이 파싱 하나임이
     * 분명하다 — 여러 곳이 동시에 틀린 표본을 쓰면 무엇이 롤백을 일으켰는지 알 수 없다.
     */
    private static Map<String, Object> brokenCurriculumAnswers() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put(ProposalFormSeed.QITEM_PROGRAM_TYPE, "스터디");
        answers.put(ProposalFormSeed.QITEM_PROGRAM_TITLE, "알고리즘 스터디");
        answers.put(ProposalFormSeed.QITEM_GOAL_CONTENT, "알고리즘 문제 풀이 근육을 만든다");
        answers.put(ProposalFormSeed.QITEM_PERIOD_BEGIN_DATE, "2026-03-02");
        answers.put(ProposalFormSeed.QITEM_PERIOD_END_DATE, "2026-06-30");
        answers.put(ProposalFormSeed.QITEM_CURRICULUM, "1회차 오리엔테이션");
        return answers;
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
}
