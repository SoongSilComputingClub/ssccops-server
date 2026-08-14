package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.form.dto.FormLabelAssignmentResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelCreateRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelUpdateRequest;

/*
 * 폼 라벨 관리·지정 (#34).
 *
 * 폼 CRUD(#32)와 서비스를 나눈 것은 라벨이 폼과 수명이 다른 자원이기 때문이다 — 라벨은 폼이
 * 하나도 없어도 만들어지고 관리된다. 다만 폼에 라벨을 지정하는 규칙은 한 곳에만 있어야 하므로
 * replaceFormLabels가 그 유일한 진입점이다. 폼 등록·수정의 labelIds도 이 메서드를 호출한다.
 */
public interface FormLabelService {

    /*
     * 라벨 목록. useYn이 null이면 전체(관리 화면), true면 활성만(지정·필터 화면),
     * false면 비활성만이다. 활성 여부로 나뉘는 화면이 실제로 둘이라 필터를 값으로 받는다.
     */
    List<FormLabelResponse> getLabels(Boolean useYn);

    /** 라벨 생성. 같은 이름이 이미 있으면 409 FORM_LABEL_NAME_DUPLICATED */
    FormLabelResponse createLabel(FormLabelCreateRequest request);

    /*
     * 사용 여부 토글. 비활성으로 내려도 이미 걸린 form_lbl_rel은 건드리지 않는다 —
     * 비활성은 "새로 달 수 없다"는 뜻이지 과거 분류를 지우라는 뜻이 아니다.
     */
    FormLabelResponse updateLabelUsage(Long formLblId, FormLabelUpdateRequest request);

    /*
     * 폼의 라벨 지정 전체 교체. 요청에 없는 연결은 지우고, 새로 생긴 것만 넣고, 유지되는 것은
     * 손대지 않아 crt_dt(지정 시각)가 보존된다.
     *
     * 요청 DTO가 아니라 식별자 목록을 받는 것은 폼 등록·수정(#32)이 자기 요청 본문 안의
     * labelIds로 같은 규칙을 태워야 하기 때문이다 — 두 경로가 각자 규칙을 갖게 두지 않는다.
     */
    List<FormLabelAssignmentResponse> replaceFormLabels(Long formId, List<Long> labelIds);
}
