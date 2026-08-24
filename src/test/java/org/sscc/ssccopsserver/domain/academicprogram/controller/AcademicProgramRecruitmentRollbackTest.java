package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 선발 확정은 나눌 수 없는 한 건이다 (#138).
 *
 * 한 줄이 실패하면 앞줄의 심사(ACCEPTED)와 등록(event_ptcp)이 함께 되돌아가야 한다. 되돌아가지
 * 않으면 "수락됐는데 팀원이 아닌" 신청자와 "명단에는 있는데 선발 화면에서는 미처리로 보이는"
 * 신청자가 남고, 운영자는 어느 줄이 반영됐는지를 화면을 뒤져 되짚어야 한다.
 *
 * ── @Transactional을 걸 수 없다 ────────────────────────────────
 * 테스트에 트랜잭션을 걸면 실제 커밋·롤백이 일어나지 않아 이 규칙을 검증할 수 없다 — 서비스의
 * 트랜잭션이 테스트 것에 참여하기만 하므로 rollback-only 표시만 남고, 영속성 컨텍스트에는 바뀐
 * 상태가 그대로 있어 테스트가 초록인 채 결함이 살아 있게 된다
 * (FormResponseReviewRollbackTest·MemberChangeRollbackTest와 같은 이유).
 *
 * ── 그래서 DB를 따로 쓴다 ──────────────────────────────────────
 * 커밋한 회원·활동·폼이 남으므로 공용 H2(testdb)를 쓰면 "회원이 한 명도 없는 상태"를 전제하는
 * 부트스트랩 테스트가 실행 순서에 따라 깨진다. URL을 바꿔 이 클래스만의 DB를 띄운다.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:academic-recruitment-rollback;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramRecruitmentRollbackTest.StubJwtDecoderConfig.class)
class AcademicProgramRecruitmentRollbackTest {

    private static final UUID MANAGER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;

    private AcademicProgramEntity recruiting;
    private Long validResponseId;

    @BeforeEach
    void setUp() throws Exception {
        MemberEntity manager = saveMember(MANAGER, "20260801", "학술국장");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        recruiting =
                AcademicProgramFixture.save(
                        eventRepository,
                        eventClassificationRepository,
                        academicProgramRepository,
                        academicProgramTypeRepository,
                        curriculumItemRepository,
                        "STUDY",
                        "롤백 확인 스터디",
                        manager,
                        List.of("OT"));

        FormEntity form = linkForm(recruiting, manager);
        startRecruitment(recruiting);

        MemberEntity applicant = saveMember(UUID.randomUUID(), "20260802", "지원자1");
        validResponseId =
                formResponseHistoryRepository
                        .saveAndFlush(
                                FormResponseHistoryEntity.createSubmitted(
                                        form,
                                        applicant,
                                        ResponseContent.of(Map.of("q1", "지원 동기입니다")),
                                        Instant.now()))
                        .getId();
    }

    /*
     * 앞줄은 성립하고 뒷줄만 없는 응답을 가리킨다. 뒷줄이 404로 끊길 때 앞줄의 심사·등록이
     * 남아 있으면 안 된다 — 트랜잭션 밖에서 다시 읽어 확인한다.
     */
    @Test
    void selectIsRolledBackWhenOneSelectionFails() throws Exception {
        mockMvc.perform(
                        post("/v1/academic-programs/" + recruiting.getId() + "/recruitment/select")
                                .header("Authorization", "Bearer " + MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"selections": [
                                          {"formRspnsId": %d, "ptcpSttsCd": "CONFIRMED"},
                                          {"formRspnsId": 999999, "ptcpSttsCd": "CONFIRMED"}
                                        ]}
                                        """
                                                .formatted(validResponseId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));

        assertThat(
                        formResponseHistoryRepository
                                .findById(validResponseId)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(ResponseStatus.SUBMITTED);
        assertThat(
                        eventParticipantRepository.findAllByEventAndStatusInOrderByIdAsc(
                                eventRepository
                                        .findById(recruiting.getEvent().getId())
                                        .orElseThrow(),
                                List.of(
                                        EventParticipantStatus.CONFIRMED,
                                        EventParticipantStatus.WAITLISTED,
                                        EventParticipantStatus.CANCELLED)))
                .isEmpty();
    }

    // ------------------------------------------------------------------ 헬퍼

    private FormEntity linkForm(AcademicProgramEntity program, MemberEntity creator) {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new Page("기본 정보", null)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "지원 동기",
                                        QuestionItemType.LONG_TEXT,
                                        true,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                creator, "롤백 확인 모집 폼", composition, null, null, FormStatus.DRAFT));

        EventEntity event = eventRepository.findById(program.getEvent().getId()).orElseThrow();
        event.linkForm(form);
        eventRepository.saveAndFlush(event);
        return form;
    }

    private void startRecruitment(AcademicProgramEntity program) throws Exception {
        mockMvc.perform(
                        post("/v1/academic-programs/" + program.getId() + "/transitions")
                                .header("Authorization", "Bearer " + MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"transition\": \"START_RECRUITMENT\"}"))
                .andExpect(status().isOk());
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
