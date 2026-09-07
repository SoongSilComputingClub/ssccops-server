package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;
import java.util.Optional;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseReviewRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.MyFormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.MyFormResponseOverviewResponse;
import org.sscc.ssccopsserver.domain.form.dto.MyFormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormResponse;
import org.sscc.ssccopsserver.domain.form.dto.SystemFormResponse;
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

    /*
     * 회원용 시스템 폼 조회 (#181 · GET /v1/forms/system/{sysFormCd}).
     *
     * sysFormCd로 폼의 form_id·제목·다중 응답 여부·문항 구성과 지금 새 응답을 받는지를 돌려준다.
     * sysFormCd를 싣는 다른 조회는 전부 FORM_READ 권한이 걸려 일반 회원(기획안 제출자)이 부를 수
     * 없어, 재제출 화면이 폼 번호를 얻을 길이 없었다.
     *
     * **접수 가능 여부를 보지 않는다** — 재제출 화면은 마감된 폼의 문항도 그려야 하고(#177),
     * 자기가 낸 것을 확인·재제출하는 흐름의 재료라 GET .../responses/mine과 같은 기준이다.
     * acceptingYn은 "지금 새 응답을 받는가"만 전하며 판정은 FormReceiptPolicy 하나가 한다.
     *
     * 없는 코드는 404 FORM_NOT_FOUND다 — 아직 시드되지 않았거나(회원이 한 명도 없으면 기획안
     * 폼 시드를 미룬다) 지워진 경우다. sys_form_cd UNIQUE가 환경당 한 건을 보장하므로 여러 건을
     * 가정하지 않는다.
     */
    SystemFormResponse getSystemForm(String systemFormCode);

    /*
     * 내 응답 목록 (#143). 대상은 언제나 인증 주체 본인이라 자동 저장과 같은 이유로 회원
     * 식별자를 받지 않는다.
     *
     * 작성 중(DRAFT)도 함께 돌려주며, 접수가 끝난 폼에서도 조회된다 — 이 조회는 쓰기와 짝을
     * 이루지 않아 접수 판정을 걸 이유가 없고, 걸면 마감 직후부터 자기가 낸 것을 볼 수 없다.
     */
    List<MyFormResponseSummaryResponse> getMyResponses(Long formId, MemberEntity respondent);

    /*
     * 폼을 가로지르는 내 응답 목록 (ssccops#221). 폼을 모르는 채로 시작하는 유일한 응답 조회이며,
     * 수정요청을 받은 응답자가 그 폼 링크를 잃어버렸을 때 찾아 들어오는 길이다.
     *
     * **행사 신청은 빠진다** — GET /v1/events/my-applications가 그것을 답하고, 두 목록이 같은
     * 화면에 놓이므로 거르지 않으면 같은 응답이 두 줄로 보인다.
     */
    List<MyFormResponseOverviewResponse> getMyResponsesAcrossForms(MemberEntity respondent);

    /*
     * 제출자용 본인 응답 상세 (#177). 내 답 전체(rspnsCn)와 검토 처리 이력을 함께 돌려준다 —
     * 수정요청 사유를 읽고 이전 답을 불러오는 것이 이 조회의 목적이며, 그 둘이 없으면 재제출은
     * 전체 본문을 처음부터 다시 치는 것으로만 된다.
     *
     * 회원 식별자를 받지 않는 것은 자동 저장·내 응답 목록이 세운 규칙 그대로이고, 여기에 더해
     * **응답 식별자가 본인 행을 가리키지 않으면 404다** — 폼 범위 검사가 폼 경계를 지키듯 회원
     * 경계를 지키는 조건이며, 없는 응답과 남의 응답이 같은 코드로 끊긴다.
     *
     * 접수 가능 여부를 보지 않는다. 내 응답 목록과 같은 기준이다 — 자기가 낸 것을 확인하는
     * 조회라 접수가 끝난 뒤에도 열려야 하고, 오히려 마감 뒤에 수정요청 사유를 읽는 것이 이
     * 경로의 실제 쓰임이다.
     */
    MyFormResponseDetailResponse getMyResponse(
            Long formId, Long formResponseId, MemberEntity respondent);

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

    /**
     * 운영자용 응답 상세 (#37). 다른 폼의 응답 식별자는 없는 응답과 같다.
     *
     * <p>요청자를 받는 것은 <b>연락처를 담을지 가르기 위해서다</b>(#277). 이 엔드포인트를 지키는 것은 RESPONSE_REVIEW이고 연락처는
     * MEMBER_MANAGE의 값이라, 자격을 서비스에서 한 번 더 묻는다.
     */
    FormResponseDetailResponse getResponse(
            Long formId, Long formResponseId, MemberEntity requester);

    /*
     * 응답 한 건의 현재 심사 상태 (#198).
     *
     * 부르는 쪽은 심사가 이미 끝났는지를 알아야 하는 경로다 — 학술 모집 선발이 같은 신청자를
     * 다시 저장할 때, 이미 ACCEPTED인 응답에 검토를 한 번 더 걸면 종결 상태라 400이고 통과해도
     * 처리 이력에 아무것도 바꾸지 않은 승인이 한 줄 더 쌓인다(#141). 재선발은 재심사가 아니다.
     *
     * 상세(getResponse)를 부르지 않는 것은 그쪽이 검토 이력과 인접 응답까지 함께 조회하기
     * 때문이고, 검토를 무조건 걸어 예외로 갈라내지 않는 것은 "이미 승인된 응답"과 "승인할 수
     * 없는 응답"이 같은 코드로 도착하기 때문이다. 범위 검사는 다른 조회와 같다 — 다른 폼의
     * 응답 식별자는 없는 응답과 같은 404다.
     */
    ResponseStatus getResponseStatus(Long formId, Long formResponseId);

    /*
     * 검토 처리 (#141). 상태 변경과 처리 이력 INSERT를 **한 트랜잭션**으로 묶는다 — "심사한다"와
     * "그 사실을 남긴다"는 나눌 수 없는 한 건이라, 이력 저장이 실패하면 상태도 되돌아간다
     * (#78의 MemberChangeRollbackTest 선례).
     *
     * 도달할 수 있는 상태는 ACCEPTED · CHANGES_REQUESTED · REJECTED 셋이며 수정요청·반려는
     * 검토 의견이 필수다. 승인·반려는 종결이라 그 뒤로는 어떤 검토도 걸 수 없다(전이표는
     * FormResponseHistoryEntity.changeStatus). 처리자(reviewer)는 요청 본문이 아니라
     * @CurrentMember에서 온다.
     */
    FormResponseSummaryResponse reviewResponse(
            Long formId,
            Long formResponseId,
            FormResponseReviewRequest request,
            MemberEntity reviewer);
}
