package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
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
                                form, respondent, content, submittedAt)));
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
     */
    private FormResponseHistoryEntity saveOrTranslateConflict(FormResponseHistoryEntity response) {
        try {
            return formResponseHistoryRepository.saveAndFlush(response);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(FormErrorCode.RESPONSE_ALREADY_SUBMITTED);
        }
    }
}
