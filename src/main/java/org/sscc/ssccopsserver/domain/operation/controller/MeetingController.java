package org.sscc.ssccopsserver.domain.operation.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaItemRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.service.MeetingService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import lombok.RequiredArgsConstructor;

/*
 * 회의 API (OPS-024~029). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 인가는 MEETING_MANAGE 권한이다(#9 준용) — 정의서의 '국장 이상'을 역할이 아니라 권한으로
 * 옮겼다. 시드에서 국장은 OPERATOR를 통해·회장·부회장·총무는 EXECUTIVE를 통해 이 권한에
 * 닿는다. 조회도 함께 막는다 — 회의 안건에는 내부 논의 내용이 그대로 들어 있다.
 * 전이(개회·회의록작성·종료)를 회의 책임자 본인으로 더 좁히는 것은 여기가 아니라
 * MeetingServiceImpl이 한다 — '무슨 일을 하는 사람인가'와 '이 회의의 의장 본인인가'는
 * 성질이 다르다(ApprovalAuthorityPolicy와 같은 경계).
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.MEETING_MANAGE)
@RequestMapping("/v1/meetings")
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    public ResponseEntity<ApiResponse<MeetingDetailResponse>> create(
            @Valid @RequestBody MeetingCreateRequest request,
            @CurrentMember MemberEntity registrant) {
        MeetingDetailResponse response = meetingService.createMeeting(request, registrant);
        URI location = URI.create("/v1/meetings/" + response.meetingId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    // 회의 목록 조회(신규). '회의' 화면 진입 시 카드 그리드를 채운다. 페이징이 없어 page 봉투를 싣지 않는다
    @GetMapping
    public ApiResponse<List<MeetingListItemResponse>> listMeetings() {
        return ApiResponse.success(meetingService.listMeetings());
    }

    // 회의 상세 조회(OPS-025). '회의 상세' 화면이 진입 시 호출한다. 소프트 삭제된 건은 서비스가 404로 막는다(LY-02)
    @GetMapping("/{meetingId}")
    public ApiResponse<MeetingDetailResponse> getMeeting(@PathVariable Long meetingId) {
        return ApiResponse.success(meetingService.getMeeting(meetingId));
    }

    /*
     * 회의 상태 전이(OPS-026). 개회·회의록작성·종료·취소가 모두 이 하나의 액션 경로를 쓴다.
     * 상태를 PATCH로 직접 쓰는 경로는 두지 않는다(POL-003·AP-03 준용). 상태 변경은 생성이
     * 아니므로 200이다(LY-06).
     */
    @PostMapping("/{meetingId}/transitions")
    public ApiResponse<MeetingTransitionResponse> transition(
            @PathVariable Long meetingId,
            @Valid @RequestBody MeetingTransitionRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(meetingService.transitionMeeting(meetingId, request, performer));
    }

    // 안건 목록 조회(OPS-027). 회의 상세(OPS-025)가 이미 안건을 함께 내리지만, 정의서가 별도 경로로 명시해 그대로 연다
    @GetMapping("/{meetingId}/agendas")
    public ApiResponse<List<MeetingAgendaResponse>> getAgendas(@PathVariable Long meetingId) {
        return ApiResponse.success(meetingService.getAgendas(meetingId));
    }

    /*
     * 안건 상정(OPS-027). 안건 제출자는 요청 본문이 아니라 인증 주체에서 온다(LY-05 준용) —
     * 정의서의 submitterId는 클라이언트가 지정할 수 없는 값으로 정정했다.
     */
    @PostMapping("/{meetingId}/agendas")
    public ResponseEntity<ApiResponse<MeetingAgendaResponse>> addAgenda(
            @PathVariable Long meetingId,
            @Valid @RequestBody MeetingAgendaItemRequest request,
            @CurrentMember MemberEntity submitter) {
        MeetingAgendaResponse response = meetingService.addAgenda(meetingId, request, submitter);
        URI location = URI.create("/v1/meetings/" + meetingId + "/agendas/" + response.agendaId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    // 안건 수정(OPS-028). 논의 내용·처리 구분만 바꾼다 — 연결 운영 건·제목·제출자는 이 API의 범위 밖이다(MeetingAgendaEntity.update
    // 참고)
    @PatchMapping("/{meetingId}/agendas/{agendaId}")
    public ApiResponse<MeetingAgendaResponse> updateAgenda(
            @PathVariable Long meetingId,
            @PathVariable Long agendaId,
            @Valid @RequestBody MeetingAgendaUpdateRequest request) {
        return ApiResponse.success(meetingService.updateAgenda(meetingId, agendaId, request));
    }

    // 안건 상정 철회(OPS-029). 회의 시작 전(SCHEDULED)만 허용한다 — 서비스가 그 밖의 시도를 409로 막는다
    @DeleteMapping("/{meetingId}/agendas/{agendaId}")
    public ApiResponse<Void> withdrawAgenda(
            @PathVariable Long meetingId, @PathVariable Long agendaId) {
        meetingService.withdrawAgenda(meetingId, agendaId);
        return ApiResponse.successWithNoData();
    }
}
