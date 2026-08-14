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
 * 이 이슈에서 새로 건 UNIQUE 제약 두 개가 실제로 DDL까지 내려가는지 확인한다.
 *
 * 두 제약 모두 "선조회로는 못 막는 동시 요청"을 막으려고 둔 것이라, 애플리케이션 코드가
 * 아니라 DB가 거절하는지를 봐야 의미가 있다. @Table(uniqueConstraints = ...)를 적어 두고도
 * ddl-auto가 만드는 스키마에 빠지는 실수를 여기서 잡는다.
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

        form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                creator,
                                "2026 신규모집 지원서",
                                new QuestionCompositionContent(List.of(), List.of()),
                                null,
                                null));
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
     * 한 회원이 한 폼에 두 행을 갖지 못한다 (#35 중복 제출 방지 · #36 자동 저장이 이어 쓸 행의 유일성).
     * 임시저장과 제출을 각각 다른 행으로 만들려는 시도도 여기에 걸린다 — 같은 행의 상태만 바뀐다.
     */
    @Test
    void rejectsTwoResponsesFromTheSameMemberOnTheSameForm() {
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
}
