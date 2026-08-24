package org.sscc.ssccopsserver.domain.form.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.global.config.JsonFormatMapperConfig;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 폼 도메인의 UNIQUE 제약이 실제로 DDL까지 내려가는지 확인한다.
 *
 * 제약들이 막는 것은 "선조회로는 못 막는 동시 요청"이라, 애플리케이션 코드가 아니라 DB가
 * 거절하는지를 봐야 의미가 있다. @Table(uniqueConstraints = ...)를 적어 두고도 ddl-auto가
 * 만드는 스키마에 빠지는 실수를 여기서 잡는다.
 *
 * uk_form_sys_form_cd(#140)는 다른 둘과 성격이 조금 다르다 — 동시 요청보다는 "코드가 가리키는
 * 폼은 환경당 하나"라는 사실 자체를 DB가 지키게 하는 쪽이고, NULL을 여러 개 허용한다는 전제에
 * 기대고 있어 그 전제까지 함께 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, JsonFormatMapperConfig.class})
class FormUniqueConstraintTest {

    @Autowired private FormRepository formRepository;
    @Autowired private FormLabelRepository formLabelRepository;
    @Autowired private FormLabelRelationRepository formLabelRelationRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberEntity creator;
    private FormEntity form;

    @BeforeEach
    void setUp() {
        creator =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260101",
                        "홍길동",
                        "20260101@soongsil.ac.kr");

        form = formRepository.saveAndFlush(newForm("2026 신규모집 지원서"));
    }

    // 같은 라벨을 같은 폼에 두 번 달면 상세에 라벨이 두 번 뜨고 라벨별 폼 수 집계가 부푼다
    @Test
    void rejectsTheSameLabelAttachedTwiceToTheSameForm() {
        FormLabelEntity label = formLabelRepository.saveAndFlush(FormLabelEntity.create("신규모집"));
        formLabelRelationRepository.saveAndFlush(FormLabelRelationEntity.create(form, label));

        assertThatThrownBy(
                        () ->
                                formLabelRelationRepository.saveAndFlush(
                                        FormLabelRelationEntity.create(form, label)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 다른 라벨이라면 같은 폼에 얼마든지 붙는다 — 제약이 N:M 자체를 막아 버리면 안 된다
    @Test
    void allowsDifferentLabelsOnTheSameForm() {
        FormLabelEntity recruiting =
                formLabelRepository.saveAndFlush(FormLabelEntity.create("신규모집"));
        FormLabelEntity semester = formLabelRepository.saveAndFlush(FormLabelEntity.create("1학기"));

        formLabelRelationRepository.saveAndFlush(FormLabelRelationEntity.create(form, recruiting));
        formLabelRelationRepository.saveAndFlush(FormLabelRelationEntity.create(form, semester));

        Assertions.assertThat(formLabelRelationRepository.findAllByForm(form)).hasSize(2);
    }

    /*
     * 한 회원이 한 폼에 **같은 응답 순번으로** 두 행을 갖지 못한다 (#143에서 제약이
     * (form_id, mbr_id) → (form_id, mbr_id, rspns_seq)로 옮겨졌다).
     *
     * 단일 응답 폼은 서버가 순번을 1로 고정하므로 두 번째 제출이 여기서 걸린다 — #35의 중복 제출
     * 방지와 #36의 "자동 저장이 이어 쓸 행의 유일성"이 그대로 이 제약에 얹혀 있다. 임시저장과
     * 제출을 각각 다른 행으로 만들려는 시도도 마찬가지다(같은 행의 상태만 바뀌어야 한다).
     */
    @Test
    void rejectsTwoResponsesWithTheSameSequenceFromTheSameMember() {
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(
                        form, creator, ResponseContent.of(Map.of("q1", "홍길동"))));

        assertThatThrownBy(
                        () ->
                                formResponseHistoryRepository.saveAndFlush(
                                        FormResponseHistoryEntity.createSubmitted(
                                                form,
                                                creator,
                                                ResponseContent.of(Map.of("q1", "홍길동")),
                                                Instant.parse("2026-03-10T12:00:00Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /*
     * 순번이 다르면 같은 회원의 응답이 여러 건 쌓인다 (#143 · 다중 응답 폼).
     *
     * 제약을 옮긴 것이지 없앤 것이 아니라는 사실을 양쪽에서 고정해 둔다 — 위 테스트만 있으면
     * 제약을 그대로 두고도 통과하고, 이 테스트만 있으면 통째로 지워도 통과한다.
     */
    @Test
    void allowsAnotherResponseFromTheSameMemberWithTheNextSequence() {
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        form,
                        creator,
                        ResponseContent.of(Map.of("q1", "첫 번째 제안")),
                        Instant.parse("2026-03-10T12:00:00Z"),
                        1));
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        form,
                        creator,
                        ResponseContent.of(Map.of("q1", "두 번째 제안")),
                        Instant.parse("2026-03-11T12:00:00Z"),
                        2));

        Assertions.assertThat(
                        formResponseHistoryRepository
                                .findAllByFormAndMemberOrderByResponseSequenceAsc(form, creator))
                .extracting(FormResponseHistoryEntity::getResponseSequence)
                .containsExactly(1, 2);
    }

    /*
     * 다음 순번의 근거는 지금 있는 행 수가 아니라 **마지막 순번**이다. 지워진 응답이 있으면
     * 세는 방식은 이미 쓴 번호를 다시 배정해 UNIQUE에 걸린다.
     */
    @Test
    void lastResponseSequenceIsZeroWhenNoResponseExists() {
        Assertions.assertThat(
                        formResponseHistoryRepository.findLastResponseSequence(form, creator))
                .isZero();

        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(
                        form, creator, ResponseContent.of(Map.of("q1", "홍길동")), 3));

        Assertions.assertThat(
                        formResponseHistoryRepository.findLastResponseSequence(form, creator))
                .isEqualTo(3);
    }

    /*
     * 코드가 가리키는 폼은 환경당 하나다 (#140). 둘이면 findBySystemFormCode가 어느 쪽을
     * 돌려줄지 알 수 없고, 잠금이 걸린 폼과 코드가 실제로 읽는 폼이 갈릴 수 있다.
     */
    @Test
    void rejectsTwoFormsWithTheSameSystemFormCode() {
        form.designateAsSystemForm("PROPOSAL");
        formRepository.saveAndFlush(form);

        FormEntity another = newForm("또 하나의 시스템 폼");
        another.designateAsSystemForm("PROPOSAL");

        assertThatThrownBy(() -> formRepository.saveAndFlush(another))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /*
     * 시스템 폼이 아닌 폼은 sys_form_cd가 NULL이며 얼마든지 쌓인다 — PostgreSQL·H2 모두 UNIQUE가
     * NULL을 여러 개 허용한다는 사실에 기대고 있으므로, 그 전제가 깨지면 평범한 폼을 두 개째
     * 만들 수 없게 된다. 실제로 확인해 둔다.
     */
    @Test
    void allowsManyFormsWithoutASystemFormCode() {
        formRepository.saveAndFlush(newForm("평범한 폼 1"));
        formRepository.saveAndFlush(newForm("평범한 폼 2"));

        Assertions.assertThat(formRepository.count()).isEqualTo(3);
    }

    // 코드가 폼을 찾는 유일한 경로. form_id는 IDENTITY라 환경마다 다르므로 코드가 가리킬 수 없다
    @Test
    void findsFormBySystemFormCode() {
        form.designateAsSystemForm("PROPOSAL");
        formRepository.saveAndFlush(form);

        Assertions.assertThat(formRepository.findBySystemFormCode("PROPOSAL"))
                .get()
                .extracting(FormEntity::getId)
                .isEqualTo(form.getId());
        Assertions.assertThat(formRepository.findBySystemFormCode("UNKNOWN")).isEmpty();
    }

    // 다른 회원의 응답은 같은 폼에 얼마든지 쌓인다
    @Test
    void allowsResponsesFromDifferentMembersOnTheSameForm() {
        MemberEntity other =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260102",
                        "김철수",
                        "20260102@soongsil.ac.kr");

        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(form, creator, null));
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(form, other, null));

        Assertions.assertThat(formResponseHistoryRepository.count()).isEqualTo(2);
    }

    private FormEntity newForm(String title) {
        return FormEntity.create(
                creator, title, new QuestionCompositionContent(List.of(), List.of()), null, null);
    }
}
