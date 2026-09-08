package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.dto.ShareLinkResponse;
import org.sscc.ssccopsserver.domain.share.service.ShareLinkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

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
    private final ShareLinkService shareLinkService;

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
     * 상세·목록은 인증만 요구한다 — 팀원도 자기 활동의 회차를 봐야 하고, 여기에 소유권을 걸면
     * 스터디장 한 사람 말고는 아무도 회차 이력을 볼 수 없다. 다른 활동의 회차 식별자로 부르면
     * 404다(경로의 활동으로 좁혀 조회한다).
     */
    @Operation(
            summary = "회차 상세 조회",
            description =
                    "계획(회차 번호·주제·예정일)과 출석부·집계를 함께 내린다."
                            + " 출석 인증사진(fileReference)은 **그 활동의 관계자**(팀원·스터디장/팀장·"
                            + "ACADEMIC_PROGRAM_MANAGE)에게만 실리며, 값은 만료가 있는 서명된 URL이다"
                            + "(expiresInSeconds로 남은 시간을 함께 내린다 — 만료되면 이 조회를 다시"
                            + " 부른다). 사진이 없는 회차와 관계자가 아닌 요청자는 같은 응답이다"
                            + "(fileReference: null).")
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
                            + " 실적 행이 없다는 뜻이라 미제출 회차는 커리큘럼 조회(#134)에서 본다.")
    @GetMapping
    public ApiResponse<List<SessionSummaryResponse>> searchSessions(
            @PathVariable Long academicProgramId,
            @Valid @ModelAttribute SessionCondition condition) {
        SessionSearchResponse result = sessionService.searchSessions(academicProgramId, condition);
        return ApiResponse.success(result.sessions(), result.page());
    }

    /* ── 공유 (ssccops#311 · ADR-0016) ─────────────────────── */

    /*
     * 회차 공유 링크 발급. 회차 상세 화면의 '공유' 버튼이 부른다.
     *
     * **프로그램과 별개 대상이다**(`ShareTargetType.ACADEMIC_SESSION`) — 프로그램은 모집을,
     * 회차는 그 회차를 뿌리는 단위라 뿌리는 시점도 받는 사람도 다르다. 묶으면 대상 ID가 무엇을
     * 가리키는지가 다시 갈린다.
     *
     * **@RequireAuthority를 걸지 않는 것은 이 컨트롤러의 조회 둘과 같은 이유다** — 자격의
     * 근거가 정적 권한 코드가 아니고, 회차 상세는 인증만 요구한다. *"볼 수 있는 사람이 공유할
     * 수 있다"*(ssccops#306)를 여기 적용한 결과이며, 소유권 정책(스터디장 본인)을 거는 쓰기
     * 둘과 갈리는 것은 **공유가 내용을 바꾸지 않기 때문이다** — 토큰이 주는 것은 이미 볼 수
     * 있는 제목·요약의 미리보기까지다(ADR-0016).
     *
     * 발급은 멱등이라 몇 번을 눌러도 결과가 같고, 새 자원이 만들어지지 않는 호출이 있어
     * 201이 아니라 200이다.
     */
    @Operation(
            summary = "회차 공유 링크 발급",
            description =
                    "발급은 멱등이다 — 살아 있는 링크가 있으면 그것을 돌려주므로 응답은 언제나 200이다."
                            + " 응답은 URL이 아니라 토큰(shrTkn)이며 웹이 `{자기 origin}/s/{token}`을"
                            + " 조립한다. 없는 회차·다른 활동의 회차는 같은 404다.")
    @PostMapping("/{sessionId}/share")
    public ApiResponse<ShareLinkResponse> issueShareLink(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @CurrentMember MemberEntity issuer) {
        // 없는 회차·다른 활동의 회차를 여기서 404로 끊는다 — 조회를 먼저 태우지 않으면
        // 존재하지 않는 대상에 토큰이 발급된다(shr_lnk에 FK가 없어 DB가 막아 주지 않는다).
        sessionService.getSession(academicProgramId, sessionId, issuer);
        return ApiResponse.success(
                shareLinkService.issue(ShareTargetType.ACADEMIC_SESSION, sessionId, issuer));
    }

    /*
     * 현재 공유 상태. 공유한 적이 없거나 폐기했으면 **data가 null인 200**이다 — '공유 중이
     * 아니다'는 오류가 아니라 정상적인 조회 결과다.
     */
    @Operation(
            summary = "회차 공유 상태 조회",
            description = "공유 중이 아니면 404가 아니라 data가 null인 200이다. 없는 회차면 404다.")
    @GetMapping("/{sessionId}/share")
    public ApiResponse<ShareLinkResponse> getShareLink(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @CurrentMember MemberEntity requester) {
        sessionService.getSession(academicProgramId, sessionId, requester);
        return ApiResponse.success(
                shareLinkService
                        .findActive(ShareTargetType.ACADEMIC_SESSION, sessionId)
                        .orElse(null));
    }

    /*
     * 공유 중지. 만료를 두지 않기로 했으므로(ADR-0016) 이것이 링크를 거두는 유일한 길이다.
     * 살아 있는 링크가 없어도 조용히 지나가며 언제나 200이다.
     */
    @Operation(
            summary = "회차 공유 중지",
            description =
                    "폐기하면 그 토큰으로는 미리보기가 404가 된다. 살아 있는 링크가 없어도 200이다 —"
                            + " 결과가 같은데 두 번째 요청만 오류로 만들 이유가 없다.")
    @DeleteMapping("/{sessionId}/share")
    public ApiResponse<Void> revokeShareLink(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @CurrentMember MemberEntity requester) {
        sessionService.getSession(academicProgramId, sessionId, requester);
        shareLinkService.revoke(ShareTargetType.ACADEMIC_SESSION, sessionId);
        return ApiResponse.successWithNoData();
    }
}
