package org.sscc.ssccopsserver.domain.form.dto;

import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 회원용 시스템 폼 조회 (#181 · GET /v1/forms/system/{sysFormCd}).
 *
 * 기획안 재제출 화면(apps/lms)은 sys_form_cd = 'PROPOSAL' 폼의 form_id를 알아야 하는데,
 * form_id는 IDENTITY라 환경마다 다르고 sysFormCd를 싣는 조회 경로(GET /v1/forms · GET
 * /v1/forms/{formId})는 전부 @RequireAuthority(FORM_READ)라 일반 회원이 부를 수 없다. 이
 * 응답은 그 회원이 인증만으로 받아 가는 최소 정보다.
 *
 * ── 운영자용 DTO(FormSummaryResponse · FormDetailResponse)를 재사용하지 않는 이유 ──
 * 그쪽에는 생성자(creatrMbrId)·응답 집계(responseSummary)·폼 상태 내부값(formSttsCd)처럼
 * 회원에게 줄 이유가 없는 필드가 있다. 한 record를 공유하면 운영자용에 필드가 하나 늘 때마다
 * 회원용 경로로 새어 나갈 것이 함께 는다 — MyFormResponseDetailResponse가 운영자용을 재사용하지
 * 않은 것과 같은 판단이다.
 *
 * ── qitemCpstCn을 함께 싣는 이유 ──
 * 재제출 화면은 마감된 폼의 문항도 그려야 한다. CHANGES_REQUESTED 재제출은 접수 마감에 막히지
 * 않는데(#177) GET /v1/forms/{formId}/public은 마감 시 409 FORM_NOT_ACCEPTING이라 문항을 받을 수
 * 없다. 이 조회는 접수 가능 여부를 보지 않는다 — 자기가 낸 것을 확인·재제출하는 흐름의 재료이며
 * GET /v1/forms/{formId}/responses/mine과 같은 기준이다.
 *
 * ── acceptingYn ──
 * "지금 새 응답을 받는가"만 답한다. 출처는 FormReceiptPolicy 하나여야 하며(웹이 상태·기간으로
 * 다시 계산하지 않게) 재제출이 마감에 막히지 않는 예외(#177)와는 무관하다 — 재제출 가능 여부는
 * POST /v1/forms/{formId}/responses가 판정한다.
 */
public record SystemFormResponse(
        Long formId,
        String formTtlNm,
        String sysFormCd,
        boolean mltplRspnsYn,
        boolean acceptingYn,
        QuestionCompositionContent qitemCpstCn) {

    public static SystemFormResponse of(FormEntity form, boolean acceptingYn) {
        return new SystemFormResponse(
                form.getId(),
                form.getTitle(),
                form.getSystemFormCode(),
                form.isMultipleResponseAllowed(),
                acceptingYn,
                form.getQuestionComposition());
    }
}
