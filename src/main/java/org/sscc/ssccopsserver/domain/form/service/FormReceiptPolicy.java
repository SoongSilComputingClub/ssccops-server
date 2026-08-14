package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Instant;

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
}
