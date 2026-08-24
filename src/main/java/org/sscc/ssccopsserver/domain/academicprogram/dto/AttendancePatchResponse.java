package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AttendanceEntity;

/*
 * 출석 정정 응답 (#137 · PATCH .../attendances).
 *
 * 갱신된 줄만이 아니라 **그 회차 출석부 전체와 집계**를 돌려준다(체크리스트 부분 갱신 관례 —
 * SubWorkChecklistItemUpdateResponse가 항목 하나와 함께 checklistSummary를 싣는 것과 같은
 * 이유). 화면이 체크 직후 "N/M 참석"을 다시 그려야 하는데 세는 규칙을 클라이언트로 넘기면
 * 회차 상세(#135)·목록의 같은 이름 값과 갈릴 수 있다.
 *
 * 회차 상태(sttsCd)는 싣지 않는다. 출석 정정은 전이가 아니라 값 수정이고, 상태가 바뀌는
 * 것처럼 보이는 응답을 내려 화면이 오해하게 만들지 않는다.
 */
public record AttendancePatchResponse(
        List<AttendanceResponse> attendances, int presentCount, int totalCount) {

    public static AttendancePatchResponse of(List<AttendanceEntity> attendances) {
        List<AttendanceResponse> rows = attendances.stream().map(AttendanceResponse::from).toList();
        int presentCount = (int) rows.stream().filter(AttendanceResponse::presentYn).count();
        return new AttendancePatchResponse(rows, presentCount, rows.size());
    }
}
