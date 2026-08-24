package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;

/*
 * 학술 활동 유형 응답 (#130). 목록·등록·수정·사용 전환이 모두 이 한 모양을 쓴다 — 저장 응답을
 * 따로 두면 한쪽만 필드가 늘었을 때 두 응답이 조용히 어긋난다(SubWorkTypeResponse와 같은 이유).
 */
public record AcademicProgramTypeResponse(
        String typeCd, String typeNm, Integer indctSeqno, boolean useYn) {

    public static AcademicProgramTypeResponse from(AcademicProgramTypeEntity type) {
        return new AcademicProgramTypeResponse(
                type.getCode(), type.getName(), type.getDisplayOrder(), type.isActive());
    }
}
