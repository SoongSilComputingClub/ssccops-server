package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeActivationRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeSaveRequest;

/** 학술 활동 유형 관리 (#130). study/project 구분을 코드가 아닌 데이터로 두기 위한 API다. */
public interface AcademicProgramTypeService {

    /** 유형 목록. indctSeqno 순, 비활성 유형도 포함한다. */
    List<AcademicProgramTypeResponse> getAcademicProgramTypes();

    AcademicProgramTypeResponse createAcademicProgramType(AcademicProgramTypeSaveRequest request);

    AcademicProgramTypeResponse updateAcademicProgramType(
            String typeCd, AcademicProgramTypeSaveRequest request);

    AcademicProgramTypeResponse changeActivation(
            String typeCd, AcademicProgramTypeActivationRequest request);
}
