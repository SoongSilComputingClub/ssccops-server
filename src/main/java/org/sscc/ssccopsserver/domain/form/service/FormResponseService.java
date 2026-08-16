package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;
import java.util.Optional;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 폼 응답에 관한 일 전부 — 응답자 쪽의 제출(#35)·자동 저장(#36)과 운영자 쪽의 조회·심사(#37).
 *
 * 컨트롤러는 응답자용(PublicFormController)과 운영자용(FormResponseController)으로 나누되
 * 서비스는 나누지 않는다. 두 쪽이 form_rspns_hstry라는 같은 행을 다루기 때문이다 — "DRAFT는
 * 심사 대상이 아니다"·"제출은 응답자만 한다"처럼 양쪽에 걸친 규칙이 서비스가 둘이 되는 순간
 * 두 벌이 되고, 두 벌이 되면 갈린다(폼 저장 경로와 라벨 지정 경로에서 실제로 겪은 일이다,
 * FormServiceImpl.replaceLabels 주석). 나눠야 할 것은 무엇이 밖으로 나가는가이고 그것은
 * 컨트롤러와 응답 DTO가 이미 나누고 있다.
 *
 * '공개'는 누구나 링크를 열 수 있다는 뜻이지 익명으로 낼 수 있다는 뜻이 아니다 — 응답자는
 * 전원 회원이며(ssccops #61), 그래서 응답자용 메서드가 모두 회원을 받는다.
 *
 * 자동 저장의 대상은 **언제나 인증 주체 본인의 응답**이다. 회원 식별자를 인자로 받지 않는 것이
 * 아니라 받을 수 없게 두는 것이 요점이다 — 받는 순간 남의 작성 중 응답에 닿는 경로가 생기고,
 * 그때부터 그 경로를 막는 것은 인가 검사 한 줄이 빠지지 않는지에 달린다.
 *
 * 반대로 운영자용 조회는 회원을 받지 않는다. 대상이 남의 응답인 것이 정상이라 주체로 범위를
 * 좁힐 수 없고, 대신 **폼(formId)이 범위를 정한다** — 그래서 세 메서드가 모두 formId를 받는다.
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

    /*
     * 운영자용 응답 목록 (#37). statusCode가 null이면 **작성 중(DRAFT)을 뺀 전부**다 —
     * "전체"가 DRAFT를 포함하지 않는다는 것이 이 API의 기본값이며, 작성 중 응답은
     * statusCode=DRAFT를 명시했을 때만 나온다.
     */
    List<FormResponseSummaryResponse> getResponses(Long formId, ResponseStatus statusCode);

    /** 운영자용 응답 상세 (#37). 다른 폼의 응답 식별자는 없는 응답과 같다 */
    FormResponseDetailResponse getResponse(Long formId, Long formResponseId);

    /*
     * 응답 상태 변경 (#37). SUBMITTED ↔ ACCEPTED ↔ REJECTED만 오갈 수 있고 DRAFT가 얽힌 전이는
     * 거절한다. 수행자는 어디에도 남지 않는다 (응답 상태 이력 테이블이 없다 — 감사 로그 #8).
     */
    FormResponseSummaryResponse changeResponseStatus(
            Long formId, Long formResponseId, FormResponseStatusChangeRequest request);
}
