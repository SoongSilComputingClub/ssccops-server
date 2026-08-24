package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseReviewRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.MyFormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseReviewHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormResponseServiceImpl implements FormResponseService {

    private final FormRepository formRepository;
    private final FormResponseHistoryRepository formResponseHistoryRepository;

    /*
     * 검토 처리 이력 (#141). 상태를 바꾸는 트랜잭션 안에서 함께 쓰기 때문에 별도 서비스로
     * 나누지 않는다 — 나누면 "심사한다"와 "남긴다"가 두 경계에 걸려 한쪽만 성공할 자리가 생긴다.
     */
    private final FormResponseReviewHistoryRepository formResponseReviewHistoryRepository;

    private final ResponseAnswerValidator responseAnswerValidator;

    /*
     * "지금 이 폼이 응답을 받을 수 있는가"의 유일한 구현 (#33). 조회와 제출이 같은 판정을 써야
     * 화면에는 문항이 보이는데 제출은 거부되는(또는 그 반대의) 상태가 생기지 않는다.
     */
    private final FormReceiptPolicy formReceiptPolicy;

    /** 제출 일시의 기준 시각. 접수 마감 판정(FormReceiptPolicy)과 같은 시계를 쓴다 */
    private final Clock clock;

    /*
     * 응답자용 폼 조회.
     *
     * 접수 가능하지 않으면 문항 구성을 담은 응답 자체를 만들지 않고 409로 끊는다. 상태만 실어
     * 200으로 내려주는 선택지도 있었지만, 그러면 문항을 뺐는지 여부가 DTO 조립 코드의 분기 하나에
     * 달리게 된다 — DRAFT 폼의 문항이 링크만으로 새어 나가는 사고는 그 분기 하나가 잘못되는
     * 것으로 충분히 일어난다. 아예 다른 경로로 나가게 두는 편이 안전하다.
     */
    @Override
    public PublicFormResponse getPublicForm(Long formId, MemberEntity respondent) {
        FormEntity form = findAcceptingForm(formId);
        return PublicFormResponse.of(form, findSubmittedResponses(form, respondent));
    }

    /*
     * 내 응답 목록 (#143 · GET /v1/forms/{formId}/responses/mine).
     *
     * **접수 가능 여부를 보지 않는다.** 자동 저장 조회(findMyDraft)와 갈리는 지점이며 근거는 이
     * 조회가 쓰기와 짝을 이루지 않는다는 것이다 — 저쪽은 "복원은 되는데 제출은 안 되는 화면"을
     * 막으려고 같은 판정을 태웠지만, 여기서 409를 내면 접수가 끝난 순간 응답자가 자기가 낸 것을
     * 확인할 길이 사라진다. 아직 열지 않은(DRAFT) 폼이라도 새어 나갈 것이 없다 — 응답자 본인의
     * 행만 돌려주는데 응답을 낼 수 없었던 폼에는 그 행이 존재하지 않아 언제나 빈 배열이다.
     *
     * 초안(DRAFT)도 함께 싣는다. 운영자용 목록이 DRAFT를 빼는 것과 기준이 갈리는데, 그쪽은 "남의
     * 제출 전 답안이 심사 목록에 섞이지 않게" 하는 규칙이고 이쪽은 내 것이라 숨길 이유가 없다 —
     * 오히려 빼면 쓰다 만 응답이 화면에서 사라져 이어 쓸 방법이 없어진다.
     */
    @Override
    public List<MyFormResponseSummaryResponse> getMyResponses(
            Long formId, MemberEntity respondent) {
        FormEntity form = findForm(formId);
        return formResponseHistoryRepository
                .findAllByFormAndMemberOrderByResponseSequenceAsc(form, respondent)
                .stream()
                .map(MyFormResponseSummaryResponse::from)
                .toList();
    }

    /*
     * 응답 제출.
     *
     * 검사 순서는 폼 → 답 → 중복이다. 접수도 하지 않는 폼에 낸 답의 형식을 따져 400을 돌려주면
     * 응답자는 답을 고치면 될 것처럼 안내받지만 실제로는 무엇을 고쳐도 낼 수 없다.
     */
    @Override
    @Transactional
    public FormResponseSubmitResponse submitResponse(
            Long formId, FormResponseSubmitRequest request, MemberEntity respondent) {

        FormEntity form = findAcceptingForm(formId);

        /*
         * 저장된 문항 구성을 다시 읽어 검증한다. 웹도 같은 검사를 하지만(validatePage) 공개 링크라
         * 요청을 직접 만들 수 있으므로 그 검사는 신뢰 대상이 아니다.
         */
        ResponseContent content =
                responseAnswerValidator.validate(form.getQuestionComposition(), request.rspnsCn());

        Instant submittedAt = clock.instant();

        List<FormResponseHistoryEntity> myResponses =
                formResponseHistoryRepository.findAllByFormAndMemberOrderByResponseSequenceAsc(
                        form, respondent);

        /*
         * 이어 쓸 응답이 있으면 새 행을 만들지 않고 그 행을 제출로 바꾼다.
         *
         * 임시저장(#36)을 쓴 응답자에게 이 분기가 없으면 UNIQUE 때문에 새 행도 만들지 못해 영영
         * 제출할 수 없고, 수정요청을 받은 응답(#141)은 애초에 그 행을 다시 내는 것이 재제출이다.
         *
         * **지금 낼 수 있는 상태인가는 여기서 따지지 않는다** (#141). 그 판정을 서비스에 두면 상태
         * 어휘가 늘 때마다 규칙이 복제된다 — 엔티티의 submit()이 상태를 보고 끊고 제출 회차도
         * 거기서 오른다 (LY-02).
         */
        Optional<FormResponseHistoryEntity> continuing = findContinuableResponse(myResponses);
        if (continuing.isPresent()) {
            FormResponseHistoryEntity response = continuing.get();
            response.submit(content, submittedAt);
            recordSubmission(response, respondent, submittedAt);
            formResponseHistoryRepository.flush();
            return FormResponseSubmitResponse.from(response);
        }

        /*
         * 이어 쓸 응답이 없는데 낸 응답은 있다 — 단일 응답 폼이면 여기서 끝이다 (#143).
         *
         * 서비스가 409를 직접 던지지 않고 남아 있는 행의 submit()을 부르는 것은 **어느 코드로
         * 끊을지가 상태마다 다르기 때문**이다: 심사 중·승인은 RESPONSE_ALREADY_SUBMITTED이고
         * 반려는 RESPONSE_ALREADY_REJECTED다(#141). 그 표를 서비스에 옮겨 적으면 상태 어휘가
         * 늘 때마다 두 벌이 되고, 실제로 #141이 코드를 나눈 이유(응답자가 할 수 있는 일이 다르다)가
         * 한쪽에서만 지켜진다. 이 호출은 반드시 예외로 끝난다 — 남아 있는 상태가 SUBMITTED ·
         * ACCEPTED · REJECTED뿐이기 때문이다.
         *
         * 다중 응답 폼은 이 분기를 지나가 새 응답이 된다. 반려된 응답만 남아 있어도 마찬가지이며,
         * 그것이 #141이 말한 "오조작의 탈출구는 번복이 아니라 새 응답"의 실제 경로다.
         */
        if (!myResponses.isEmpty() && !form.isMultipleResponseAllowed()) {
            myResponses.get(myResponses.size() - 1).submit(content, submittedAt);
        }

        FormResponseHistoryEntity response =
                saveOrTranslateConflict(
                        FormResponseHistoryEntity.createSubmitted(
                                form,
                                respondent,
                                content,
                                submittedAt,
                                nextResponseSequence(form, respondent)),
                        FormErrorCode.RESPONSE_ALREADY_SUBMITTED);
        recordSubmission(response, respondent, submittedAt);
        return FormResponseSubmitResponse.from(response);
    }

    /*
     * 이 제출이 이어 쓸 응답 (#143). 없으면 새 응답을 만들 차례다.
     *
     * 고르는 순서가 규칙이다. 초안(DRAFT)이 있으면 언제나 그 행이며 — 초안은 폼 종류와 무관하게
     * 최대 1건이라 고를 것도 없다 — 초안이 없고 수정요청을 받은 응답이 있으면 그것을 마무리하는
     * 것이 새 응답을 시작하는 것보다 먼저다. 다중 응답 폼에서 두 뜻이 겹치는 순간이 실제로
     * 생기는데(수정요청을 받아 두고 새 제안을 내려는 경우), 재제출을 우선하지 않으면 응답자는
     * 수정요청받은 응답을 영영 마무리할 수 없다 — 제출 경로에 응답 식별자가 없어 지목할 방법이
     * 없기 때문이다. 반대 선택(새 응답 우선)은 되돌릴 길이 없고, 이쪽은 그 응답을 낸 다음에 또
     * 내면 된다.
     *
     * 수정요청이 여럿이면 순번이 가장 큰 것이다. 목록이 순번 오름차순이라 마지막 원소다.
     *
     * **응답 식별자를 받는 재제출 전용 경로(POST .../responses/{id}/resubmit)는 열지 않았다.**
     * 응답자 화면이 아직 수정요청 사유를 읽는 길조차 갖고 있지 않아(#141) 그 경로가 무엇을 받고
     * 무엇을 보여줄지가 화면 설계와 함께 정해져야 한다 — 지금 열면 쓰는 곳 없는 계약이 먼저 굳는다.
     */
    private static Optional<FormResponseHistoryEntity> findContinuableResponse(
            List<FormResponseHistoryEntity> myResponses) {

        Optional<FormResponseHistoryEntity> draft =
                myResponses.stream()
                        .filter(response -> response.getStatus() == ResponseStatus.DRAFT)
                        .findFirst();
        if (draft.isPresent()) {
            return draft;
        }
        return myResponses.stream()
                .filter(response -> response.getStatus() == ResponseStatus.CHANGES_REQUESTED)
                .reduce((earlier, later) -> later);
    }

    /*
     * 새 응답이 쓸 순번 (#143). **응답 순번(rspns_seq)이지 제출 회차(sbmsn_seq)가 아니다** —
     * 여기서 오르는 것은 "이 회원의 몇 번째 응답인가"이고, 같은 행을 다시 내는 회차는 엔티티의
     * submit()이 올린다.
     *
     * 지금 있는 행 수를 세지 않고 마지막 순번을 묻는 것은, 응답이 지워진 적이 있으면 이미 쓴
     * 번호를 다시 배정하게 되기 때문이다. 버려진 초안이 번호를 먹어 구멍이 나는 것은 무방하다 —
     * 표시용 번호가 아니라 UNIQUE를 성립시키는 식별자다.
     */
    private int nextResponseSequence(FormEntity form, MemberEntity respondent) {
        return formResponseHistoryRepository.findLastResponseSequence(form, respondent) + 1;
    }

    /*
     * 작성 중 응답 저장 (#36). upsert다 — 행이 있으면 내용만 갈고, 없으면 DRAFT로 만든다.
     *
     * 검사 순서는 폼 → 제출 여부 → 답이다. 답의 모양을 먼저 따져 400을 돌려주면 응답자(정확히는
     * 웹의 자동 저장)는 답을 고치면 저장될 것처럼 안내받지만, 접수가 끝났거나 이미 제출한 폼에서는
     * 무엇을 고쳐도 저장되지 않는다 (#35의 제출 경로와 같은 이유).
     *
     * 상태·제출 일시는 여기서 건드리지 않는다. DRAFT 행의 sbmsn_dt는 NULL이어야 하고 그 값을
     * 채우는 유일한 자리는 제출(submitResponse)이다 — 자동 저장이 그 둘을 함께 만질 수 있게 두면
     * "낸 적 없는데 제출 일시가 있는" 행이 생기는 경로가 열린다.
     */
    @Override
    @Transactional
    public FormResponseDraftResponse saveDraft(
            Long formId, FormResponseDraftRequest request, MemberEntity respondent) {

        FormEntity form = findAcceptingForm(formId);

        /*
         * 초안은 폼 종류와 무관하게 언제나 최대 1건이다 (#143). 있으면 그 행을 갱신하고, 없을
         * 때만 새로 만들 수 있는지 따진다 — 이 순서가 곧 애플리케이션 쪽 '초안 1건' 판정이다.
         *
         * DB의 부분 유니크 인덱스(uk_form_rspns_hstry_one_draft)와 두 겹인 것은 각자 막는 것이
         * 다르기 때문이다. 인덱스는 동시 요청을 막고(선조회는 둘 다 "없다"를 본다), 이 판정은
         * H2에서도 규칙이 지켜지게 한다 — H2는 부분 인덱스를 지원하지 않아 테스트가 인덱스로는
         * 아무것도 확인할 수 없다.
         */
        Optional<FormResponseHistoryEntity> existing =
                formResponseHistoryRepository.findByFormAndMemberAndStatus(
                        form, respondent, ResponseStatus.DRAFT);
        if (existing.isEmpty()) {
            requireNewDraftAllowed(form, respondent);
        }

        /*
         * 자동 저장 전용 검증. 필수·정규식·최대 선택 수·선택지 실재 여부는 보지 않고, 폼에 없는
         * qitemId와 저장 형태·크기만 본다 (ResponseAnswerValidator 주석).
         */
        ResponseContent content =
                responseAnswerValidator.validateDraft(
                        form.getQuestionComposition(), request.rspnsCn());

        FormResponseHistoryEntity draft =
                existing.map(found -> updateDraft(found, content))
                        .orElseGet(
                                () ->
                                        saveOrTranslateConflict(
                                                FormResponseHistoryEntity.createDraft(
                                                        form,
                                                        respondent,
                                                        content,
                                                        nextResponseSequence(form, respondent)),
                                                FormErrorCode.RESPONSE_SAVE_CONFLICT));

        return FormResponseDraftResponse.from(draft);
    }

    /*
     * 내 작성 중 응답 조회 (#36). 대상은 언제나 인증 주체 본인이라 회원 식별자를 받지 않는다.
     *
     * 접수 가능 여부를 여기서도 따지는 것은 응답자용 폼 조회와 짝을 맞추기 위해서다. 웹은 두
     * 요청을 나란히 보내는데, 한쪽은 409로 "지금은 쓸 수 없는 폼"이라 하고 다른 쪽은 작성 중인
     * 내용을 돌려주면 화면은 복원은 되지만 제출은 되지 않는 상태에 놓인다.
     *
     * 이미 제출한 응답은 작성 중이 아니므로 비어 있는 것으로 답한다 — 오류가 아니다. "제출을
     * 마쳤다"는 사실은 응답자용 폼 조회(alreadySubmitted)가 이미 전하고, 여기까지 409로 끊으면
     * 웹은 같은 사실을 두 경로에서 각각 처리해야 한다.
     */
    @Override
    public Optional<FormResponseDraftResponse> findMyDraft(Long formId, MemberEntity respondent) {
        FormEntity form = findAcceptingForm(formId);
        return formResponseHistoryRepository
                .findByFormAndMemberAndStatus(form, respondent, ResponseStatus.DRAFT)
                .map(FormResponseDraftResponse::from);
    }

    /*
     * 운영자용 응답 목록 (#37).
     *
     * **페이징을 두지 않는다.** 모집 폼에 수백 건이 들어올 수 있다는 것은 사실이지만, 이 화면은
     * 목록을 한 번 받아 상태별로 걸러 보며 심사하는 화면이고 상세의 이전/다음 이동도 그 순서를
     * 그대로 따른다 — 페이지를 나누면 '이전'이 페이지 경계에서 끊기거나 서버가 페이지 밖의
     * 이웃까지 계산해야 한다. ssccops-web #13도 배열을 받는 전제로 이미 머지됐고, 폼 목록(#32)이
     * 같은 이유로 페이징을 미뤄 둔 선례가 있다. 응답이 실제로 수백 건 쌓여 목록이 느려지면
     * 그때 커서 기반으로 넣되 웹의 목록·상세 이동과 함께 바꾼다.
     *
     * 쿼리는 폼 1 + 목록 1로 두 번이다. 목록에 회원 정보가 붙지만 응답 수와 무관하게 한 번이며
     * (findAllForOperatorList의 엔티티 그래프), 이 수는 테스트가 못 박아 둔다.
     */
    @Override
    public List<FormResponseSummaryResponse> getResponses(Long formId, ResponseStatus statusCode) {
        FormEntity form = findForm(formId);
        return formResponseHistoryRepository
                .findAllForOperatorList(form, statusesToList(statusCode))
                .stream()
                .map(FormResponseSummaryResponse::from)
                .toList();
    }

    /*
     * 운영자용 응답 상세 (#37).
     *
     * 조회에 formId를 함께 거는 것이 이 메서드의 첫 번째 책임이다 — 응답 식별자만으로 찾으면
     * /v1/forms/1/responses/999가 다른 폼의 지원자 답변과 개인정보를 그대로 돌려준다.
     *
     * 인접 응답은 목록의 기본 조회와 같은 순서·범위에서 고른다. 목록에 없는 응답(DRAFT)을 직접
     * 열면 이웃이 없다 — 심사 목록에서 빠져 있던 응답이 이동만으로 심사 흐름에 들어오면
     * "DRAFT는 심사 대상이 아니다"가 목록에서만 지켜지는 규칙이 된다.
     */
    @Override
    public FormResponseDetailResponse getResponse(Long formId, Long formResponseId) {
        FormEntity form = findForm(formId);
        FormResponseHistoryEntity response = findResponse(form, formResponseId);

        /*
         * 처리 이력은 상태와 무관하게 싣는다 (#141). DRAFT 응답에는 아직 아무 처리도 없어 빈
         * 배열이지만, 상태로 분기해 아예 조회하지 않으면 "이력이 없다"와 "이력을 안 봤다"가
         * 같은 응답이 된다.
         */
        List<FormResponseReviewHistoryEntity> reviewHistories =
                formResponseReviewHistoryRepository.findAllByResponseOrderByProcessedAtAscIdAsc(
                        response);

        if (response.getStatus() == ResponseStatus.DRAFT) {
            return FormResponseDetailResponse.of(response, reviewHistories, null, null);
        }

        List<Long> orderedIds =
                formResponseHistoryRepository.findIdsForOperatorList(
                        form, ResponseStatus.submittedOrLater());
        int index = orderedIds.indexOf(formResponseId);
        Long previousId = index > 0 ? orderedIds.get(index - 1) : null;
        Long nextId =
                index >= 0 && index < orderedIds.size() - 1 ? orderedIds.get(index + 1) : null;

        return FormResponseDetailResponse.of(response, reviewHistories, previousId, nextId);
    }

    /*
     * 검토 처리 (#141). 전이 규칙과 처리 구분 판정은 엔티티(FormResponseHistoryEntity.review)가
     * 갖고 여기서는 범위 검사와 조립만 한다 — 서비스에 if로 옮겨 적으면 상태를 바꾸는 다른
     * 경로가 생길 때 규칙이 갈린다 (LY-02 · FormServiceImpl.changeStatus와 같은 방식).
     *
     * **상태 변경과 이력 INSERT는 한 트랜잭션이다.** 상태만 바뀌고 이력이 없으면 그 심사는
     * 근거를 잃고, 이력 행은 updatable = false로 잠겨 있어 나중에 채워 넣을 경로도 없다 —
     * 등급·상태 변경(#78)이 세운 것과 같은 규칙이며 이력 저장이 실패하면 상태도 되돌아간다
     * (FormResponseReviewRollbackTest가 못 박는다).
     *
     * **처리자는 요청 본문이 아니라 @CurrentMember에서 온 회원이다.** 본문으로 받으면 "누가
     * 승인했는가"를 스스로 적어 넣을 수 있어 이력이 증거가 되지 못한다. #37에서 컨트롤러가 받은
     * @CurrentMember를 서비스로 넘기지 않았던 것은 남길 자리가 없었기 때문이고, 이 이슈가 그
     * 자리를 만들었으므로 이제는 넘긴다.
     *
     * 검토 의견 필수 여부는 여기서 보지 않는다 — 처리 구분마다 다르고, 그 판단과 거절은
     * ResponseReviewAction · FormResponseReviewHistoryEntity.record가 한 벌로 갖는다.
     *
     * 접수 가능 여부(FormReceiptPolicy)는 보지 않는다. 심사는 접수가 끝난 뒤에 하는 일이라
     * 응답자 경로와 같은 판정을 걸면 마감한 폼의 응답을 아무도 승인할 수 없다.
     */
    @Override
    @Transactional
    public FormResponseSummaryResponse reviewResponse(
            Long formId,
            Long formResponseId,
            FormResponseReviewRequest request,
            MemberEntity reviewer) {

        FormResponseHistoryEntity response = findResponse(findForm(formId), formResponseId);
        ResponseReviewAction action = response.review(request.rspnsSttsCd());

        formResponseReviewHistoryRepository.save(
                FormResponseReviewHistoryEntity.record(
                        response, action, reviewer, request.rvwOpnnCn(), clock.instant()));

        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 (updateDraft 주석과 같은 이유)
        formResponseHistoryRepository.flush();

        return FormResponseSummaryResponse.from(response);
    }

    /*
     * 제출 이력 한 줄 (#141). 최초 제출과 재제출 모두 남긴다.
     *
     * 검토 이력 테이블에 제출이 들어가는 이유는 엔티티 주석에 있다 — 타임라인이 "제출 →
     * 수정요청 → 재제출 → 승인"으로 읽히려면 회차가 오른 시점이 그 안에 있어야 한다.
     * 처리자는 검토자가 아니라 응답자 본인이며, 제출에는 검토 의견이 없다.
     */
    private void recordSubmission(
            FormResponseHistoryEntity response, MemberEntity respondent, Instant submittedAt) {
        formResponseReviewHistoryRepository.save(
                FormResponseReviewHistoryEntity.record(
                        response, ResponseReviewAction.SUBMIT, respondent, null, submittedAt));
    }

    /*
     * 목록·인접 응답이 볼 상태 집합.
     *
     * 미지정은 "전체"가 아니라 **작성 중을 뺀 전부**다. 제출 전 답안이 제출된 응답과 섞이면
     * 운영자에게는 심사 대기 목록에 든 것처럼 보이고, 그 목록에서 승인을 누르면 응답자가 아직
     * 쓰고 있던 내용이 그대로 확정된다. 작성 중 응답을 실제로 봐야 할 때(진행 상황 확인)를 위해
     * statusCode=DRAFT는 열어 두되, 명시적으로 고른 경우로 제한한다.
     */
    private static Collection<ResponseStatus> statusesToList(ResponseStatus statusCode) {
        return statusCode == null ? ResponseStatus.submittedOrLater() : EnumSet.of(statusCode);
    }

    private FormEntity findForm(Long formId) {
        return formRepository
                .findById(formId)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }

    /** 폼 범위 안에서만 찾는다. 다른 폼의 응답 식별자는 없는 응답과 같은 404다 */
    private FormResponseHistoryEntity findResponse(FormEntity form, Long formResponseId) {
        return formResponseHistoryRepository
                .findByIdAndForm(formResponseId, form)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_RESPONSE_NOT_FOUND));
    }

    /*
     * 새 초안을 시작할 수 있는가 (#143). 초안이 없는 상태에서만 부른다.
     *
     * 단일 응답 폼에서 이미 낸 응답이 있으면 거절한다 — 제출 뒤에도 저장이 통하면 운영진이 심사한
     * 내용과 응답자가 들고 있는 화면이 소리 없이 갈라진다(#36의 판단이며 그대로 유지한다).
     * 다중 응답 폼에서는 그 응답이 새 응답을 막을 이유가 없으므로 통과시킨다 — 초안 자리는
     * 제출로 비워졌고, 새 초안은 다음 순번을 받는다.
     *
     * 남아 있는 행의 상태를 따지지 않고 존재만 보는 것은, 초안이 없다는 것이 이미 확인된 뒤라
     * 남은 것은 정의상 제출 이상뿐이기 때문이다.
     */
    private void requireNewDraftAllowed(FormEntity form, MemberEntity respondent) {
        if (form.isMultipleResponseAllowed()) {
            return;
        }
        if (formResponseHistoryRepository.existsByFormAndMember(form, respondent)) {
            throw new GeneralException(FormErrorCode.RESPONSE_ALREADY_SUBMITTED);
        }
    }

    /*
     * 기존 DRAFT 행 갱신. 새 행을 만들지 않으므로 자동 저장을 아무리 자주 불러도 행 수는 그대로다.
     *
     * flush를 명시하는 것은 mdfcn_dt 때문이다. @LastModifiedDate는 UPDATE가 나가는 시점에
     * 채워지므로, 트랜잭션이 끝나기 전에 응답 DTO를 만들면 웹이 받는 '마지막 저장 시각'이 이번
     * 저장이 아니라 직전 저장의 값이 된다.
     */
    private FormResponseHistoryEntity updateDraft(
            FormResponseHistoryEntity draft, ResponseContent content) {
        draft.updateContent(content);
        formResponseHistoryRepository.flush();
        return draft;
    }

    /*
     * 응답자용 경로가 쓰는 폼 조회. 운영자용 조회(findForm)와 갈리는 것은 접수 가능 여부를
     * 함께 보느냐 하나다 — 심사는 접수가 끝난 뒤에 하는 일이라 운영자 경로에 이 판정을 걸면
     * 마감한 폼의 응답을 아무도 열어볼 수 없다.
     */
    private FormEntity findAcceptingForm(Long formId) {
        FormEntity form = findForm(formId);

        // DRAFT·CLOSED와 접수 기간 밖이 전부 여기서 한 코드로 끊긴다 (FormErrorCode 주석)
        if (!formReceiptPolicy.isAcceptingResponses(form)) {
            throw new GeneralException(FormErrorCode.FORM_NOT_ACCEPTING);
        }
        return form;
    }

    /*
     * 이 회원이 이 폼에 낸 응답들 (#143 · 순번 오름차순). 임시저장(DRAFT) 행은 아직 낸 것이
     * 아니라 제외한다 — 포함하면 자동 저장(#36)이 한 번 돌기만 해도 웹이 작성 화면 대신 제출
     * 내역 화면을 띄운다.
     *
     * 기준 집합을 여기서 다시 나열하지 않고 ResponseStatus.submittedOrLater를 쓰는 것은 응답
     * 집계·운영자 목록과 같은 어휘를 써야 하기 때문이다 (#37) — 두 벌이 되면 "낸 것으로 세는 상태"가
     * 화면마다 갈린다. EnumSet을 루프 밖에서 한 번만 만드는 것은 그 팩토리가 호출마다 새 집합을
     * 만들기 때문이다.
     */
    private List<FormResponseHistoryEntity> findSubmittedResponses(
            FormEntity form, MemberEntity respondent) {
        EnumSet<ResponseStatus> submitted = ResponseStatus.submittedOrLater();
        return formResponseHistoryRepository
                .findAllByFormAndMemberOrderByResponseSequenceAsc(form, respondent)
                .stream()
                .filter(response -> submitted.contains(response.getStatus()))
                .toList();
    }

    /*
     * 선조회만으로는 같은 사람이 두 탭에서 동시에 누르는 경우를 막지 못한다 — 둘 다 조회를
     * 통과한 뒤 하나가 (form_id, mbr_id, rspns_seq) UNIQUE에 걸린다. 두 요청이 같은 마지막
     * 순번을 읽고 같은 다음 번호를 계산하므로 다중 응답 폼에서도 이 방어선은 그대로 선다 (#143).
     * 그 실패도 같은 409로 옮겨, 응답자가 보는 결과가 타이밍에 따라 500과 409를 오가지 않게
     * 한다 (#21 학번 중복과 같은 방식).
     *
     * 제약 위반은 flush 시점에야 드러나므로 saveAndFlush로 이 메서드 안에서 잡는다.
     *
     * 옮길 코드를 인자로 받는 것은 경합의 뜻이 경로마다 다르기 때문이다. 제출끼리 부딪히면
     * "이미 냈다"(RESPONSE_ALREADY_SUBMITTED)가 맞지만, 자동 저장이 부딪힌 상대는 같은 사람의
     * 다른 임시저장일 수 있어 그렇게 답하면 거짓말이 된다 (RESPONSE_SAVE_CONFLICT).
     */
    private FormResponseHistoryEntity saveOrTranslateConflict(
            FormResponseHistoryEntity response, FormErrorCode conflictCode) {
        try {
            return formResponseHistoryRepository.saveAndFlush(response);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(conflictCode);
        }
    }
}
