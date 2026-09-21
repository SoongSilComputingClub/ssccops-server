package org.sscc.ssccopsserver.domain.form.dto;

/*
 * 시스템 폼 지정 응답 (#520 · PUT /v1/forms/system/{sysFormCd} · ssccops#436 · ADR-0044).
 *
 * 새로 지정된 폼의 상세(form)를 그대로 싣고 그 앞에 «어느 코드를 · 어느 폼에서»(sysFormCd ·
 * prevFormId)를 붙인 모양이다. 상세를 통째로 싣는 것은 지정 직후 화면이 그 폼의 배지(sysFormCd ·
 * sysYn · receiptStatus)를 다시 조회하지 않고 그리기 위해서고(FormStatusChangeResponse가 접수
 * 기간을 함께 싣는 것과 같은 이유), FormDetailResponse를 그대로 돌려주지 않은 것은 이전 폼이 어느
 * 것이었는지가 상세에 실릴 자리가 없기 때문이다 — 어드민이 «지난 학기 폼의 지정이 풀렸다»를 알아야
 * 그 폼을 정리할 수 있다. 첫 지정이거나 같은 폼을 다시 지정했으면 prevFormId는 각각 null · 그 폼이다.
 */
public record SystemFormDesignateResponse(
        String sysFormCd, Long prevFormId, FormDetailResponse form) {}
