package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/*
 * 회차 기록 제출 요청(#135 · POST .../sessions). 재제출(PUT)도 같은 DTO를 쓴다 — 재제출은
 * 부분 수정이 아니라 전체 교체라 두 요청의 본문이 다를 이유가 없다(학술관리_API설계.md §3.4).
 *
 * 인증사진은 여기 없다 — 별도 presigned 경로(#137)로 분리한다(설계 결정 #1). 화면의 "제출"
 * 버튼은 하나지만 사진 업로드가 먼저 끝난 뒤 이 요청이 뒤따르는 순서로 클라이언트가
 * 오케스트레이션한다.
 *
 * 작성자(rgtrMbrId)도 없다 — @CurrentMember로 서버가 채운다. 받아 주면 남의 이름으로 기록을
 * 남길 수 있어 "누가 썼는가"가 증거가 되지 못한다(회원 등급·상태 변경의 chnrgMbrId와 같은 판단).
 *
 * attendances는 필수지만 빈 배열은 허용한다 — 아직 확정 팀원이 없는 활동의 첫 회차(OT 등)가
 * 실제로 있을 수 있고, 그때 "전원 출석 여부"의 답은 빈 명단이다.
 */
public record SessionSubmitRequest(
        @NotNull(message = "curriculumItemId는 필수입니다.") Long curriculumItemId,
        @NotNull(message = "realDt는 필수입니다.") LocalDate realDt,
        @NotBlank(message = "cn은 필수입니다.") String cn,
        String noticeCn,
        @NotNull(message = "attendances는 필수입니다.") @Valid
                List<SessionAttendanceSubmitRequest> attendances) {

    public List<SessionAttendanceSubmitRequest> attendancesOrEmpty() {
        return attendances == null ? List.of() : attendances;
    }
}
