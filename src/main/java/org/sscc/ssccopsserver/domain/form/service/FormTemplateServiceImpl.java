package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.FormFromTemplateRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormFromTemplateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateUseRequest;
import org.sscc.ssccopsserver.domain.form.dto.TemplateFromFormRequest;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormTemplateEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormTemplateRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormTemplateServiceImpl implements FormTemplateService {

    private final FormTemplateRepository formTemplateRepository;
    private final FormRepository formRepository;

    /*
     * **폼과 같은 검증기다** (#142의 핵심 제약). 템플릿 전용 검증기를 하나 더 만들면 두 규칙이
     * 갈리는 순간 "템플릿에서는 만들었는데 폼으로는 저장되지 않는" 구성이 생기고, 사용자가
     * 마주치는 것은 자기가 만들지 않은 폼의 400이다. 문항 구성이 저장되기 전에 통과해야 하는
     * 문은 이 클래스 하나뿐이며, 그 문은 방향(폼→템플릿·템플릿→폼)을 가리지 않는다.
     */
    private final QuestionCompositionValidator questionCompositionValidator;

    /*
     * 템플릿 목록. 쿼리는 1회다 — 생성자 이름을 함께 내리므로 @EntityGraph로 끌어온다
     * (FormTemplateRepository 주석). 라벨·응답처럼 따로 모아 올 것이 없어 폼 목록보다 단순하다.
     *
     * 페이징을 두지 않는 것은 폼 라벨과 같은 이유다 (AP-11) — 운영진이 손으로 만드는 데이터라
     * 수십 건을 넘지 않고 화면도 전체를 한 번에 그리므로 페이징할 축이 없다.
     */
    @Override
    public List<FormTemplateResponse> getTemplates(Boolean useYn) {
        List<FormTemplateEntity> templates =
                useYn == null
                        ? formTemplateRepository.findAllByOrderByNameAsc()
                        : formTemplateRepository.findAllByActiveOrderByNameAsc(useYn);

        return templates.stream().map(FormTemplateResponse::from).toList();
    }

    @Override
    public FormTemplateDetailResponse getTemplate(Long formTmplId) {
        return FormTemplateDetailResponse.from(findTemplate(formTmplId));
    }

    /*
     * 템플릿 생성. 생성자는 인증 주체이며 요청 본문이 지정할 수 없다 — 폼과 같은 이유이고,
     * form_tmpl.creatr_mbr_id도 사후 변경 불가(updatable = false)라 되돌릴 수 없다.
     *
     * 다만 이 값은 **감사용이지 접근 제어용이 아니다.** 템플릿은 공용이라 다른 회원도 그대로
     * 고치고 쓸 수 있다.
     */
    @Override
    @Transactional
    public FormTemplateResponse createTemplate(
            FormTemplateSaveRequest request, MemberEntity creator) {
        QuestionCompositionContent composition =
                questionCompositionValidator.validate(request.qitemCpstCn());

        return FormTemplateResponse.from(
                formTemplateRepository.saveAndFlush(
                        FormTemplateEntity.create(
                                creator, request.tmplNm(), request.tmplExpln(), composition)));
    }

    /*
     * 템플릿 수정. 문항 구성은 전체 교체이며 근거는 폼과 같다.
     *
     * 폼 수정(FormServiceImpl.updateForm)이 하는 문항 식별자 보호
     * (ensureExistingQuestionItemsKept · 409 QUESTION_ITEM_IN_USE)를 여기서는 하지 않는다.
     * 그 규칙이 지키는 것은 form_rspns_hstry.rspns_cn의 key인데 **템플릿에는 응답이 없다** —
     * 템플릿의 qitemId를 바꿔도 끊길 답이 존재하지 않고, 이 템플릿으로 이미 만든 폼은 자기
     * 사본을 갖고 있어 영향을 받지 않는다. 이것 역시 '폼에 플래그' 대신 테이블을 나눈 덕에
     * 여기 분기를 두지 않아도 되는 자리다.
     *
     * 비활성 템플릿도 고칠 수 있다 — 비활성은 "새로 고를 수 없다"이지 "잠긴다"가 아니다.
     */
    @Override
    @Transactional
    public FormTemplateResponse updateTemplate(Long formTmplId, FormTemplateSaveRequest request) {
        FormTemplateEntity template = findTemplate(formTmplId);
        QuestionCompositionContent composition =
                questionCompositionValidator.validate(request.qitemCpstCn());

        template.update(request.tmplNm(), request.tmplExpln(), composition);
        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 — 먼저 흘려보내야 응답의 수정 일시가 실제 값이 된다
        formTemplateRepository.flush();

        return FormTemplateResponse.from(template);
    }

    /*
     * 사용 여부 전환. 지우지 않으므로 되돌릴 수 있고, 같은 값을 다시 넣어도 결과가 같다(멱등) —
     * 화면의 토글이 두 번 눌려도 오류가 아니다 (FormLabelServiceImpl.updateLabelUsage와 같다).
     *
     * 라벨과 달리 "사용 중인 폼 N건"을 함께 세지 않는다. 템플릿에서 만든 폼은 그 순간 독립하며
     * 어느 템플릿에서 나왔는지 가리키는 컬럼이 없기 때문이다 — 셀 수 있는 것이 없다는 사실이
     * 곧 "템플릿을 내려도 기존 폼은 아무 영향이 없다"의 다른 표현이다.
     */
    @Override
    @Transactional
    public FormTemplateResponse changeTemplateUsage(
            Long formTmplId, FormTemplateUseRequest request) {
        FormTemplateEntity template = findTemplate(formTmplId);
        template.changeActive(request.useYn());
        formTemplateRepository.flush();

        return FormTemplateResponse.from(template);
    }

    /*
     * 템플릿으로 새 폼 만들기 — 이 이슈가 열어 주는 실제 동작.
     *
     * 결과는 FormServiceImpl.duplicate와 같은 모양의 폼이다: 상태 DRAFT · 접수 기간 없음 ·
     * 라벨 없음 · 문항 구성은 깊은 복사 · 생성자는 이 조작을 수행한 회원. 그 규칙의 유일한
     * 구현은 두 경로가 함께 부르는 FormEntity.create(..., DRAFT)이며, 여기서 상태나 기간을
     * 다시 판단하지 않는다.
     *
     * 그럼에도 duplicate를 호출하지 않고 별도 메서드로 둔 것은 입력과 제목 규칙이 다르기
     * 때문이다 — duplicate는 원본 '폼'을 받아 제목에 '(복사본)'을 붙이지만, 여기서는 원본이
     * 템플릿이고 제목은 요청이 정하거나 템플릿명을 쓴다. duplicate에 "원본이 템플릿인 경우"
     * 분기를 넣으면 폼 복제 로직이 템플릿을 알아야 하고, 그것이야말로 이 이슈가 피하려던
     * 종류의 얽힘이다.
     *
     * 라벨을 붙이지 않으므로 FormLabelService를 부르지 않는다. 폼 생성(createForm)이
     * replaceLabels를 태우는 것과 갈리는데, 여기서는 지정할 라벨이 정의상 없다 — 라벨은
     * '2026 신규모집'처럼 회차를 뜻하는 값이라 템플릿이 들고 있을 수 있는 값이 아니다.
     */
    @Override
    @Transactional
    public FormFromTemplateResponse createFormFromTemplate(
            Long formTmplId, FormFromTemplateRequest request, MemberEntity creator) {
        FormTemplateEntity template = findTemplate(formTmplId);

        /*
         * 비활성 템플릿은 여기서만 막힌다. 조회·수정은 그대로 열려 있고, 이 템플릿으로 이미
         * 만들어 둔 폼도 건드리지 않는다 — 폼 라벨 FORM_LABEL_NOT_USABLE과 같은 규칙이다.
         */
        if (!template.isActive()) {
            throw new GeneralException(FormErrorCode.FORM_TEMPLATE_NOT_USABLE);
        }

        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                creator,
                                FormFromTemplateRequest.titleOrDefault(request, template.getName()),
                                template.copyQuestionComposition(),
                                null,
                                null,
                                FormStatus.DRAFT));

        return FormFromTemplateResponse.of(form, template.getId());
    }

    /*
     * 이 폼의 문항 구성을 템플릿으로 저장.
     *
     * 폼의 구성을 **같은 검증기에 다시 태운다.** 폼이 저장될 때 이미 통과한 값이라 정상적인
     * 경우 아무것도 바뀌지 않지만(validate는 자기 출력에 대해 멱등이다), 규칙이 늘어난 뒤에
     * 저장돼 있던 옛 폼이 있다면 그 구성이 템플릿으로 굳어 새 폼의 출발점이 되는 것을 막는다 —
     * 템플릿의 존재 이유가 '여기서 시작하면 저장된다'이므로, 시작점이 저장되지 않는 값이면
     * 템플릿으로서 성립하지 않는다.
     *
     * validate가 돌려주는 값은 원본과 컬렉션을 공유하지 않는 새 구조라(List.copyOf·Map.copyOf)
     * 깊은 복사를 따로 하지 않아도 폼과 템플릿이 같은 객체를 가리키지 않는다. 반대 방향
     * (템플릿 → 폼)이 deepCopy를 쓰는 것과 결과가 같다.
     *
     * 폼의 상태·접수 기간·라벨·응답은 옮기지 않는다. 옮길 자리가 없다는 것이 form_tmpl을
     * 별도 테이블로 나눈 이유 그 자체다.
     */
    @Override
    @Transactional
    public FormTemplateResponse createTemplateFromForm(
            Long formId, TemplateFromFormRequest request, MemberEntity creator) {
        FormEntity form =
                formRepository
                        .findById(formId)
                        .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));

        QuestionCompositionContent composition =
                questionCompositionValidator.validate(form.getQuestionComposition());

        /*
         * 생성자는 폼의 생성자가 아니라 이 조작을 수행한 회원이다 — 남의 폼을 템플릿으로
         * 저장했다면 그 템플릿을 만든 사람은 저장한 쪽이다 (duplicate가 사본의 생성자를
         * 복제 수행자로 두는 것과 같은 판단).
         */
        return FormTemplateResponse.from(
                formTemplateRepository.saveAndFlush(
                        FormTemplateEntity.create(
                                creator,
                                TemplateFromFormRequest.nameOrDefault(request, form.getTitle()),
                                TemplateFromFormRequest.descriptionOf(request),
                                composition)));
    }

    private FormTemplateEntity findTemplate(Long formTmplId) {
        return formTemplateRepository
                .findById(formTmplId)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_TEMPLATE_NOT_FOUND));
    }
}
