package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormResponseServiceImpl implements FormResponseService {

    private final FormRepository formRepository;
    private final FormResponseHistoryRepository formResponseHistoryRepository;
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
        return PublicFormResponse.of(form, findSubmitted(form, respondent).orElse(null));
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

        /*
         * 이미 행이 있으면 상태로 갈린다. 임시저장(DRAFT)은 아직 낸 것이 아니라 그 행을 제출로
         * 바꾸고(#36이 만들 행이다), 그 밖의 상태는 이미 낸 것이라 409다.
         *
         * (form_id, mbr_id) UNIQUE 때문에 새 행을 만들 수도 없으므로, 이 분기가 없으면 자동
         * 저장을 쓴 응답자는 영영 제출할 수 없게 된다.
         */
        Optional<FormResponseHistoryEntity> existing =
                formResponseHistoryRepository.findByFormAndMember(form, respondent);
        if (existing.isPresent()) {
            FormResponseHistoryEntity response = existing.get();
            if (response.getStatus() != ResponseStatus.DRAFT) {
                throw new GeneralException(FormErrorCode.RESPONSE_ALREADY_SUBMITTED);
            }
            response.submit(content, submittedAt);
            formResponseHistoryRepository.flush();
            return FormResponseSubmitResponse.from(response);
        }

        return FormResponseSubmitResponse.from(
                saveOrTranslateConflict(
                        FormResponseHistoryEntity.createSubmitted(
                                form, respondent, content, submittedAt),
                        FormErrorCode.RESPONSE_ALREADY_SUBMITTED));
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

        Optional<FormResponseHistoryEntity> existing =
                formResponseHistoryRepository.findByFormAndMember(form, respondent);
        existing.ifPresent(FormResponseServiceImpl::requireStillDraft);

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
                                                        form, respondent, content),
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
                .findByFormAndMember(form, respondent)
                .filter(response -> response.getStatus() == ResponseStatus.DRAFT)
                .map(FormResponseDraftResponse::from);
    }

    /*
     * 이미 낸 응답은 자동 저장으로 덮어쓸 수 없다. 제출 뒤에도 저장이 통하면 운영진이 심사한
     * 내용과 응답자가 들고 있는 화면이 소리 없이 갈라진다 — 수정 제출은 별도로 정할 규칙이다(#37).
     */
    private static void requireStillDraft(FormResponseHistoryEntity response) {
        if (response.getStatus() != ResponseStatus.DRAFT) {
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

    private FormEntity findAcceptingForm(Long formId) {
        FormEntity form =
                formRepository
                        .findById(formId)
                        .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));

        // DRAFT·CLOSED와 접수 기간 밖이 전부 여기서 한 코드로 끊긴다 (FormErrorCode 주석)
        if (!formReceiptPolicy.isAcceptingResponses(form)) {
            throw new GeneralException(FormErrorCode.FORM_NOT_ACCEPTING);
        }
        return form;
    }

    /*
     * 이 회원이 이 폼에 이미 제출했는가. 임시저장(DRAFT) 행은 아직 낸 것이 아니라 제외한다 —
     * 포함하면 자동 저장(#36)이 한 번 돌기만 해도 웹이 작성 화면 대신 제출 내역 화면을 띄운다.
     */
    private Optional<FormResponseHistoryEntity> findSubmitted(
            FormEntity form, MemberEntity respondent) {
        return formResponseHistoryRepository
                .findByFormAndMember(form, respondent)
                .filter(response -> response.getStatus() != ResponseStatus.DRAFT);
    }

    /*
     * 선조회만으로는 같은 사람이 두 탭에서 동시에 누르는 경우를 막지 못한다 — 둘 다 조회를
     * 통과한 뒤 하나가 (form_id, mbr_id) UNIQUE에 걸린다. 그 실패도 같은 409로 옮겨, 응답자가
     * 보는 결과가 타이밍에 따라 500과 409를 오가지 않게 한다 (#21 학번 중복과 같은 방식).
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
