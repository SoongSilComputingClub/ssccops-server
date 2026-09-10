package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.service.FormReceiptPolicy;
import org.sscc.ssccopsserver.domain.form.service.FormService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 목록 필터와 배지가 같은 답을 내는지 (#325 · ADR-0019).
 *
 * **이 이슈가 남기는 유일한 위험을 지키는 테스트다.** 필터가 파생값(FormReceiptStatus) 축으로
 * 옮겨지면서 판정이 두 곳에 생겼다 — 자바의 FormReceiptPolicy.receiptStatusOf(배지)와, SQL로
 * 기간을 비교하는 목록 질의(FormRepository.findAllForAdminList)다. 질의는 SQL이라 판정식과
 * 물리적으로 같은 코드일 수 없으므로 두 벌인 것 자체는 피할 수 없고, 대신 갈렸는지를 여기서 본다.
 *
 * 갈리면 목록과 배지가 어긋난다. 그리고 그 어긋남은 경계(시작 정각·종료 정각)와 NULL 기간에서만
 * 나타나므로 **마감 직전 1초에만 드러나 사람이 발견하지 못한다.** 그래서 확인 방식이 "몇 개가
 * 나오는가"가 아니라 **다섯 값 × 경계 표본 전체를 두 경로에 같이 먹여 결과 집합을 통째로 비교**하는
 * 형태다 — 표본을 하나 더 늘리면 다섯 값 전부에 대해 자동으로 검사된다.
 *
 * 컨텍스트를 새로 띄우지 않으려고 FormControllerTest와 같은 설정을 그대로 쓴다 (#103 — 스프링
 * 컨텍스트 수가 테스트 시간을 지배한다). 고정 시각도 그쪽의 NOW와 같아야 표본 주석이 어긋나지 않는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, FormControllerTest.FormTestConfig.class})
@Transactional
class FormReceiptFilterEquivalenceTest {

    /** 고정 기준 시각 (2026-03-15 00:00 KST). FormControllerTest.NOW와 같은 값이어야 한다 */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    private static final Instant ONE_SECOND_BEFORE = NOW.minusSeconds(1);
    private static final Instant ONE_SECOND_AFTER = NOW.plusSeconds(1);
    private static final Instant LONG_BEFORE = NOW.minusSeconds(86_400L * 14);
    private static final Instant LONG_AFTER = NOW.plusSeconds(86_400L * 14);

    @Autowired private FormService formService;
    @Autowired private FormRepository formRepository;
    @Autowired private FormReceiptPolicy formReceiptPolicy;
    @Autowired private Clock clock;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberEntity creator;

    @BeforeEach
    void setUp() {
        creator =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260325",
                        "필터 검증",
                        "filter-equivalence@soongsil.ac.kr");
    }

    /*
     * 시각이 실제로 고정돼 있는지부터 본다. 여기가 흔들리면 아래 비교가 "두 경로가 같다"가 아니라
     * "둘 다 같은 시스템 시각을 봤다"를 확인하는 것이 되어, 경계 표본이 달력에 따라 의미를 잃는다.
     */
    @Test
    void clockIsFixedSoBoundarySamplesKeepTheirMeaning() {
        assertThat(clock.instant()).isEqualTo(NOW);
    }

    /*
     * 다섯 값 전부를 두 경로에 같이 먹여 결과 집합을 비교한다.
     *
     * 기대값을 손으로 적지 않는 것이 요점이다 — 손으로 적으면 그 목록이 세 번째 판정식이 되어
     * 지켜야 할 대상이 하나 더 늘어난다. 배지 쪽(receiptStatusOf)을 기준으로 삼고 질의가 그것을
     * 따라오는지만 본다. 표본이 각 값에 최소 한 건씩은 들어가는지도 함께 확인해, 전부 빈 집합이라
     * 통과하는 상태를 막는다.
     */
    @Test
    void listQueryAndBadgeAgreeOnEveryReceiptStatus() {
        List<FormEntity> samples = saveBoundarySamples();

        for (FormReceiptStatus receiptStatus : FormReceiptStatus.values()) {
            Set<Long> fromBadge =
                    samples.stream()
                            .filter(
                                    form ->
                                            formReceiptPolicy.receiptStatusOf(form)
                                                    == receiptStatus)
                            .map(FormEntity::getId)
                            .collect(Collectors.toSet());

            Set<Long> fromQuery =
                    formService.getForms(receiptStatus, null).stream()
                            .map(FormSummaryResponse::formId)
                            .collect(Collectors.toSet());

            assertThat(fromQuery).as("%s — 목록 질의와 배지 판정이 갈렸다", receiptStatus).isEqualTo(fromBadge);
            assertThat(fromBadge).as("%s 표본이 하나도 없어 비교가 성립하지 않는다", receiptStatus).isNotEmpty();
        }
    }

    /*
     * 필터를 걸지 않으면 표본 전부가 나오고, 다섯 값의 결과를 합치면 그 전부와 같아야 한다.
     *
     * 값별 비교만으로는 어느 값에도 걸리지 않고 사라지는 폼을 잡지 못한다 — 경계 조건을 한쪽
     * 방향으로만 잘못 쓰면(예: 양쪽 다 미포함) 종료 정각인 폼이 ACCEPTING에도 EXPIRED에도
     * 들어가지 않은 채 다섯 비교가 전부 통과한다. 배지는 반드시 다섯 중 하나를 돌려주므로
     * 합집합이 전체와 같은지가 그 구멍을 막는다.
     */
    @Test
    void fiveReceiptStatusesPartitionTheWholeList() {
        List<FormEntity> samples = saveBoundarySamples();
        Set<Long> allIds = samples.stream().map(FormEntity::getId).collect(Collectors.toSet());

        Set<Long> unfiltered =
                formService.getForms(null, null).stream()
                        .map(FormSummaryResponse::formId)
                        .collect(Collectors.toSet());
        assertThat(unfiltered).isEqualTo(allIds);

        List<Long> unioned = new ArrayList<>();
        for (FormReceiptStatus receiptStatus : FormReceiptStatus.values()) {
            formService.getForms(receiptStatus, null).stream()
                    .map(FormSummaryResponse::formId)
                    .forEach(unioned::add);
        }

        // 겹치지 않는다 — 한 폼은 정확히 한 값에 속한다
        assertThat(unioned).doesNotHaveDuplicates();
        assertThat(Set.copyOf(unioned)).isEqualTo(allIds);
    }

    /*
     * 접수 기간이 NULL인 폼. '제한 없음'이지 '지금이 아님'이 아니므로 열려 있으면 ACCEPTING이고
     * SCHEDULED에도 EXPIRED에도 들어가지 않는다. NULL 비교를 빠뜨리면 SQL의 3값 논리 때문에
     * 조용히 어느 결과에도 나타나지 않는 폼이 되는데, 그 폼은 목록에서 사라진 것으로 보인다.
     */
    @Test
    void formsWithoutReceiptPeriodAreAcceptingNotScheduledOrExpired() {
        Long noPeriod = save(FormStatus.OPEN, null, null).getId();
        Long noEnd = save(FormStatus.OPEN, LONG_BEFORE, null).getId();
        Long noBeginPastEnd = save(FormStatus.OPEN, null, LONG_BEFORE).getId();
        Long noBeginFutureEnd = save(FormStatus.OPEN, null, LONG_AFTER).getId();

        assertThat(idsOf(FormReceiptStatus.ACCEPTING))
                .contains(noPeriod, noEnd, noBeginFutureEnd)
                .doesNotContain(noBeginPastEnd);
        assertThat(idsOf(FormReceiptStatus.EXPIRED))
                .contains(noBeginPastEnd)
                .doesNotContain(noPeriod, noEnd, noBeginFutureEnd);
        assertThat(idsOf(FormReceiptStatus.SCHEDULED))
                .doesNotContain(noPeriod, noEnd, noBeginPastEnd, noBeginFutureEnd);
    }

    /*
     * 경계는 양쪽 모두 포함이다. 시작 정각·종료 정각은 '접수 중'이며, 갈리는 것은 그 앞뒤 1초다 —
     * 화면이 '3월 1일 ~ 3월 31일'이라 안내하는데 31일 정각에 마감으로 넘어가면 사용자가 이해하는
     * 기간과 어긋난다 (FormReceiptPolicy 주석).
     */
    @Test
    void bothPeriodBoundariesAreInclusive() {
        Long beginsNow = save(FormStatus.OPEN, NOW, LONG_AFTER).getId();
        Long endsNow = save(FormStatus.OPEN, LONG_BEFORE, NOW).getId();
        Long beginsAndEndsNow = save(FormStatus.OPEN, NOW, NOW).getId();
        Long beginsInOneSecond = save(FormStatus.OPEN, ONE_SECOND_AFTER, LONG_AFTER).getId();
        Long endedOneSecondAgo = save(FormStatus.OPEN, LONG_BEFORE, ONE_SECOND_BEFORE).getId();

        assertThat(idsOf(FormReceiptStatus.ACCEPTING))
                .contains(beginsNow, endsNow, beginsAndEndsNow)
                .doesNotContain(beginsInOneSecond, endedOneSecondAgo);
        assertThat(idsOf(FormReceiptStatus.SCHEDULED)).containsOnlyOnce(beginsInOneSecond);
        assertThat(idsOf(FormReceiptStatus.EXPIRED)).containsOnlyOnce(endedOneSecondAgo);
    }

    /*
     * 운영진이 보고한 증상 그대로 (#325). 기간이 끝난 폼은 form_stts_cd가 OPEN이라 저장값 축의
     * '마감'(statusCode=CLOSED)에는 걸리지 않는다 — 그래서 필터가 파생값 축으로 옮겨졌다.
     * 운영자가 직접 닫은 폼과 기간이 끝난 폼이 서로 다른 값이라는 것도 함께 본다.
     */
    @Test
    void expiredFormIsFoundByExpiredNotByClosed() {
        Long expired = save(FormStatus.OPEN, LONG_BEFORE, ONE_SECOND_BEFORE).getId();
        Long closedByOperator = save(FormStatus.CLOSED, LONG_BEFORE, LONG_AFTER).getId();

        assertThat(idsOf(FormReceiptStatus.EXPIRED))
                .contains(expired)
                .doesNotContain(closedByOperator);
        assertThat(idsOf(FormReceiptStatus.CLOSED))
                .contains(closedByOperator)
                .doesNotContain(expired);

        // 저장값은 그대로다 — 고친 것은 상태가 아니라 거르는 축이다
        assertThat(formRepository.findById(expired).orElseThrow().getStatus())
                .isEqualTo(FormStatus.OPEN);
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

    private List<Long> idsOf(FormReceiptStatus receiptStatus) {
        return formService.getForms(receiptStatus, null).stream()
                .map(FormSummaryResponse::formId)
                .toList();
    }

    /*
     * 경계 표본 한 벌. 상태 셋 × (NULL·과거·정각·미래) 기간 조합이며 다섯 파생값이 전부 채워진다.
     * DRAFT·CLOSED에도 기간을 붙이는 것은 저장값이 결론을 내는 두 값이 기간에 흔들리지 않는지를
     * 함께 보기 위해서다 — 기간 조건이 상태 분기 밖으로 새면 여기서 걸린다.
     */
    private List<FormEntity> saveBoundarySamples() {
        return List.of(
                // form_stts_cd만으로 결론이 나는 둘. 기간이 무엇이든 값이 바뀌면 안 된다
                save(FormStatus.DRAFT, null, null),
                save(FormStatus.DRAFT, LONG_BEFORE, LONG_BEFORE),
                save(FormStatus.DRAFT, LONG_AFTER, LONG_AFTER),
                save(FormStatus.CLOSED, null, null),
                save(FormStatus.CLOSED, LONG_BEFORE, LONG_AFTER),
                save(FormStatus.CLOSED, LONG_BEFORE, ONE_SECOND_BEFORE),

                // NULL은 '제한 없음'이다
                save(FormStatus.OPEN, null, null),
                save(FormStatus.OPEN, LONG_BEFORE, null),
                save(FormStatus.OPEN, null, LONG_AFTER),
                save(FormStatus.OPEN, null, LONG_BEFORE),
                save(FormStatus.OPEN, LONG_AFTER, null),

                // 경계 — 정각은 접수 중, 그 앞뒤 1초가 갈린다
                save(FormStatus.OPEN, NOW, LONG_AFTER),
                save(FormStatus.OPEN, LONG_BEFORE, NOW),
                save(FormStatus.OPEN, NOW, NOW),
                save(FormStatus.OPEN, ONE_SECOND_AFTER, LONG_AFTER),
                save(FormStatus.OPEN, LONG_BEFORE, ONE_SECOND_BEFORE),
                save(FormStatus.OPEN, ONE_SECOND_BEFORE, ONE_SECOND_AFTER),

                // 경계에서 먼 평범한 셋
                save(FormStatus.OPEN, LONG_BEFORE, LONG_AFTER),
                save(FormStatus.OPEN, LONG_BEFORE, LONG_BEFORE),
                save(FormStatus.OPEN, LONG_AFTER, LONG_AFTER));
    }

    private FormEntity save(FormStatus status, Instant beginAt, Instant endAt) {
        return formRepository.saveAndFlush(
                FormEntity.create(
                        creator,
                        "%s %s~%s".formatted(status, beginAt, endAt),
                        singleQuestion(),
                        beginAt,
                        endAt,
                        status));
    }

    /*
     * 문항 한 개짜리 최소 구성. OPEN으로 만드는 폼은 requireOpenable이 문항을 요구하므로
     * 빈 구성으로는 표본을 세울 수 없다.
     */
    private QuestionCompositionContent singleQuestion() {
        return new QuestionCompositionContent(
                List.of(new QuestionCompositionContent.Page("한 장", null)),
                List.of(
                        new QuestionCompositionContent.QuestionItem(
                                "q1",
                                "이름",
                                QuestionItemType.SHORT_TEXT,
                                true,
                                0,
                                List.of(),
                                Map.of(),
                                null,
                                null,
                                null,
                                null)));
    }
}
