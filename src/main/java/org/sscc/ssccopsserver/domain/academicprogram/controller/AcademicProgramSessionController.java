package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSubmitRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.SessionService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import lombok.RequiredArgsConstructor;

/*
 * 회차 기록 작성·조회 API (#135 · 학술관리_API설계.md §1·§3.4). 활동 조회 컨트롤러와 나누는
 * 것은 인가 레벨이 메서드마다 갈리기 때문이다 — 쓰기 둘은 스터디장/팀장 본인(소유권 정책),
 * 조회 둘은 인증만이다.
 *
 * @RequireAuthority를 쓰지 않는다. 자격의 근거가 정적 권한 코드가 아니라 "이 활동의
 * leadrMbrId 본인인가"라는 레코드 단위 관계라 AOP가 알 수 없고, 판정은 서비스 레이어의
 * AcademicProgramOwnershipPolicy가 전담한다(SubWorkOwnershipPolicy와 같은 층).
 *
 * 최초 제출과 재제출을 메서드가 아니라 경로로 나눈다 — 성립 조건이 서로 배타적이라 같은
 * 경로에 두면 본문 모양으로 갈리는 분기가 생긴다(SessionService 주석).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/sessions")
public class AcademicProgramSessionController {

    private final SessionService sessionService;

    @Operation(
            summary = "회차 기록 제출",
            description =
                    "NOT_SUBMITTED(=실적 행이 없는) 커리큘럼 항목에만 쓸 수 있다. 출석은 이 요청에 함께"
                            + " 싣는다 — 확정 팀원(event_ptcp, CONFIRMED)이 아닌 대상이 있으면 400"
                            + " INVALID_ATTENDANCE_TARGET이다. 인증사진은 별도 경로(#137)이며 이 요청에"
                            + " 포함하지 않는다.")
    @PostMapping
    public ResponseEntity<ApiResponse<SessionDetailResponse>> submitSession(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody SessionSubmitRequest request,
            @CurrentMember MemberEntity requester) {
        SessionDetailResponse response =
                sessionService.submitSession(academicProgramId, request, requester);
        URI location =
                URI.create(
                        "/v1/academic-programs/"
                                + academicProgramId
                                + "/sessions/"
                                + response.sessionId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    @Operation(
            summary = "회차 기록 재제출",
            description =
                    "REVISION_REQUESTED 상태에서만 쓸 수 있다(그 밖의 상태는 409 SESSION_NOT_EDITABLE)."
                            + " 부분 수정이 아니라 전체 교체이며, 이전 제출 내용의 이력은 남기지 않는다."
                            + " 출석 대상은 확정 팀원과 **이 회차에 이미 줄이 있는 참가자**다 — 기록된 뒤"
                            + " 취소·대기로 바뀐 참가자의 줄은 요청에 없어도 지워지지 않고, 실으면 체크"
                            + " 값만 바뀐다(#617). 그 밖의 대상은 400 INVALID_ATTENDANCE_TARGET이다."
                            + " 생성이 아니므로 200이다.")
    @PutMapping("/{sessionId}")
    public ApiResponse<SessionDetailResponse> resubmitSession(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @Valid @RequestBody SessionSubmitRequest request,
            @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                sessionService.resubmitSession(academicProgramId, sessionId, request, requester));
    }

    /*
     * 상세·목록은 **그 활동의 관계자**(팀원·스터디장/팀장·학술국장)만 본다 (#688 · ssccops#611).
     * 소유권(스터디장 한 사람)을 걸면 팀원이 회차 이력을 못 보고, 그렇다고 «인증만»이면 동아리 전원이
     * 남의 프로그램 출석·진행 내용을 읽는다 — ⚠️ 2026-10-18 정정: 여기는 «인증만 요구한다»였고
     * 근거가 «팀원도 봐야 한다»였는데 코드는 팀원이 아니라 전원에게 열었다. 다른 활동의 회차
     * 식별자로 부르면 404다(경로의 활동으로 좁혀 조회한다).
     */
    @Operation(
            summary = "회차 상세 조회",
            description =
                    "계획(회차 번호·주제·예정일)과 출석부·집계를 함께 내린다."
                            + " 출석 인증사진(fileReference)은 **그 활동의 관계자**(팀원·스터디장/팀장·"
                            + "학술 조회 권한 ACADEMIC_PROGRAM_READ)에게만 실리며, 값은 만료가 있는 서명된 URL이다"
                            + "(expiresInSeconds로 남은 시간을 함께 내린다 — 만료되면 이 조회를 다시"
                            + " 부른다). **조회 자체가 그 활동의 관계자만**이며 아니면 403 FORBIDDEN이다."
                            + " latestOpinion(마지막 수정요청 사유)은 스터디장/팀장·ACADEMIC_PROGRAM_READ 보유자에게만"
                            + " 실리고 팀원에게는 null이다.")
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDetailResponse> getSession(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                sessionService.getSession(academicProgramId, sessionId, requester));
    }

    @Operation(
            summary = "회차 목록 조회",
            description =
                    "활동 상세 화면 안에서 그 활동의 회차만 본다. 커서 페이징이며 기본 정렬은 회차 번호"
                            + " 오름차순이다. sttsCd에 NOT_SUBMITTED를 넘기면 언제나 빈 목록이다 — 그 상태는"
                            + " 실적 행이 없다는 뜻이라 미제출 회차는 커리큘럼 조회(#134)에서 본다. 그 활동의"
                            + " 관계자(팀원·스터디장/팀장·학술 조회 권한 ACADEMIC_PROGRAM_READ)만 부를 수 있고 아니면 403이다.")
    @GetMapping
    public ApiResponse<List<SessionSummaryResponse>> searchSessions(
            @PathVariable Long academicProgramId,
            @Valid @ModelAttribute SessionCondition condition,
            // springdoc이 필수 쿼리 파라미터로 실어 api-compat이 깨는 변경으로 본다 — 스펙에서 감춘다
            @Parameter(hidden = true) @CurrentMember MemberEntity requester) {
        SessionSearchResponse result =
                sessionService.searchSessions(academicProgramId, condition, requester);
        return ApiResponse.success(result.sessions(), result.page());
    }
}
