package org.sscc.ssccopsserver.domain.form.repository;

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
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.global.config.JsonFormatMapperConfig;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 미제출 초안 보존 기간 정리의 **조건**을 못 박는다 (#557 · ssccops#502 · #36 결정).
 *
 * 스케줄러(`FormDraftRetentionScheduler`)는 cron 껍데기이고 판단은 전부 이 질의에 있다.
 *
 * ── 이 테스트가 있는 진짜 이유 ──────────────────────────────────────────────
 * `domain/form/AGENTS.md` 가 두 가지를 적어 두었는데 **서로 다르다.**
 *
 *  · 문장: «폼이 CLOSED된 뒤 남은 DRAFT는 **접수 종료 후 90일**까지 보존한다» — 즉시 지우지
 *    않는 근거는 마감 철회(CLOSED→OPEN)가 허용된다는 것이다(#33)
 *  · 대안으로 적어 둔 수동 SQL: `WHERE rspns_stts_cd = 'DRAFT' AND mdfcn_dt < now() - interval '90 days'`
 *    — **폼 상태를 보지 않는다**
 *
 * 그 SQL 을 그대로 옮기면 **접수가 열려 있는 폼의 초안도 지운다.** 장기 모집 폼에서 90일 넘게
 * 손대지 않은 «작성 중인 지원서»가 사라지는데, 그것은 정책이 아니라 사고다. 문장에는 근거가
 * 있고 SQL 에는 없으므로 문장을 정본으로 삼았고, **아래 두 번째 테스트가 그 선택을 지킨다.**
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, JsonFormatMapperConfig.class})
class FormDraftRetentionQueryTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

    /** 기준 시각 — 이보다 앞서 접수가 끝난 폼의 초안이 대상이다. */
    private static final Instant NINETY_DAYS_AGO = NOW.minusSeconds(90L * 24 * 60 * 60);

    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberEntity applicant;

    @BeforeEach
    void setUp() {
        applicant =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260101",
                        "홍길동",
                        "20260101@soongsil.ac.kr");
    }

    /* 접수가 끝난 지 91일 된 CLOSED 폼의 초안은 지운다 — 이것이 #36 이 정한 일이다. */
    @Test
    void deletesDraftsOfFormsClosedLongerThanRetention() {
        FormEntity closedLongAgo =
                saveForm("2026 신규모집", FormStatus.CLOSED, NOW.minusSeconds(91L * 24 * 60 * 60));
        saveDraft(closedLongAgo);

        int deleted = purge();

        Assertions.assertThat(deleted).isEqualTo(1);
        Assertions.assertThat(formResponseHistoryRepository.findAll()).isEmpty();
    }

    /*
     * ⚠️ **접수가 열려 있는 폼의 초안은 남는다** — 문서의 수동 SQL 을 그대로 옮겼다면 여기서
     * 지워졌을 것이다.
     *
     * 초안 자체는 오래됐다(작성자가 90일 넘게 손대지 않았다). 그런데 폼은 아직 OPEN 이므로
     * 그 사람은 지금도 이어 쓸 수 있다. 지우면 작성 중인 지원서가 사라진다.
     */
    @Test
    void keepsDraftsWhileTheFormIsStillOpen() {
        FormEntity stillOpen =
                saveForm("장기 모집", FormStatus.OPEN, NOW.plusSeconds(30L * 24 * 60 * 60));
        saveDraft(stillOpen);

        Assertions.assertThat(purge()).isZero();
        Assertions.assertThat(formResponseHistoryRepository.findAll()).hasSize(1);
    }

    /* 90일이 아직 안 지난 CLOSED 폼은 남는다 — 마감 철회(CLOSED→OPEN)가 허용되기 때문이다(#33). */
    @Test
    void keepsDraftsOfRecentlyClosedForms() {
        FormEntity closedRecently =
                saveForm("지난주 마감", FormStatus.CLOSED, NOW.minusSeconds(7L * 24 * 60 * 60));
        saveDraft(closedRecently);

        Assertions.assertThat(purge()).isZero();
        Assertions.assertThat(formResponseHistoryRepository.findAll()).hasSize(1);
    }

    /*
     * **제출된 응답은 지우지 않는다.** 지원 의사를 남긴 것이라 보관 근거가 있다 —
     * 여기서 걷는 것은 «낸 적 없는 것»뿐이다.
     */
    @Test
    void keepsSubmittedResponsesOfLongClosedForms() {
        FormEntity closedLongAgo =
                saveForm("2025 신규모집", FormStatus.CLOSED, NOW.minusSeconds(400L * 24 * 60 * 60));
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        closedLongAgo,
                        applicant,
                        ResponseContent.of(Map.of("q1", "홍길동")),
                        NOW.minusSeconds(401L * 24 * 60 * 60)));

        Assertions.assertThat(purge()).isZero();
        Assertions.assertThat(formResponseHistoryRepository.findAll()).hasSize(1);
    }

    /*
     * 접수 종료가 비어 있는 폼은 건드리지 않는다.
     *
     * 기준으로 쓸 값이 없으므로 «90일이 지났는가»에 답할 수 없고, 답할 수 없는 것을 지우는 쪽으로
     * 기울이지 않는다. 그런 폼이 CLOSED 로 남아 있으면 그것은 사람이 볼 일이다.
     */
    @Test
    void keepsDraftsWhenReceiptEndIsUnknown() {
        FormEntity noReceiptEnd = saveForm("접수 일시 없는 폼", FormStatus.CLOSED, null);
        saveDraft(noReceiptEnd);

        Assertions.assertThat(purge()).isZero();
        Assertions.assertThat(formResponseHistoryRepository.findAll()).hasSize(1);
    }

    private int purge() {
        return formResponseHistoryRepository.deleteDraftsOfFormsClosedBefore(
                ResponseStatus.DRAFT, FormStatus.CLOSED, NINETY_DAYS_AGO);
    }

    /*
     * 문항을 하나 넣는 것은 장식이 아니다 — `FormEntity` 가 «문항이 없는 폼은 접수를 시작할 수
     * 없습니다»로 OPEN 생성을 거절한다. 빈 구성으로 쓰려다 그 가드에 걸려 알았고, 거절이 맞다.
     */
    private static QuestionCompositionContent oneQuestion() {
        return new QuestionCompositionContent(
                List.of(new Page("페이지1", null)),
                List.of(
                        new QuestionItem(
                                "q1",
                                "이름",
                                QuestionItemType.SHORT_TEXT,
                                false,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)));
    }

    private FormEntity saveForm(String title, FormStatus status, Instant receiptEndAt) {
        return formRepository.saveAndFlush(
                FormEntity.create(applicant, title, oneQuestion(), null, receiptEndAt, status));
    }

    private void saveDraft(FormEntity form) {
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(
                        form, applicant, ResponseContent.of(Map.of("q1", "홍길동"))));
    }
}
