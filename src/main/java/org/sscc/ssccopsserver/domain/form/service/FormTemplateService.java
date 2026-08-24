package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.form.dto.FormFromTemplateRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormFromTemplateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateUseRequest;
import org.sscc.ssccopsserver.domain.form.dto.TemplateFromFormRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 폼 템플릿 관리와 템플릿↔폼 전환 (#142).
 *
 * 폼 CRUD(FormService)와 서비스를 나눈 것은 템플릿이 폼과 수명이 다른 자원이기 때문이다 —
 * 템플릿은 폼이 하나도 없어도 만들어지고, 그 템플릿으로 만든 폼을 전부 지워도 남는다.
 * 이것이 '폼에 플래그' 대신 별도 테이블을 고른 이유와 같은 판단의 서비스 층 대응이다.
 *
 * 반대로 **문항 구성의 규칙은 나누지 않는다.** 이 서비스도 폼과 같은
 * QuestionCompositionValidator를 그대로 부른다 — 규칙을 한 벌 더 적으면 템플릿에서는 통과한
 * 구성이 폼 저장에서 거절되고, 사용자는 화면이 만들어 준 폼을 저장할 수 없게 된다.
 */
public interface FormTemplateService {

    /*
     * 템플릿 목록. useYn이 null이면 전체(관리 화면), true면 활성만('템플릿에서 시작하기'),
     * false면 비활성만이다 — FormLabelService.getLabels와 같은 필터 규칙이다.
     *
     * 문항 구성(qitemCpstCn)은 싣지 않는다. 폼 목록과 같은 규칙이다.
     */
    List<FormTemplateResponse> getTemplates(Boolean useYn);

    /** 템플릿 상세. 문항 구성을 통째로 싣는다 — 템플릿 편집기가 이 응답을 초안으로 받아 쓴다 */
    FormTemplateDetailResponse getTemplate(Long formTmplId);

    /** 템플릿 생성. 생성자는 인증 주체이며 새 템플릿은 항상 활성이다 */
    FormTemplateResponse createTemplate(FormTemplateSaveRequest request, MemberEntity creator);

    /*
     * 템플릿 수정. 문항 구성은 전체 교체이며 사용 여부는 건드리지 않는다.
     * **비활성 템플릿도 고칠 수 있다** — 비활성은 "새로 고를 수 없다"는 뜻이지 "잠긴다"가
     * 아니다. 내려놓은 템플릿의 오타를 고친 뒤 다시 켜는 것이 정상 경로다.
     */
    FormTemplateResponse updateTemplate(Long formTmplId, FormTemplateSaveRequest request);

    /*
     * 사용 여부 전환. 지우지 않는다 — 폼 라벨·하위 업무 유형과 같은 축이다.
     * 이 템플릿으로 이미 만들어 둔 폼은 한 건도 건드리지 않는다(애초에 연결이 없다).
     */
    FormTemplateResponse changeTemplateUsage(Long formTmplId, FormTemplateUseRequest request);

    /*
     * 이 템플릿으로 새 폼 만들기. 문항 구성을 깊은 복사한 DRAFT 폼이 생기고 접수 기간·라벨은
     * 비어 있다. 비활성 템플릿이면 400 FORM_TEMPLATE_NOT_USABLE.
     *
     * 만들어진 폼은 템플릿을 가리키는 컬럼을 갖지 않는다 — 복사는 한 번뿐인 사건이고, 이후
     * 템플릿을 고쳐도 폼이 따라 바뀌어서는 안 되기 때문이다.
     */
    FormFromTemplateResponse createFormFromTemplate(
            Long formTmplId, FormFromTemplateRequest request, MemberEntity creator);

    /*
     * 이 폼의 문항 구성을 템플릿으로 저장. 저장 대상은 경로가 가리키는 폼의 현재 구성이며
     * 요청 본문이 문항을 실어 보내지 않는다.
     *
     * 폼의 접수 기간·상태·라벨·응답은 옮기지 않는다 — 템플릿에 그것들을 담을 자리가 없다는
     * 사실이 곧 form_tmpl을 별도 테이블로 나눈 이유다.
     */
    FormTemplateResponse createTemplateFromForm(
            Long formId, TemplateFromFormRequest request, MemberEntity creator);
}
