package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/** 폼 조회·생성·수정·복제(#32)와 접수 상태 전이(#33). 폼 관리 화면이 전부 이 인터페이스를 소비한다. */
public interface FormService {

    /** 목록. 상태·라벨은 각각 선택이며 둘 다 주면 AND다 */
    List<FormSummaryResponse> getForms(FormStatus statusCode, Long labelId);

    FormDetailResponse getForm(Long formId);

    FormSaveResponse createForm(FormSaveRequest request, MemberEntity creator);

    /** 수정. 상태(formSttsCd)는 바꾸지 않는다 — 상태를 바꾸는 길은 changeStatus 하나다 (#33) */
    FormSaveResponse updateForm(Long formId, FormSaveRequest request);

    /*
     * 접수 상태 전이 (#33). 전이표는 FormStatusAction이, 전이 가능 여부와 사전 검증은
     * FormEntity가 갖는다.
     *
     * 수행자(actor)를 받지 않는다. 데이터사전에 폼 상태 이력 테이블이 없어 기록할 자리가
     * 없고, 감사 로그(#8)가 확정되기 전에 여기서 새 테이블을 만들면 나중에 두 벌이 된다.
     * 회원 여부 자체는 컨트롤러의 @CurrentMember가 끊는다.
     */
    FormStatusChangeResponse changeStatus(Long formId, FormStatusChangeRequest request);

    /** 복제. 생성자는 원본 생성자가 아니라 복제를 수행한 회원이다 */
    FormDuplicateResponse duplicateForm(Long formId, MemberEntity creator);
}
