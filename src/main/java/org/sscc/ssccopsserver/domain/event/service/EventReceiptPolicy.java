package org.sscc.ssccopsserver.domain.event.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.service.FormReceiptPolicy;

import lombok.RequiredArgsConstructor;

/*
 * "이 행사의 모집 배지에 무엇을 그릴 것인가"의 유일한 구현 (ssccops#139·#143 · D3).
 *
 * 행사는 모집 기간을 스스로 갖지 않는다 — 접수 여부는 전부 연결된 폼의 사실이고 판정은
 * FormReceiptPolicy 하나다. 여기서 하는 일은 그 판정을 부르기 전에 "폼이 없으면 null"이라는
 * 계약 한 줄을 붙이는 것뿐이며, 그 한 줄을 정책과 나란히 두는 이유는 부르는 쪽이 둘이기
 * 때문이다: 운영자용 EventServiceImpl과 공개용 PublicEventServiceImpl.
 *
 * 삼항 연산자 하나를 양쪽에 적어도 당장은 같은 값이 나오지만, 폼 없는 행사를 어떻게 표시할지가
 * 바뀌는 날(예: null 대신 전용 값) 한쪽만 고쳐지면 같은 행사가 관리 화면과 공개 화면에서 다른
 * 배지를 단다 — 그 어긋남은 화면을 나란히 놓기 전에는 드러나지 않는다.
 *
 * 진행 단계(eventPhase)의 짝은 EventPhasePolicy다.
 */
@Component
@RequiredArgsConstructor
public class EventReceiptPolicy {

    private final FormReceiptPolicy formReceiptPolicy;

    /** 모집 상태는 연결된 폼의 파생 값이다(D3). 폼이 없는 공지는 계약대로 null이다 */
    public FormReceiptStatus receiptStatusOf(EventEntity event) {
        return event.getForm() == null ? null : formReceiptPolicy.receiptStatusOf(event.getForm());
    }
}
