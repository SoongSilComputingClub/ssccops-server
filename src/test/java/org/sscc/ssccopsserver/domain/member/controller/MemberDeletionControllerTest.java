package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseReviewHistoryRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 회원 하드 삭제 (#361 · ADR-0021) — DELETE /v1/members/{memberId} ·
 * GET /v1/members/{memberId}/deletion-preview.
 *
 * ── 이 클래스가 못 박는 것 ─────────────────────────────────────
 *  1. 응답·참가·이력이 있는 TEMP 회원을 지우면 **그것들이 실제로 사라진다** — 코드가 아니라
 *     DB cascade가 지우므로, 엔티티의 @OnDelete가 H2에 같은 제약을 만들었는지를 이 테스트가
 *     DB로 확인한다(빠지면 삭제가 FK로 막혀 409가 난다).
 *  2. 폼 작성자는 못 지운다 — 409 + 메시지에 «폼 작성자». 미리보기의 blockedBy도 같은 문구다.
 *  3. 본인 400 · 없는 회원 404 NOT_FOUND · 권한 없음 403.
 *  4. 미리보기 숫자가 맞고, **본인이 처리자인 제출 이력은 막는 것으로 세지 않는다**.
 *
 * 플래그 off의 404 FEATURE_DISABLED는 여기 없다 — application-test.yaml이 켜 둔 공용 컨텍스트라
 * 끄려면 컨텍스트를 하나 더 띄워야 하고, 그 판정은 컨텍스트 없이 확인된다
 * (MemberDeletionServiceImplTest).
 *
 * ── 왜 삭제 요청 앞뒤로 clear 하는가 ─────────────────────────────
 * 앞: 픽스처가 같은 세션에 관리 상태로 남아 지워지는 회원을 참조하면 Hibernate가 DELETE를
 * 내보내기 전에 flush를 거절한다(detachFixtures 주석). 뒤: cascade는 DB가 하고 Hibernate는
 * 모르므로, 응답·참가 엔티티가 1차 캐시에 살아 있으면 findById가 DB를 보지 않고 그것을
 * 돌려준다 — 지워졌는지는 캐시를 비운 뒤 물어야 한다.
 *
 * **실패하는 요청은 테스트 하나에 하나뿐이다** (MemberUpdateControllerTest와 같은 이유).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class MemberDeletionControllerTest {

    private static final String MEMBERS = "/v1/members";
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID PLAIN_MEMBER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-09-11T03:00:00Z");

    @PersistenceContext private EntityManager entityManager;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FormResponseReviewHistoryRepository formResponseReviewHistoryRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;

    private MemberEntity manager;
    private MemberEntity target;

    @BeforeEach
    void setUp() {
        manager = saveMember(MANAGER, "20200001", "김도현");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.MEMBER_MANAGE);
        saveMember(PLAIN_MEMBER, "20200002", "이서연");

        // 연동 실패로 생긴 새 계정의 모양 — TEMP 등급(MemberFixture 기본값), 역할 없음
        target = saveMember(UUID.randomUUID(), "20200003", "박준호");
    }

    /* ── 삭제 ───────────────────────────────────────────── */

    /*
     * 응답 2건(그중 1건은 검토까지 끝났다) · 그 응답으로 등록된 참가 1건 · 등급/상태 이력 —
     * 전부 사라지고 회원 행도 없다. 검토 이력에는 처리자가 본인인 제출 행과 관리자인 승인 행이
     * 섞여 있는데 둘 다 응답에 딸려 지워지고, 관리자는 그대로 남는다.
     */
    @Test
    void deletingTempMemberRemovesResponsesParticipationsAndHistories() throws Exception {
        FormEntity form = saveForm(manager, "신입 모집");
        FormResponseHistoryEntity accepted = saveSubmittedResponse(form, target);
        reviewSubmit(accepted, target);
        accepted.review(ResponseStatus.ACCEPTED);
        formResponseReviewHistoryRepository.saveAndFlush(
                FormResponseReviewHistoryEntity.record(
                        accepted, ResponseReviewAction.ACCEPT, manager, null, AT));
        FormResponseHistoryEntity pending = saveSubmittedResponse(form, target, 2);
        reviewSubmit(pending, target);

        EventEntity event = saveEvent(manager, form);
        Long participantId =
                eventParticipantRepository
                        .saveAndFlush(
                                EventParticipantEntity.register(
                                        event,
                                        target,
                                        EventParticipantStatus.CONFIRMED,
                                        accepted,
                                        manager))
                        .getId();
        saveHistories(target, manager);

        Long targetId = target.getId();
        Long acceptedId = accepted.getId();
        Long pendingId = pending.getId();

        detachFixtures();
        mockMvc.perform(authorized(delete(MEMBERS + "/" + targetId), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        entityManager.clear();

        assertThat(memberRepository.findById(targetId)).isEmpty();
        assertThat(formResponseHistoryRepository.findById(acceptedId)).isEmpty();
        assertThat(formResponseHistoryRepository.findById(pendingId)).isEmpty();
        assertThat(eventParticipantRepository.findById(participantId)).isEmpty();
        assertThat(countReviewRowsOf(acceptedId) + countReviewRowsOf(pendingId)).isZero();
        assertThat(memberGradeHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(targetId))
                .isEmpty();
        assertThat(memberStatusHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(targetId))
                .isEmpty();

        // 남의 것은 그대로다 — 폼·행사와 검토자였던 관리자
        assertThat(formRepository.findById(form.getId())).isPresent();
        assertThat(eventRepository.findById(event.getId())).isPresent();
        assertThat(memberRepository.findById(manager.getId())).isPresent();
    }

    /*
     * 폼을 만든 회원은 못 지운다. DB의 NO ACTION FK가 막고 서비스가 409로 옮기며, 메시지에
     * 어느 참조인지가 사람 표기로 실린다 — 운영진이 무엇을 정리해야 하는지 알아야 한다.
     */
    @Test
    void formCreatorCannotBeDeleted() throws Exception {
        saveForm(target, "박준호가 만든 폼");

        detachFixtures();
        mockMvc.perform(authorized(delete(MEMBERS + "/" + target.getId()), MANAGER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_REFERENCED"))
                .andExpect(jsonPath("$.message", containsString("폼 작성자")));
    }

    @Test
    void deletingSelfIs400() throws Exception {
        mockMvc.perform(authorized(delete(MEMBERS + "/" + manager.getId()), MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_DELETE_SELF"));

        assertThat(memberRepository.findById(manager.getId())).isPresent();
    }

    /** 없는 회원의 404는 NOT_FOUND다 — 플래그 off의 FEATURE_DISABLED와 코드가 다르다 */
    @Test
    void unknownMemberIs404NotFound() throws Exception {
        mockMvc.perform(authorized(delete(MEMBERS + "/999999"), MANAGER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deletionRequiresMemberManage() throws Exception {
        mockMvc.perform(authorized(delete(MEMBERS + "/" + target.getId()), PLAIN_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(memberRepository.findById(target.getId())).isPresent();
    }

    /* ── 미리보기 ───────────────────────────────────────── */

    /*
     * 응답 2 · 참가 1 · 이력 2(등급 1 + 상태 1). 제출 이력의 처리자가 본인인 행은 blockedBy에
     * 세지 않는다 — 그 행은 응답에 딸려 함께 지워지므로 삭제를 막지 않는다.
     */
    @Test
    void previewCountsOwnDataAndIgnoresSelfProcessedReviews() throws Exception {
        FormEntity form = saveForm(manager, "신입 모집");
        FormResponseHistoryEntity first = saveSubmittedResponse(form, target);
        reviewSubmit(first, target);
        FormResponseHistoryEntity second = saveSubmittedResponse(form, target, 2);
        reviewSubmit(second, target);
        EventEntity event = saveEvent(manager, form);
        eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(
                        event, target, EventParticipantStatus.WAITLISTED, first, manager));
        saveHistories(target, manager);

        mockMvc.perform(
                        authorized(
                                get(MEMBERS + "/" + target.getId() + "/deletion-preview"), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseCount").value(2))
                .andExpect(jsonPath("$.data.participationCount").value(1))
                .andExpect(jsonPath("$.data.historyCount").value(2))
                .andExpect(jsonPath("$.data.blockedBy").isArray())
                .andExpect(jsonPath("$.data.blockedBy.length()").value(0));
    }

    /** 폼 작성자 + 남의 응답의 검토자 — 둘 다 blockedBy에 실리고 문구는 409의 것과 같다 */
    @Test
    void previewListsWhatBlocksDeletion() throws Exception {
        saveForm(target, "박준호가 만든 폼");
        FormEntity othersForm = saveForm(manager, "관리자가 만든 폼");
        MemberEntity other = saveMember(UUID.randomUUID(), "20200004", "최민수");
        FormResponseHistoryEntity othersResponse = saveSubmittedResponse(othersForm, other);
        formResponseReviewHistoryRepository.saveAndFlush(
                FormResponseReviewHistoryEntity.record(
                        othersResponse, ResponseReviewAction.ACCEPT, target, null, AT));

        mockMvc.perform(
                        authorized(
                                get(MEMBERS + "/" + target.getId() + "/deletion-preview"), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseCount").value(0))
                .andExpect(jsonPath("$.data.blockedBy.length()").value(2))
                .andExpect(jsonPath("$.data.blockedBy[0]").value("폼 작성자"))
                .andExpect(jsonPath("$.data.blockedBy[1]").value("폼 응답 검토자"));
    }

    @Test
    void previewOfUnknownMemberIs404NotFound() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS + "/999999/deletion-preview"), MANAGER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void previewRequiresMemberManage() throws Exception {
        mockMvc.perform(
                        authorized(
                                get(MEMBERS + "/" + target.getId() + "/deletion-preview"),
                                PLAIN_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    /*
     * 삭제 요청 전에 1차 캐시를 비운다. 운영에서는 DELETE 요청이 자기 세션에서 회원 하나만
     * 읽고 지우므로 다른 엔티티가 그 회원을 물고 있을 일이 없다. 그런데 이 테스트는 픽스처를
     * 같은 트랜잭션·같은 세션에서 만들어, 응답·폼 엔티티가 지워지는 회원을 참조한 채 관리
     * 상태로 남는다 — 그러면 Hibernate가 DELETE를 내보내기도 전에 "삭제된 인스턴스를 참조한다"
     * (TransientObjectException)로 flush를 거절해 FK 판정이 DB에 닿지 않는다. 비우면 운영과
     * 같은 모양이 된다.
     */
    private void detachFixtures() {
        entityManager.flush();
        entityManager.clear();
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId);
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

    private FormEntity saveForm(MemberEntity creator, String title) {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new Page("기본 정보", null)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "이름",
                                        null,
                                        QuestionItemType.SHORT_TEXT,
                                        true,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        return formRepository.saveAndFlush(
                FormEntity.create(creator, title, composition, null, null, FormStatus.OPEN, true));
    }

    private FormResponseHistoryEntity saveSubmittedResponse(
            FormEntity form, MemberEntity respondent) {
        return saveSubmittedResponse(form, respondent, 1);
    }

    /** 같은 폼에 두 번째 응답을 내려면 순번이 달라야 한다 (uk_form_rspns_hstry_form_member_seq) */
    private FormResponseHistoryEntity saveSubmittedResponse(
            FormEntity form, MemberEntity respondent, int responseSequence) {
        return formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        form,
                        respondent,
                        ResponseContent.of(Map.of("q1", "홍길동")),
                        AT,
                        responseSequence));
    }

    /** 제출 행의 처리자는 응답자 본인이다 (FormResponseReviewHistoryEntity 주석) */
    private void reviewSubmit(FormResponseHistoryEntity response, MemberEntity respondent) {
        formResponseReviewHistoryRepository.saveAndFlush(
                FormResponseReviewHistoryEntity.record(
                        response, ResponseReviewAction.SUBMIT, respondent, null, AT));
    }

    private EventEntity saveEvent(MemberEntity creator, FormEntity form) {
        return eventRepository.saveAndFlush(
                EventEntity.create(
                        eventClassificationRepository.findById("EVENT").orElseThrow(),
                        creator,
                        "개강총회",
                        "본문",
                        null,
                        form,
                        null,
                        null,
                        null,
                        null));
    }

    /** 등급·상태 이력 한 줄씩. 변경자는 관리자다 — 그 참조는 본인 데이터 행이라 삭제를 막지 않는다 */
    private void saveHistories(MemberEntity member, MemberEntity changer) {
        memberGradeHistoryRepository.saveAndFlush(
                MemberGradeHistoryEntity.create(
                        member,
                        null,
                        memberGradeRepository.findById(MemberGradeCode.TEMP.code()).orElseThrow(),
                        LocalDate.of(2026, 9, 11),
                        "가입",
                        changer));
        memberStatusHistoryRepository.saveAndFlush(
                MemberStatusHistoryEntity.create(
                        member,
                        null,
                        member.getMembershipStatus(),
                        LocalDate.of(2026, 9, 11),
                        null,
                        "가입",
                        changer));
    }

    private long countReviewRowsOf(Long responseId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(*) FROM form_rspns_rvw_hstry WHERE"
                                                + " form_rspns_id = :id")
                                .setParameter("id", responseId)
                                .getSingleResult())
                .longValue();
    }
}
