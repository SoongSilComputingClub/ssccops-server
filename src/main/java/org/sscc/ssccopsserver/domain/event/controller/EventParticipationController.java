package org.sscc.ssccopsserver.domain.event.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantMutationResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantRegisterRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.service.EventParticipationService;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 행사 신청 목록·참가자 명단 API (ssccops#146 · D5·D8·D14·D16).
 *
 * **클래스 레벨 @RequireAuthority(EVENT_MANAGE)다** — 조회도 예외가 아니다. 이 API는 신청자의
 * 학번·학과·등급까지 내려주므로 핸들러가 하나 늘 때 애노테이션을 빠뜨리는 것만으로 개인정보가
 * 열리는 자리를 만들지 않는다(FormResponseController와 같은 판단). wave2가 행사 권한을 잘게
 * 쪼개지 않기로 했으므로(D8) 조회·등록·전이가 한 권한이다.
 *
 * **신청 목록을 폼 권한(RESPONSE_REVIEW)이 아니라 EVENT_MANAGE로 여는 것이 D8의 결정이다.**
 * 행사 운영자가 자기 행사의 신청을 보려고 폼 전체 권한을 받아야 한다면 그 권한이 다른 모든
 * 폼의 지원서까지 함께 연다. 같은 응답을 두 문이 열게 되지만, 행사 화면은 행사 경로만 쓴다.
 *
 * **심사 엔드포인트는 여기 없다.** 수락·거절은 기존 폼 응답 검토 API
 * (POST /v1/forms/{formId}/responses/{formRspnsId}/reviews, #141)를 그대로 쓴다 — 같은
 * form_rspns_hstry 행을 두 규칙이 다루기 시작하면 한쪽만 고쳐지는 날 이력 없는 승인이 생긴다.
 *
 * 컨트롤러 하나가 /applications와 /participants를 함께 맡는 것은 둘이 한 화면의 좌우이고
 * 권한이 같기 때문이다(FormLabelController가 두 경로를 함께 맡는 것과 같은 형태).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/events/{eventId}")
@RequireAuthority(AuthorityCode.EVENT_MANAGE)
public class EventParticipationController {

    private final EventParticipationService eventParticipationService;

    @Operation(
            summary = "행사 신청 목록 조회",
            description =
                    "연결된 폼의 응답 목록을 그대로 내려준다(폼 응답 목록 API와 같은 규칙·같은 응답 스키마)."
                            + " statusCode를 생략하면 작성 중(DRAFT)을 뺀 전부이며, 정렬은 제출 일시 내림차순이다."
                            + " **폼이 연결되지 않은 행사는 빈 목록이 아니라 409 EVENT_HAS_NO_FORM이다** —"
                            + " 빈 배열은 '아직 신청이 없다'로 읽히지만 실제로는 신청을 받을 수단이 없다."
                            + " 수락·거절 심사는 이 경로가 아니라 폼 응답 검토 API"
                            + " (POST /v1/forms/{formId}/responses/{formRspnsId}/reviews)를 쓴다.")
    @GetMapping("/applications")
    public ApiResponse<List<FormResponseSummaryResponse>> getApplications(
            @PathVariable Long eventId, @RequestParam(required = false) ResponseStatus statusCode) {
        return ApiResponse.success(eventParticipationService.getApplications(eventId, statusCode));
    }

    @Operation(
            summary = "행사 참가자 명단 조회",
            description =
                    "참가자 명단. ptcpSttsCd를 생략하면 **취소(CANCELLED)를 포함한 전부**다 —"
                            + " 명단은 활동 이력으로 영구 보존하므로(D16) 취소된 행도 명단에 남는다."
                            + " 정렬은 등록 순번(식별자 오름차순)이며 운영 화면의 신청 순서 참고용이다"
                            + " (대기 순번은 신청자에게 공개하지 않는다)."
                            + " formRspnsId는 신청 근거이고 수동 등록(전화·현장 접수)이면 null이다.")
    @GetMapping("/participants")
    public ApiResponse<List<EventParticipantResponse>> getParticipants(
            @PathVariable Long eventId,
            @RequestParam(required = false) EventParticipantStatus ptcpSttsCd) {
        return ApiResponse.success(eventParticipationService.getParticipants(eventId, ptcpSttsCd));
    }

    /*
     * 참가자 등록. 새 자원을 만드는 요청이라 201이며 Location은 명단을 가리킨다 — 참가자
     * 단건 조회 경로가 없으므로(명단으로 충분하다) 목록 경로를 준다.
     */
    @Operation(
            summary = "행사 참가자 등록",
            description =
                    "본문의 근거는 응답 기반 { formRspnsId, ptcpSttsCd } 또는 수동 { mbrId, ptcpSttsCd }이며"
                            + " **상호 배타**다(둘 다 오거나 둘 다 없으면 400 INVALID_PARTICIPANT_SOURCE)."
                            + " 응답 기반은 그 응답이 이 행사의 연결 폼 응답이어야 하고(아니면 404"
                            + " FORM_RESPONSE_NOT_FOUND) 심사가 끝나 ACCEPTED여야 한다(아니면 409"
                            + " APPLICATION_NOT_ACCEPTED). ptcpSttsCd는 CONFIRMED·WAITLISTED만 받으며"
                            + " 그 밖은 400 INVALID_PARTICIPANT_REGISTRATION_STATUS다."
                            + " 등록자(rgtrMbrId)는 요청 본문이 아니라 인증 주체에서 서버가 채운다."
                            + " 같은 회원의 중복 등록은 409 EVENT_PARTICIPANT_DUPLICATED다."
                            + " **정원 초과는 차단하지 않는다**(D5 — 정원은 참고치다). 대신 응답에"
                            + " confirmedCount·ptcpLmtCnt·capacityExceeded를 실어 화면이 경고한다."
                            + " 탈퇴·제명 회원의 등록도 막지 않고 warnings로 알린다.")
    @PostMapping("/participants")
    public ResponseEntity<ApiResponse<EventParticipantMutationResponse>> registerParticipant(
            @PathVariable Long eventId,
            @Valid @RequestBody EventParticipantRegisterRequest request,
            @CurrentMember MemberEntity registrant) {

        EventParticipantMutationResponse response =
                eventParticipationService.registerParticipant(eventId, request, registrant);
        URI location = URI.create("/v1/events/" + eventId + "/participants");
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 참가 상태 전이. 상태 한 필드를 고치는 요청이라 PATCH이며, DELETE는 두지 않는다 —
     * 명단은 영구 보존이라 취소도 행을 지우는 것이 아니라 상태다(D16).
     */
    @Operation(
            summary = "행사 참가 상태 변경",
            description =
                    "허용되는 전이는 WAITLISTED→CONFIRMED(승격)와 CONFIRMED→CANCELLED(취소) 둘뿐이며"
                            + " 그 밖은 400 INVALID_PARTICIPANT_STATUS_TRANSITION이다(같은 상태로의"
                            + " 재지정·취소 되돌리기·대기자 취소도 같은 400)."
                            + " 승격도 정원을 넘길 수 있으므로 등록과 같은"
                            + " confirmedCount·ptcpLmtCnt·capacityExceeded·warnings를 함께 내려준다."
                            + " 다른 행사의 참가자 식별자는 없는 참가자와 같은 404"
                            + " EVENT_PARTICIPANT_NOT_FOUND다."
                            + " **행을 지우는 DELETE는 없다** — 명단은 활동 이력으로 영구 보존한다(D16).")
    @PatchMapping("/participants/{eventPtcpId}")
    public ApiResponse<EventParticipantMutationResponse> changeParticipantStatus(
            @PathVariable Long eventId,
            @PathVariable Long eventPtcpId,
            @Valid @RequestBody EventParticipantStatusChangeRequest request) {
        return ApiResponse.success(
                eventParticipationService.changeParticipantStatus(eventId, eventPtcpId, request));
    }
}
