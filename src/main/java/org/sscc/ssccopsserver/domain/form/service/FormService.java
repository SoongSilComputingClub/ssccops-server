package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/** 폼 조회·생성·수정·복제 (#32). 폼 관리 화면 네 개(목록·상세·편집·복제)가 전부 이 인터페이스를 소비한다. */
public interface FormService {

    /** 목록. 상태·라벨은 각각 선택이며 둘 다 주면 AND다 */
    List<FormSummaryResponse> getForms(FormStatus statusCode, Long labelId);

    FormDetailResponse getForm(Long formId);

    FormSaveResponse createForm(FormSaveRequest request, MemberEntity creator);

    FormSaveResponse updateForm(Long formId, FormSaveRequest request);

    /** 복제. 생성자는 원본 생성자가 아니라 복제를 수행한 회원이다 */
    FormDuplicateResponse duplicateForm(Long formId, MemberEntity creator);
}
