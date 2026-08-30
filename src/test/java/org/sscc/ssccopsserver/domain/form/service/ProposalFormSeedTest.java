package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.global.config.JsonFormatMapperConfig;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 기획안 시스템 폼 시드 검증 (#173).
 *
 * **시드가 깨진 채 배포되면 폼을 열 수 없다.** 문항 구성이 스스로 모순돼 있으면
 * QuestionCompositionValidator가 저장을 거절하고, 계약(SystemFormContract)이 요구하는 qitemId가
 * 구성에 없으면 운영진이 문구 한 줄만 고쳐도 400 SYSTEM_FORM_CONTRACT_VIOLATION이 난다 —
 * 둘 다 배포 후 운영진의 첫 조작에서야 드러나는 종류라 여기서 미리 확인한다.
 *
 * 기대값을 상수가 아니라 **문자열 리터럴로 적는다.** qitemId는 응답(rspns_cn)의 key이자
 * 이관(#150)·화면이 함께 쓰는 계약이라, 상수를 고쳤을 때 테스트가 따라 바뀌어 조용히 통과하면
 * 안 된다(CodeSeedDataTest·EventSeedDataTest와 같은 태도).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaAuditingConfig.class,
    JsonFormatMapperConfig.class,
    QuestionCompositionValidator.class,
    ProposalFormSeeder.class
})
class ProposalFormSeedTest {

    @Autowired private ProposalFormSeeder proposalFormSeeder;
    @Autowired private QuestionCompositionValidator questionCompositionValidator;
    @Autowired private FormRepository formRepository;
    @Autowired private FormLabelRepository formLabelRepository;
    @Autowired private FormLabelRelationRepository formLabelRelationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;

    /*
     * 시드 구성이 저장 경로의 검증을 통과해야 한다. 통과하는 것만으로는 부족해서 **정규화 결과가
     * 원본과 같은지**까지 본다 — 다르면 첫 편집에서 구성이 '바뀐 것'으로 판정돼(FormEntity.update의
     * record equals) 아무도 손대지 않은 문항에 qitem_ver 2와 이력 한 줄이 붙는다.
     */
    @Test
    void seedCompositionPassesTheValidatorUnchanged() {
        QuestionCompositionContent composition = ProposalFormSeed.composition();

        assertThatCode(() -> questionCompositionValidator.validate(composition))
                .doesNotThrowAnyException();
        assertThat(questionCompositionValidator.validate(composition)).isEqualTo(composition);
    }

    /*
     * qitemId 표 그대로 — 순서까지 못 박는다. 응답 화면이 이 순서로 그려지고, 순서가 바뀌면
     * 기획안을 쓰는 사람이 보는 흐름(무엇을 왜 하는지 → 언제 → 누구와 → 어떻게)이 흐트러진다.
     */
    @Test
    void declaresEveryQitemIdOfTheApprovedQuestionSet() {
        assertThat(ProposalFormSeed.composition().qitems())
                .extracting(QuestionItem::qitemId, QuestionItem::qitemTypeCd, QuestionItem::reqYn)
                .containsExactly(
                        tuple("programType", QuestionItemType.SINGLE_CHOICE, true),
                        tuple("programTitle", QuestionItemType.SHORT_TEXT, true),
                        tuple("goalContent", QuestionItemType.LONG_TEXT, true),
                        tuple("prepContent", QuestionItemType.LONG_TEXT, false),
                        tuple("periodBeginDate", QuestionItemType.DATE, true),
                        tuple("periodEndDate", QuestionItemType.DATE, true),
                        tuple("scheduleText", QuestionItemType.SHORT_TEXT, false),
                        tuple("capacityMinCount", QuestionItemType.SHORT_TEXT, false),
                        tuple("capacityMaxCount", QuestionItemType.SHORT_TEXT, false),
                        tuple("placeName", QuestionItemType.SHORT_TEXT, false),
                        tuple("curriculum", QuestionItemType.LONG_TEXT, true));
    }

    /*
     * 계약은 "없으면 이관이 성립하지 않는 문항"만 담는다. 여섯 개를 리터럴로 못 박아 두는 것은
     * 나중에 선택 문항을 하나씩 밀어 넣어 폼 전체가 잠기는 것을 막기 위해서다 — 그렇게 되면
     * 운영진은 회차마다 문구 한 줄도 고칠 수 없다.
     */
    @Test
    void locksOnlyTheQuestionsWhoseAbsenceBreaksTheMigration() {
        assertThat(new SystemFormContract().requiredQitemIdsOf("PROPOSAL"))
                .containsExactlyInAnyOrder(
                        "programType",
                        "programTitle",
                        "goalContent",
                        "periodBeginDate",
                        "periodEndDate",
                        "curriculum");
    }

    /*
     * 목록이 기획안을 알아보는 값은 활동명이다 (#196). 선언이 다른 문항으로 바뀌면 검토·제출 현황
     * 목록의 제목이 통째로 달라지므로, 그 값을 여기서 못 박는다.
     *
     * **대표 문항은 잠긴 문항이어야 한다** — 그렇지 않으면 운영진이 편집 화면에서 그것을 지우는
     * 순간 제목이 조용히 사라진다(SystemFormContract 생성자가 기동에서 세우는 규칙이며, 여기서는
     * PROPOSAL의 선언이 실제로 그 조건을 지키는지 본다).
     */
    @Test
    void declaresTheProgramTitleAsTheProposalListTitle() {
        SystemFormContract contract = new SystemFormContract();

        assertThat(contract.titleQitemIdOf("PROPOSAL"))
                .contains(ProposalFormSeed.QITEM_PROGRAM_TITLE);
        assertThat(contract.requiredQitemIdsOf("PROPOSAL"))
                .contains(ProposalFormSeed.QITEM_PROGRAM_TITLE);
        assertThat(QuestionCompositionContent.qitemIdsOf(ProposalFormSeed.composition()))
                .contains(ProposalFormSeed.QITEM_PROGRAM_TITLE);
    }

    /** 계약이 요구하는 문항이 시드에 없으면 세우자마자 자기 계약을 어긴 폼이 된다 */
    @Test
    void seedCompositionContainsEveryContractedQitemId() {
        assertThat(QuestionCompositionContent.qitemIdsOf(ProposalFormSeed.composition()))
                .containsAll(new SystemFormContract().requiredQitemIdsOf("PROPOSAL"));
    }

    /*
     * 유형 선택지는 acdm_actv_type의 type_nm과 **글자까지** 같아야 한다. 응답은 문자열로
     * 저장되고 이관(#150)이 그것을 코드로 되돌리므로, 기준정보에서 이름을 바꾸면 매핑이 끊긴다 —
     * 그 사실이 배포 전에 드러나는 자리가 여기다.
     */
    @Test
    void programTypeOptionsMatchTheAcademicProgramTypeNamesCharacterForCharacter() {
        List<String> referenceNames =
                academicProgramTypeRepository.findAllByOrderByDisplayOrderAsc().stream()
                        .map(AcademicProgramTypeEntity::getName)
                        .toList();

        assertThat(referenceNames).containsExactly("스터디", "프로젝트");
        assertThat(ProposalFormSeed.PROGRAM_TYPE_OPTIONS).isEqualTo(referenceNames);
        assertThat(
                        ProposalFormSeed.composition().qitems().stream()
                                .filter(qitem -> "programType".equals(qitem.qitemId()))
                                .findFirst()
                                .orElseThrow()
                                .optionList())
                .isEqualTo(referenceNames);
    }

    /*
     * 제출자가 읽는 안내가 곧 파서의 명세다 (#173). 문항 문구에 포맷이 그대로 들어 있지 않으면
     * 안내와 파서가 갈리고, 갈린 순간 제출자는 안내대로 적었는데 승인이 막힌다.
     */
    @Test
    void curriculumQuestionSpellsOutTheLineFormatItsParserExpects() {
        String label =
                ProposalFormSeed.composition().qitems().stream()
                        .filter(qitem -> "curriculum".equals(qitem.qitemId()))
                        .findFirst()
                        .orElseThrow()
                        .qitemLblNm();

        assertThat(ProposalFormSeed.CURRICULUM_LINE_FORMAT).isEqualTo("1회차 | 주제 | 2026-03-05");
        assertThat(label).contains("1회차 | 주제 | 2026-03-05").contains("생략");
    }

    /*
     * 회원이 없는 환경에서는 세우지 않는다 — form.creatr_mbr_id가 NOT NULL이라 명의로 세울
     * 사람이 없다. 조용히 실패하는 대신 건너뛰고 다음 기동에서 다시 시도한다.
     */
    @Test
    void doesNothingWhileNoMemberExists() {
        proposalFormSeeder.seed();

        assertThat(formRepository.findBySystemFormCode("PROPOSAL")).isEmpty();
    }

    /*
     * 세워진 폼의 성질 — 접수는 닫혀 있고(DRAFT) 기간은 비어 있으며(운영진이 회차마다 정한다)
     * 한 사람이 여러 건을 낼 수 있다(#143). 시스템 폼 표시와 라벨도 함께 확인한다.
     */
    @Test
    void seedsAClosedMultiResponseSystemFormWithTheLabelAttached() {
        givenFirstMember();

        proposalFormSeeder.seed();

        FormEntity form = formRepository.findBySystemFormCode("PROPOSAL").orElseThrow();
        assertThat(form.getStatus()).isEqualTo(FormStatus.DRAFT);
        assertThat(form.getReceiptBeginAt()).isNull();
        assertThat(form.getReceiptEndAt()).isNull();
        assertThat(form.isSystemForm()).isTrue();
        assertThat(form.isMultipleResponseAllowed()).isTrue();
        assertThat(form.getQuestionVersion()).isEqualTo(1);
        assertThat(form.getQuestionComposition()).isEqualTo(ProposalFormSeed.composition());
        assertThat(formLabelRelationRepository.findAllByForm(form))
                .singleElement()
                .satisfies(relation -> assertThat(relation.getLabel().getName()).isEqualTo("기획안"));
    }

    /*
     * 멱등 — 다시 돌려도 폼도 라벨도 늘지 않는다. 시드는 매 기동마다 실행되므로 이것이 깨지면
     * 재시작 한 번에 기획안 폼이 두 개가 되고, 그때는 uk_form_sys_form_cd에 걸려 기동이 죽는다.
     */
    @Test
    void reRunningTheSeedChangesNothing() {
        givenFirstMember();
        proposalFormSeeder.seed();

        proposalFormSeeder.seed();
        proposalFormSeeder.seed();

        assertThat(formRepository.count()).isEqualTo(1);
        assertThat(formLabelRepository.findAllByOrderByNameAsc()).hasSize(1);
        assertThat(formLabelRelationRepository.count()).isEqualTo(1);
    }

    private void givenFirstMember() {
        MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                "20260101",
                "홍길동",
                "20260101@soongsil.ac.kr");
    }
}
