package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

import lombok.RequiredArgsConstructor;

/*
 * "지금 이 폼이 응답을 받을 수 있는가"의 유일한 구현 (#33).
 *
 * 공개 폼 조회·응답 제출(#35)·응답 자동 저장(#36)이 전부 같은 질문을 한다. 세 군데에서 각자
 * 판정하면 반드시 어긋난다 — 한쪽만 종료 일시를 포함(<=)으로 보고 다른 쪽이 미포함(<)으로 보는
 * 식의 차이는 마감 직전 1초에만 드러나서 테스트로도 잘 잡히지 않는다. 후속 이슈는 이 클래스만
 * 호출한다.
 *
 *   접수 가능 = form_stts_cd == OPEN
 *             && (rcpt_bgng_dt == null || now >= rcpt_bgng_dt)
 *             && (rcpt_end_dt  == null || now <= rcpt_end_dt)
 *
 * 경계는 양쪽 모두 포함이다. 시작 일시 정각과 종료 일시 정각은 '접수 중'이다 — 화면이 안내하는
 * 기간이 '3월 1일 ~ 3월 31일'인데 31일 정각에 닫히면 사용자가 이해하는 기간과 어긋난다.
 * NULL은 '제한 없음'이지 '지금이 아님'이 아니다 — 기간을 정하지 않고 여는 폼이 정상이다.
 *
 * now는 LocalDate.now()/Instant.now()가 아니라 주입된 Clock에서 온다 (global/config/ClockConfig).
 * 직접 부르면 마감 경계 판정을 테스트에서 고정할 수 없다.
 *
 * ── 접수 기간이 끝난 폼을 자동 마감하지 않는 이유 (#33에서 내린 결정) ──
 *
 * rcpt_end_dt가 지나도 form_stts_cd는 OPEN으로 남는다. 응답은 위 판정식이 시간까지 보므로
 * 막히지만, 상태 코드만 읽는 목록에서는 여전히 '접수 중'으로 보인다. 이 간극을 배치로 상태를
 * CLOSED로 덮어써서 메우지 않고 표시 계층에서 나누기로 했다 (receiptStatusOf).
 *
 * 배치를 두지 않는 이유는 세 가지다.
 *   1. 배치가 쓴 CLOSED와 운영자가 누른 CLOSED가 구별되지 않는다. 구별되지 않으면 '마감 철회'
 *      (CLOSED → OPEN)가 무엇을 되돌리는 것인지 알 수 없다.
 *   2. 배치로 닫힌 폼의 종료 일시를 운영자가 미뤄도 상태는 CLOSED로 남는다. 기간은 미래인데
 *      응답은 계속 거부되는, 화면만 보고는 원인을 알 수 없는 상태가 된다.
 *   3. 상태를 쓰는 주체가 사용자 요청 밖에 하나 더 생긴다. 인스턴스가 여러 개인 배포에서는
 *      스케줄러 중복 실행을 막을 장치(ShedLock 등)가 따로 필요한데 지금 프로젝트에 없다.
 *
 * 표시 계층 구분은 파생 값이라 되돌릴 것이 없고, 나중에 배치가 필요해지면 그때 얹어도
 * 이 판정식은 그대로다.
 *
 * ── 목록 필터도 이 축을 본다 (#325 · ADR-0019) ──
 *
 * 배지는 처음부터 receiptStatusOf로 그렸지만 목록 필터는 form_stts_cd로 걸러, 기간이 끝난 폼이
 * '마감'에 걸리지 않고 '접수 중'에 남아 있었다. 운영진이 본 것은 같은 카드의 배지가 '기간 종료'인데
 * 필터는 그 폼을 접수 중으로 세는 상태다. 고친 방향은 상태를 쓰는 것이 아니라 필터를 이 축으로
 * 옮기는 것이며(filterFor), 위 세 근거는 그대로 유효하다 — 저장 계층은 아무것도 바뀌지 않았다.
 */
@Component
@RequiredArgsConstructor
public class FormReceiptPolicy {

    // 마감 경계 판정 기준 시각. 테스트에서 고정할 수 있도록 주입받는다 (ClockConfig)
    private final Clock clock;

    /** 지금 응답을 받을 수 있는가. #35·#36이 부르는 유일한 판정 지점이다 */
    public boolean isAcceptingResponses(FormEntity form) {
        return receiptStatusOf(form) == FormReceiptStatus.ACCEPTING;
    }

    /*
     * 화면에 보여줄 접수 상태. isAcceptingResponses가 이 값에서 파생하므로 두 답이 어긋날 수 없다 —
     * 목록에는 '접수 중'인데 응답은 거부되는(또는 그 반대의) 상태를 구조적으로 막는 배치다.
     */
    public FormReceiptStatus receiptStatusOf(FormEntity form) {
        FormStatus status = form.getStatus();
        if (status == FormStatus.DRAFT) {
            return FormReceiptStatus.DRAFT;
        }
        if (status == FormStatus.CLOSED) {
            return FormReceiptStatus.CLOSED;
        }

        Instant now = clock.instant();
        Instant beginAt = form.getReceiptBeginAt();
        Instant endAt = form.getReceiptEndAt();

        if (beginAt != null && now.isBefore(beginAt)) {
            return FormReceiptStatus.SCHEDULED;
        }
        if (endAt != null && now.isAfter(endAt)) {
            return FormReceiptStatus.EXPIRED;
        }
        return FormReceiptStatus.ACCEPTING;
    }

    /*
     * 목록 질의가 접수 기간을 비교하는 방식 (#325). receiptStatusOf의 분기와 1:1이며
     * 다른 값은 없다 — 여기에 값을 더하면 위 판정식에도 같은 분기가 있어야 한다.
     */
    public enum ReceiptPeriodMatch {

        /** 기간을 보지 않는다. form_stts_cd만으로 결론이 나는 DRAFT·CLOSED와 "전체"가 쓴다 */
        ANY,

        /** 시작 일시 전 (SCHEDULED) */
        BEFORE_BEGIN,

        /** 종료 일시 후 (EXPIRED) */
        AFTER_END,

        /** 기간 안 — 경계 포함 (ACCEPTING) */
        WITHIN
    }

    /*
     * 목록 질의가 쓸 조회 조건 (#325). 판정식을 두 조각으로 나눈 것이다 — 저장 컬럼으로 거를 수
     * 있는 form_stts_cd 집합과, SQL이 기간을 비교하는 방식. now를 함께 실어 보내는 것은 질의가
     * Instant.now()를 다시 부르면 판정과 다른 시각을 보게 되기 때문이다.
     */
    public record ReceiptFilter(
            Set<FormStatus> statuses, ReceiptPeriodMatch periodMatch, Instant now) {}

    /*
     * 접수 상태 필터를 질의 조건으로 번역한다 (#325 · ADR-0019).
     *
     * **이 표는 receiptStatusOf의 분기를 옮겨 적은 것이고, 그것이 이 메서드가 여기 있는
     * 이유다.** 질의가 SQL로 기간을 비교하므로 receiptStatusOf와 물리적으로 같은 코드일 수
     * 없다 — 판정식에서 멀리 떨어뜨리면 한쪽만 고쳐도 아무도 모르고, 그 어긋남은 목록과 배지가
     * 갈리는 형태로 마감 직전 1초에만 드러나 사람이 발견하지 못한다. 두 경로가 같은 답을 내는지는
     * FormReceiptFilterEquivalenceTest가 다섯 값 × 경계 표본으로 매번 확인한다.
     *
     * NULL은 "제한 없음"이지 "지금이 아님"이 아니다 — 기간을 정하지 않은 OPEN 폼은 ACCEPTING이며
     * SCHEDULED도 EXPIRED도 아니다. 경계는 양쪽 모두 포함이라 시작 정각·종료 정각은 ACCEPTING이다.
     *
     * receiptStatus가 null이면 "전체"다. 상태 집합에 NULL을 넘겨 :status is null로 분기하지 않는
     * 것은 Hibernate가 열거형 파라미터의 타입을 추론하지 못해서다 (FormRepository 주석).
     */
    public ReceiptFilter filterFor(FormReceiptStatus receiptStatus) {
        Instant now = clock.instant();
        if (receiptStatus == null) {
            return new ReceiptFilter(EnumSet.allOf(FormStatus.class), ReceiptPeriodMatch.ANY, now);
        }
        return switch (receiptStatus) {
            case DRAFT ->
                    new ReceiptFilter(EnumSet.of(FormStatus.DRAFT), ReceiptPeriodMatch.ANY, now);
            case CLOSED ->
                    new ReceiptFilter(EnumSet.of(FormStatus.CLOSED), ReceiptPeriodMatch.ANY, now);
            case SCHEDULED ->
                    new ReceiptFilter(
                            EnumSet.of(FormStatus.OPEN), ReceiptPeriodMatch.BEFORE_BEGIN, now);
            case EXPIRED ->
                    new ReceiptFilter(
                            EnumSet.of(FormStatus.OPEN), ReceiptPeriodMatch.AFTER_END, now);
            case ACCEPTING ->
                    new ReceiptFilter(EnumSet.of(FormStatus.OPEN), ReceiptPeriodMatch.WITHIN, now);
        };
    }
}
