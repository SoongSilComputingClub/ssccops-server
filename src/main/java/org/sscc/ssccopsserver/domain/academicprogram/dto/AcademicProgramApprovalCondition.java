package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalPoint;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 승인 이력 조회(#139 · GET /v1/academic-programs/{id}/approvals)의 쿼리 파라미터.
 *
 * **aprvPntCd는 SESSION·COMPLETION 둘뿐이다.** 기획안 승인(구 PROPOSAL 지점)은 2026-08-24
 * 재설계로 학술 도메인 밖으로 나갔고(폼 응답 검토 이력 form_rspns_rvw_hstry, #141), 그 사실은
 * AcademicProgramApprovalPoint에 이미 적혀 있다 — 그래서 여기서 목록을 다시 적지 않고 enum에
 * 없는 값이면 400 INVALID_CODE_VALUE로 끊는다. 어휘를 두 곳에 적으면 지점이 하나 늘 때 한쪽만
 * 고쳐진다.
 *
 * **sort 파라미터를 받지 않는다.** 이 목록의 순서는 처리 최신순 하나뿐이고(정렬 키가 곧
 * 식별자다, AcademicProgramApprovalCursor 주석), 고를 수 없는 값을 받아 두면 오타 난 정렬로
 * 목록을 받은 클라이언트가 서버가 정렬해 준 줄 알고 그대로 그린다. 실제로 적용된 정렬은
 * page.sort에 실려 나간다.
 */
public record AcademicProgramApprovalCondition(
        String aprvPntCd,
        Long sessionId,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = AcademicProgramApprovalCondition.MAX_SIZE,
                        message =
                                "size는 " + AcademicProgramApprovalCondition.MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** 서버가 실제로 적용하는 유일한 정렬. page.sort로 그대로 실려 나간다 */
    public static final String SORT = "-approvalId";

    public AcademicProgramApprovalSearchQuery toQuery(Long academicProgramId) {
        return new AcademicProgramApprovalSearchQuery(
                academicProgramId,
                toPoint(),
                sessionId,
                size == null ? DEFAULT_SIZE : size,
                AcademicProgramApprovalCursor.decode(cursor));
    }

    private AcademicProgramApprovalPoint toPoint() {
        if (aprvPntCd == null || aprvPntCd.isBlank()) {
            return null;
        }
        try {
            return AcademicProgramApprovalPoint.valueOf(aprvPntCd.strip());
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
        }
    }
}
