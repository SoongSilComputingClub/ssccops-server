package org.sscc.ssccopsserver.domain.form.service;

import java.util.Optional;

import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 공개 폼 조회·응답 제출(#35)·자동 저장(#36). 공개 링크(/f/{formId})로 들어온 응답자가 쓰는 일이다.
 *
 * '공개'는 누구나 링크를 열 수 있다는 뜻이지 익명으로 낼 수 있다는 뜻이 아니다 — 응답자는
 * 전원 회원이며(ssccops #61), 그래서 모든 메서드가 회원을 받는다.
 *
 * 자동 저장의 대상은 **언제나 인증 주체 본인의 응답**이다. 회원 식별자를 인자로 받지 않는 것이
 * 아니라 받을 수 없게 두는 것이 요점이다 — 받는 순간 남의 작성 중 응답에 닿는 경로가 생기고,
 * 그때부터 그 경로를 막는 것은 인가 검사 한 줄이 빠지지 않는지에 달린다.
 */
public interface FormResponseService {

    /** 응답자용 폼 조회. 지금 응답을 받지 않는 폼이면 문항을 내려주지 않고 끊는다 */
    PublicFormResponse getPublicForm(Long formId, MemberEntity respondent);

    /** 응답 제출. 응답자·상태·제출 일시는 요청이 아니라 서버가 정한다 */
    FormResponseSubmitResponse submitResponse(
            Long formId, FormResponseSubmitRequest request, MemberEntity respondent);

    /*
     * 작성 중 응답 저장 (#36). 행이 있으면 내용만 갱신하고 없으면 DRAFT로 만든다.
     *
     * 제출과 달리 필수·형식·최대 선택 수를 보지 않는다. 작성 중에 그 규칙을 걸면 답을 완성하기
     * 전까지는 아무것도 저장되지 않아 자동 저장이 있으나 마나 해진다.
     */
    FormResponseDraftResponse saveDraft(
            Long formId, FormResponseDraftRequest request, MemberEntity respondent);

    /*
     * 내 작성 중 응답 조회 (#36). 없으면 비어 있다 — 웹은 이 값의 유무로 '이어서 작성'을 띄울지
     * 정한다. 이미 제출한 응답은 작성 중이 아니므로 여기에 실리지 않는다.
     */
    Optional<FormResponseDraftResponse> findMyDraft(Long formId, MemberEntity respondent);
}
