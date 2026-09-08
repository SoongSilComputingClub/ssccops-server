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
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.dto.ShareLinkResponse;
import org.sscc.ssccopsserver.domain.share.service.ShareLinkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import lombok.RequiredArgsConstructor;

/*
 * 회의 API (OPS-024~029). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 인가는 메서드마다 갈린다(#101). 조회는 MEETING_READ(MEETING_MANAGE의 자식이라 그 보유자는
 * 별도 매핑 없이 통과한다), 회의 자체의 생성·전이는 MEETING_MANAGE(#9 준용 — 정의서의
 * '국장 이상'을 권한으로 옮긴 것, 국장은 OPERATOR를 통해·회장·부회장·총무는 EXECUTIVE를
 * 통해 닿는다), 안건 CRUD는 MEETING_AGENDA_WRITE다 —
 * 국원은 회의를 열거나 닫지는 못해도 안건은 작성할 수 있어야 하기 때문에 별도 코드로 뺐다
 * (MEETING_MANAGE 보유자는 트리 펼침으로 이 권한도 자동 포함한다). 전이(개회·회의록작성·
 * 종료)를 회의 책임자 본인으로 더 좁히는 것은 여기가 아니라 MeetingServiceImpl이 한다 —
 * '무슨 일을 하는 사람인가'와 '이 회의의 의장 본인인가'는 성질이 다르다(ApprovalAuthorityPolicy와
 * 같은 경계).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/meetings")
public class MeetingController {

    private final MeetingService meetingService;
    private final ShareLinkService shareLinkService;

    @RequireAuthority(AuthorityCode.MEETING_MANAGE)
    @PostMapping
    public ResponseEntity<ApiResponse<MeetingDetailResponse>> create(
            @Valid @RequestBody MeetingCreateRequest request,
            @CurrentMember MemberEntity registrant) {
        MeetingDetailResponse response = meetingService.createMeeting(request, registrant);
        URI location = URI.create("/v1/meetings/" + response.meetingId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    // 회의 목록 조회(신규). '회의' 화면 진입 시 카드 그리드를 채운다. 페이징이 없어 page 봉투를 싣지 않는다
    @RequireAuthority(AuthorityCode.MEETING_READ)
    @GetMapping
    public ApiResponse<List<MeetingListItemResponse>> listMeetings() {
        return ApiResponse.success(meetingService.listMeetings());
    }

    // 회의 상세 조회(OPS-025). '회의 상세' 화면이 진입 시 호출한다. 소프트 삭제된 건은 서비스가 404로 막는다(LY-02)
    @RequireAuthority(AuthorityCode.MEETING_READ)
    @GetMapping("/{meetingId}")
    public ApiResponse<MeetingDetailResponse> getMeeting(@PathVariable Long meetingId) {
        return ApiResponse.success(meetingService.getMeeting(meetingId));
    }

    /*
     * 회의 상태 전이(OPS-026). 개회·회의록작성·종료·취소가 모두 이 하나의 액션 경로를 쓴다.
     * 상태를 PATCH로 직접 쓰는 경로는 두지 않는다(POL-003·AP-03 준용). 상태 변경은 생성이
     * 아니므로 200이다(LY-06).
     */
    @RequireAuthority(AuthorityCode.MEETING_MANAGE)
    @PostMapping("/{meetingId}/transitions")
    public ApiResponse<MeetingTransitionResponse> transition(
            @PathVariable Long meetingId,
            @Valid @RequestBody MeetingTransitionRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(meetingService.transitionMeeting(meetingId, request, performer));
    }

    // 안건 목록 조회(OPS-027). 회의 상세(OPS-025)가 이미 안건을 함께 내리지만, 정의서가 별도 경로로 명시해 그대로 연다
    @RequireAuthority(AuthorityCode.MEETING_READ)
    @GetMapping("/{meetingId}/agendas")
    public ApiResponse<List<MeetingAgendaResponse>> getAgendas(@PathVariable Long meetingId) {
        return ApiResponse.success(meetingService.getAgendas(meetingId));
    }

    /*
     * 안건 상정(OPS-027). 안건 제출자는 요청 본문이 아니라 인증 주체에서 온다(LY-05 준용) —
     * 정의서의 submitterId는 클라이언트가 지정할 수 없는 값으로 정정했다.
     */
    @RequireAuthority(AuthorityCode.MEETING_AGENDA_WRITE)
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
    @RequireAuthority(AuthorityCode.MEETING_AGENDA_WRITE)
    @PatchMapping("/{meetingId}/agendas/{agendaId}")
    public ApiResponse<MeetingAgendaResponse> updateAgenda(
            @PathVariable Long meetingId,
            @PathVariable Long agendaId,
            @Valid @RequestBody MeetingAgendaUpdateRequest request) {
        return ApiResponse.success(meetingService.updateAgenda(meetingId, agendaId, request));
    }

    // 안건 상정 철회(OPS-029). 회의 시작 전(SCHEDULED)만 허용한다 — 서비스가 그 밖의 시도를 409로 막는다
    @RequireAuthority(AuthorityCode.MEETING_AGENDA_WRITE)
    @DeleteMapping("/{meetingId}/agendas/{agendaId}")
    public ApiResponse<Void> withdrawAgenda(
            @PathVariable Long meetingId, @PathVariable Long agendaId) {
        meetingService.withdrawAgenda(meetingId, agendaId);
        return ApiResponse.successWithNoData();
    }

    /*
     * 회의 삭제(#125). 자기 자신만 소프트 삭제한다 — 안건(mtg_dtl)은 지우지 않는다. 회의
     * 책임자(의장) 본인 여부는 보지 않고 MEETING_DELETE 보유 여부만으로 판정한다 — 개회·
     * 회의록작성·종료(TR-M1~M3)가 의장 본인만 허용하는 것과 다른 결정이다. 상태와 무관하게
     * 항상 허용한다(종료·취소된 회의도 삭제 가능).
     */
    @RequireAuthority(AuthorityCode.MEETING_DELETE)
    @DeleteMapping("/{meetingId}")
    public ApiResponse<Void> deleteMeeting(@PathVariable Long meetingId) {
        meetingService.deleteMeeting(meetingId);
        return ApiResponse.successWithNoData();
    }

    /*
     * 공유 링크 발급 (ssccops#310 · ADR-0016). 회의 상세 화면의 '공유' 버튼이 부른다.
     *
     * **이 셋이 `ShareController` 하나가 아니라 여기 있는 이유**는 `ShareLinkService` 주석에
     * 적혀 있다 — ssccops-server#306이 업무를 붙여 보고 후보 ①(도메인 컨트롤러마다 얇게)로
     * 확정했고, 세 번째 대상인 여기서 그 형판을 반복한다.
     *
     * **요구 권한이 MEETING_MANAGE가 아니라 MEETING_READ인 것은 의도된 것이다.** 이 토큰이 주는
     * 것은 제목·요약을 보여주는 미리보기까지이고(ADR-0016), 그것은 이 화면을 이미 보고 있는
     * 사람이 아는 것을 넘지 않는다 — 볼 수 있는 사람이 공유할 수 있다(업무와 같은 판단).
     * **안건을 담지 않기로 한 것이 이 판단을 떠받친다** — 카드가 회의 유형과 일시까지만 말하므로
     * 회의를 열고 닫는 권한까지 요구할 이유가 없다.
     *
     * **멱등이다.** 살아 있는 링크가 있으면 새로 만들지 않고 그것을 돌려주므로 몇 번을 눌러도
     * 결과가 같다 — 만료가 없어(ADR-0016) 누를 때마다 발급하면 죽지 않는 링크가 쌓인다.
     * 그래서 새 자원이 만들어지지 않는 호출이 있고, 201이 아니라 200으로 답한다.
     */
    @RequireAuthority(AuthorityCode.MEETING_READ)
    @PostMapping("/{meetingId}/share")
    public ApiResponse<ShareLinkResponse> issueShareLink(
            @PathVariable Long meetingId, @CurrentMember MemberEntity issuer) {
        // 없는 회의·삭제된 운영 건을 여기서 404로 끊는다 — 조회를 먼저 태우지 않으면 존재하지
        // 않는 대상에 토큰이 발급된다(shr_lnk에 FK가 없어 DB가 막아 주지 않는다).
        meetingService.getMeeting(meetingId);
        return ApiResponse.success(
                shareLinkService.issue(ShareTargetType.MEETING, meetingId, issuer));
    }

    /*
     * 현재 공유 상태 (ssccops#310). 화면이 '공유하기'와 '공유 중지' 중 무엇을 그릴지 정한다.
     *
     * 공유한 적이 없거나 폐기했으면 **data가 null인 200**이다 — 404가 아닌 것은 '공유 중이
     * 아니다'가 오류가 아니라 정상적인 조회 결과이기 때문이다(업무·하위 업무와 같은 판단).
     */
    @RequireAuthority(AuthorityCode.MEETING_READ)
    @GetMapping("/{meetingId}/share")
    public ApiResponse<ShareLinkResponse> getShareLink(@PathVariable Long meetingId) {
        meetingService.getMeeting(meetingId);
        return ApiResponse.success(
                shareLinkService.findActive(ShareTargetType.MEETING, meetingId).orElse(null));
    }

    /*
     * 공유 중지 (ssccops#310). 폐기하면 그 링크로는 미리보기도 상세도 열리지 않는다.
     *
     * 만료를 두지 않기로 했으므로(ADR-0016) **이것이 링크를 거두는 유일한 길이다.** 살아 있는
     * 링크가 없어도 조용히 지나가며 언제나 200이다 — 결과가 같은데 두 번째 요청만 오류로 만들
     * 이유가 없다.
     */
    @RequireAuthority(AuthorityCode.MEETING_READ)
    @DeleteMapping("/{meetingId}/share")
    public ApiResponse<Void> revokeShareLink(@PathVariable Long meetingId) {
        meetingService.getMeeting(meetingId);
        shareLinkService.revoke(ShareTargetType.MEETING, meetingId);
        return ApiResponse.successWithNoData();
    }
}
