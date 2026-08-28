package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendanceResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.AttendanceService;
import org.sscc.ssccopsserver.domain.academicprogram.service.SessionFileReferenceService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 출석 정정·인증사진 업로드 API (#137 · 학술관리_API설계.md §3.5). 회차 기록 컨트롤러
 * (AcademicProgramSessionController)와 나누는 것은 이 셋이 회차 본문이 아니라 **회차에 매달린
 * 부속 자료**를 다루기 때문이다 — 통과하는 회차 상태 집합도 다르다
 * (SessionStatus.allowsCorrection).
 *
 * 인증사진이 '출석' 컨트롤러에 함께 있는 것은 둘이 같은 문을 쓰기 때문이다(소유권 + 확정되지
 * 않은 회차, SessionCorrectionPolicy). 자원 이름만 보고 파일을 나누면 그 문을 두 곳에서
 * 열어야 한다.
 *
 * @RequireAuthority를 쓰지 않는 이유는 회차 기록 컨트롤러와 같다 — 자격의 근거가 정적 권한
 * 코드가 아니라 "이 활동의 leadrMbrId 본인인가"라는 레코드 단위 관계라 AOP가 알 수 없고,
 * 판정은 서비스 레이어가 전담한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/sessions/{sessionId}")
public class AcademicProgramAttendanceController {

    private final AttendanceService attendanceService;
    private final SessionFileReferenceService sessionFileReferenceService;

    /*
     * 조회는 인증만 요구한다 — 팀원도 자기 활동의 출석부를 봐야 한다. 회차 상세(#135)도 같은
     * 명단을 싣지만 이 경로는 출석부 화면 전용이라 줄마다 attendanceId를 함께 내린다
     * (AttendanceResponse 주석).
     */
    @Operation(
            summary = "출석부 조회",
            description = "그 회차의 출석 명단을 등록 순서로 내린다. 다른 활동의 회차 식별자로 부르면 404다.")
    @GetMapping("/attendances")
    public ApiResponse<List<AttendanceResponse>> getAttendances(
            @PathVariable Long academicProgramId, @PathVariable Long sessionId) {
        return ApiResponse.success(attendanceService.getAttendances(academicProgramId, sessionId));
    }

    /*
     * 부분 갱신이므로 PATCH다 — 요청에 실린 참가자의 체크 값만 바뀌고 명단은 그대로다
     * (SubWorkController.updateChecklistItem과 같은 자리). 상태를 PATCH로 쓰지 않는다는
     * 원칙과 어긋나지 않는다: 여기서 바꾸는 것은 회차 상태가 아니라 출석 한 줄의 값이다.
     */
    @Operation(
            summary = "출석 정정",
            description =
                    "회차 기록과 별도로 출석만 고친다. 스터디장/팀장 본인만 부를 수 있고, 승인 완료"
                            + "(APPROVED)된 회차는 409 SESSION_NOT_EDITABLE이다. 그 회차 출석부에 줄이 없는"
                            + " 참가자나 중복된 참가자는 400 INVALID_ATTENDANCE_TARGET이며, 출석 대상을"
                            + " 더하거나 빼는 것은 회차 기록 재제출(#135)의 몫이다.")
    @PatchMapping("/attendances")
    public ApiResponse<AttendancePatchResponse> correctAttendances(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @Valid @RequestBody AttendancePatchRequest request,
            @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                attendanceService.correctAttendances(
                        academicProgramId, sessionId, request, requester));
    }

    /*
     * 201인 것은 회차에 붙는 참조(file_rfrnc)가 이 요청으로 생기기 때문이다. 재업로드도
     * 같은 201이다 — UPSERT라 행은 새로 생기지 않지만(fileReferenceId가 유지된다) 새 업로드
     * 허가가 발급된다는 점에서 클라이언트가 하는 일이 같고, 두 경우를 200/201로 가르면 화면이
     * 사진 유무를 먼저 알아야 요청을 보낼 수 있다.
     */
    @Operation(
            summary = "출석 인증사진 업로드 URL 발급",
            description =
                    "R2에 직접 올릴 presigned PUT URL을 발급한다(서버는 파일 바이트를 받지 않는다)."
                            + " 회차당 1장이며 이미 사진이 있으면 거절하지 않고 참조를 교체한다(UPSERT)."
                            + " 웹은 응답의 contentType을 그대로 PUT의 Content-Type 헤더에 실어야 한다 —"
                            + " 그 값까지 서명에 들어가 있어 다르면 R2가 거절한다.")
    @PostMapping("/file-reference")
    public ResponseEntity<ApiResponse<FileReferenceUploadResponse>> issueFileReferenceUploadUrl(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @Valid @RequestBody FileReferenceUploadRequest request,
            @CurrentMember MemberEntity requester) {
        FileReferenceUploadResponse response =
                sessionFileReferenceService.issueUploadUrl(
                        academicProgramId, sessionId, request, requester);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }
}
