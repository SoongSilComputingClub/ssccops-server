package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;

/*
 * 접수 가능 판정(#33)의 단위 검증. 후속 이슈(#35 응답 제출 · #36 자동 저장)가 이 클래스만
 * 호출하기로 한 규칙이라, 여기가 그 규칙의 유일한 명세다.
 *
 * 스프링을 띄우지 않는다 — 판정에 필요한 것은 폼의 상태·기간과 Clock뿐이라 컨텍스트를 띄우면
 * 확인하려는 것과 무관한 이유로 깨진다. Clock을 고정하는 것이 이 테스트의 전부다.
 */
class FormReceiptPolicyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 기준 시각. 아래 모든 기간은 이 값을 사이에 두고 앞뒤로 잡는다 */
    private static final Instant NOW = Instant.parse("2026-03-15T00:00:00Z");

    private static final Instant BEFORE_NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant AFTER_NOW = Instant.parse("2026-03-31T00:00:00Z");

    private final FormReceiptPolicy policy = new FormReceiptPolicy(Clock.fixed(NOW, KST));

    /* ── 기간 4케이스 ────────────────────────────────────── */

    // 기간 미설정: 제한 없이 열어 둔 폼이다. NULL은 '제한 없음'이지 '지금이 아님'이 아니다
    @Test
    void openFormWithoutReceiptPeriodIsAccepting() {
        FormEntity form = openForm(null, null);

        assertThat(policy.isAcceptingResponses(form)).isTrue();
        assertThat(policy.receiptStatusOf(form)).isEqualTo(FormReceiptStatus.ACCEPTING);
    }

    @Test
    void openFormDuringReceiptPeriodIsAccepting() {
        FormEntity form = openForm(BEFORE_NOW, AFTER_NOW);

        assertThat(policy.isAcceptingResponses(form)).isTrue();
        assertThat(policy.receiptStatusOf(form)).isEqualTo(FormReceiptStatus.ACCEPTING);
    }

    // 기간 전: 상태는 OPEN이지만 아직 시작하지 않았다. 목록에는 '접수 예정'으로 나가야 한다
    @Test
    void openFormBeforeReceiptPeriodIsNotAccepting() {
        FormEntity form = openForm(AFTER_NOW, null);

        assertThat(policy.isAcceptingResponses(form)).isFalse();
        assertThat(policy.receiptStatusOf(form)).isEqualTo(FormReceiptStatus.SCHEDULED);
    }

    /*
     * 기간 후: 이 이슈에서 내린 결정이 그대로 드러나는 케이스다. 자동 마감 배치를 두지 않기로
     * 했으므로 상태는 OPEN으로 남고, 응답은 거부되며, 화면 표시만 EXPIRED로 갈린다.
     */
    @Test
    void openFormAfterReceiptPeriodStaysOpenButStopsAccepting() {
        FormEntity form = openForm(BEFORE_NOW, BEFORE_NOW.plusSeconds(60));

        assertThat(form.getStatus()).isEqualTo(FormStatus.OPEN);
        assertThat(policy.isAcceptingResponses(form)).isFalse();
        assertThat(policy.receiptStatusOf(form)).isEqualTo(FormReceiptStatus.EXPIRED);
    }

    /* ── 경계 ────────────────────────────────────────────── */

    /*
     * 시작·종료 정각은 모두 '접수 중'이다. 화면이 '3월 1일 ~ 3월 31일'로 안내하는데 31일 정각에
     * 닫히면 사용자가 이해하는 기간과 어긋난다. 판정식이 >= 와 <= 인 근거를 고정한다.
     */
    @Test
    void receiptPeriodBoundariesAreInclusive() {
        assertThat(policy.isAcceptingResponses(openForm(NOW, AFTER_NOW))).isTrue();
        assertThat(policy.isAcceptingResponses(openForm(BEFORE_NOW, NOW))).isTrue();
    }

    /* ── 상태 ────────────────────────────────────────────── */

    // 기간이 아무리 열려 있어도 상태가 OPEN이 아니면 받지 않는다 — 상태가 접근 통제 스위치다
    @Test
    void draftAndClosedFormsNeverAcceptRegardlessOfPeriod() {
        FormEntity draft = form(FormStatus.DRAFT, BEFORE_NOW, AFTER_NOW);
        FormEntity closed = form(FormStatus.CLOSED, BEFORE_NOW, AFTER_NOW);

        assertThat(policy.isAcceptingResponses(draft)).isFalse();
        assertThat(policy.receiptStatusOf(draft)).isEqualTo(FormReceiptStatus.DRAFT);
        assertThat(policy.isAcceptingResponses(closed)).isFalse();
        assertThat(policy.receiptStatusOf(closed)).isEqualTo(FormReceiptStatus.CLOSED);
    }

    private FormEntity openForm(Instant receiptBeginAt, Instant receiptEndAt) {
        return form(FormStatus.OPEN, receiptBeginAt, receiptEndAt);
    }

    /*
     * 생성자(creator)를 NULL로 둔다. 판정은 폼의 상태·기간만 보고 저장하지도 않으므로
     * 회원을 만들 이유가 없다 — 만들면 이 테스트가 회원 도메인의 변경에 끌려다닌다.
     */
    private FormEntity form(FormStatus status, Instant receiptBeginAt, Instant receiptEndAt) {
        return FormEntity.create(
                null, "접수 판정 폼", composition(), receiptBeginAt, receiptEndAt, status);
    }

    private QuestionCompositionContent composition() {
        return new QuestionCompositionContent(
                List.of(new Page("한 장", null)),
                List.of(
                        new QuestionItem(
                                "q1",
                                "이름",
                                QuestionItemType.SHORT_TEXT,
                                true,
                                0,
                                List.of(),
                                null,
                                null,
                                null,
                                null,
                                null)));
    }
}
