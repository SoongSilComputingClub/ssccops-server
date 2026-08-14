package org.sscc.ssccopsserver.domain.form.service;

import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 공개 폼 조회·응답 제출 (#35). 공개 링크(/f/{formId})로 들어온 응답자가 쓰는 두 가지 일이다.
 *
 * '공개'는 누구나 링크를 열 수 있다는 뜻이지 익명으로 낼 수 있다는 뜻이 아니다 — 응답자는
 * 전원 회원이며(ssccops #61), 그래서 두 메서드 모두 회원을 받는다.
 */
public interface FormResponseService {

    /** 응답자용 폼 조회. 지금 응답을 받지 않는 폼이면 문항을 내려주지 않고 끊는다 */
    PublicFormResponse getPublicForm(Long formId, MemberEntity respondent);

    /** 응답 제출. 응답자·상태·제출 일시는 요청이 아니라 서버가 정한다 */
    FormResponseSubmitResponse submitResponse(
            Long formId, FormResponseSubmitRequest request, MemberEntity respondent);
}
