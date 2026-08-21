package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.operation.dto.AuthorizerAuthorityResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeActivationRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeSaveRequest;

/** 하위 업무 유형 관리 (OPS-018 · OPS-019). 승인 정책을 코드가 아닌 데이터로 두기 위한 API다. */
public interface SubWorkTypeService {

    /** 유형 목록. useYn이 null이면 비활성까지 전부, 아니면 그 값과 같은 것만. */
    List<SubWorkTypeResponse> getSubWorkTypes(Boolean useYn);

    /** 유형 폼의 승인자 선택지 — 결재 권한 코드와 표시명 (#123). */
    List<AuthorizerAuthorityResponse> getAuthorizerAuthorities();

    SubWorkTypeResponse createSubWorkType(SubWorkTypeSaveRequest request);

    SubWorkTypeResponse updateSubWorkType(Long subWorkTypeId, SubWorkTypeSaveRequest request);

    SubWorkTypeResponse changeActivation(Long subWorkTypeId, SubWorkTypeActivationRequest request);
}
