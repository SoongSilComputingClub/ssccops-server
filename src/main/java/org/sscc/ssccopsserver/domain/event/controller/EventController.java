package org.sscc.ssccopsserver.domain.event.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventSaveRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventSummaryResponse;
import org.sscc.ssccopsserver.domain.event.service.EventService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 행사 관리 API (ssccops#139). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * **클래스 레벨 @RequireAuthority(EVENT_MANAGE)다** — wave2가 행사 권한을 잘게 쪼개지 않기로
 * 했고(D8 · AuthorityCode.EVENT_MANAGE 주석), 폼처럼 조회/쓰기/전이를 나눌 자식 권한이 없다.
 * 공개(익명) 행사 조회는 이 컨트롤러가 아니라 별도 이슈(ssccops#143)의 몫이다 — 여기에는
 * permitAll 경로가 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/events")
@RequireAuthority(AuthorityCode.EVENT_MANAGE)
public class EventController {

    private final EventService eventService;

    /*
     * 행사 목록. 두 필터는 각각 선택이며 둘 다 주면 AND다. 본문(mtxtCn)은 목록에 싣지 않는다 —
     * md 본문은 10만 자까지 갈 수 있어 행사 수만큼 곱해진다.
     */
    @Operation(
            summary = "행사 목록 조회",
            description =
                    "행사 관리 화면의 목록. eventClsfCd·eventSttsCd는 각각 선택이며 둘 다 주면 AND로 걸린다."
                            + " eventPhase는 행사 일시에서 조회 시점에 파생한 값이고, receiptStatus는"
                            + " 연결된 폼의 접수 상태다(폼이 없으면 null)."
                            + " confirmedCount는 확정(CONFIRMED) 참가자만 센다."
                            + " 목록에는 본문(mtxtCn)을 싣지 않는다.")
    @GetMapping
    public ApiResponse<List<EventSummaryResponse>> getEvents(
            @RequestParam(required = false) String eventClsfCd,
            @RequestParam(required = false) EventStatus eventSttsCd) {
        return ApiResponse.success(eventService.getEvents(eventClsfCd, eventSttsCd));
    }

    @Operation(
            summary = "행사 단건 조회",
            description =
                    "행사 상세·편집 화면이 진입 시 호출한다. 본문(mtxtCn)은 md 원문 그대로다(D12)."
                            + " 없는 행사는 404 EVENT_NOT_FOUND로 응답한다.")
    @GetMapping("/{eventId}")
    public ApiResponse<EventDetailResponse> getEvent(@PathVariable Long eventId) {
        return ApiResponse.success(eventService.getEvent(eventId));
    }

    @Operation(
            summary = "행사 생성",
            description =
                    "생성자(creatrMbrId)는 인증 주체에서 서버가 채우므로 요청 본문에 넣지 않는다."
                            + " 상태는 항상 DRAFT다 — 게시는 POST /v1/events/{eventId}/status의 몫이다."
                            + " 없는 분류는 404 EVENT_CLASSIFICATION_NOT_FOUND, 다른 행사에 전속된 폼은"
                            + " 409 FORM_ALREADY_LINKED, 본문이 10만 자를 넘으면"
                            + " 413 EVENT_CONTENT_TOO_LARGE로 응답한다.")
    @PostMapping
    public ResponseEntity<ApiResponse<EventDetailResponse>> createEvent(
            @Valid @RequestBody EventSaveRequest request, @CurrentMember MemberEntity creator) {
        EventDetailResponse response = eventService.createEvent(request, creator);
        URI location = URI.create("/v1/events/" + response.eventId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 행사 수정. 상태 필드가 본문에 아예 없다 — 상태를 바꾸는 길은 액션 경로 하나뿐이다
     * (폼 PUT 패턴). 폼 연결 규칙(D11)은 서비스가 검증한다.
     */
    @Operation(
            summary = "행사 수정",
            description =
                    "행사 내용을 통째로 교체한다. 상태(eventSttsCd)는 이 API로 바꿀 수 없다 —"
                            + " POST /v1/events/{eventId}/status를 쓴다."
                            + " 신청(제출 이후 응답 또는 참가자)이 발생한 뒤 폼 연결을 바꾸거나 해제하면"
                            + " 409 EVENT_FORM_IN_USE, 다른 행사에 전속된 폼을 연결하면"
                            + " 409 FORM_ALREADY_LINKED로 응답한다.")
    @PutMapping("/{eventId}")
    public ApiResponse<EventDetailResponse> updateEvent(
            @PathVariable Long eventId, @Valid @RequestBody EventSaveRequest request) {
        return ApiResponse.success(eventService.updateEvent(eventId, request));
    }

    /*
     * 게시 상태 전이. 상태를 PUT의 필드로 넘기는 경로는 두지 않는다 (AP-03 · 폼 #33 선례) —
     * 내용을 고치는 것과 게시하는 것은 검증·감사 대상이 다른 행위다.
     *
     * 상태 변경은 생성이 아니므로 200이다 (LY-06). 전이 가능 여부는 도메인이 판단하므로
     * 여기서 분기하지 않는다 (LY-02).
     */
    @Operation(
            summary = "행사 게시 상태 전이",
            description =
                    "action은 PUBLISH·RETRACT·ARCHIVE·REPUBLISH다. DRAFT↔PUBLISHED·"
                            + "PUBLISHED→ARCHIVED·ARCHIVED→PUBLISHED만 허용하며 그 밖의 전이는"
                            + " 400 INVALID_EVENT_STATUS_TRANSITION으로 응답한다.")
    @PostMapping("/{eventId}/status")
    public ApiResponse<EventDetailResponse> changeEventStatus(
            @PathVariable Long eventId, @Valid @RequestBody EventStatusChangeRequest request) {
        return ApiResponse.success(eventService.changeStatus(eventId, request));
    }

    /*
     * 행사 삭제. 204가 아니라 data가 null인 200인 것은 모든 응답이 ApiResponse 봉투를 쓰기
     * 때문이다 (#36·#65·#80과 같은 판단).
     */
    @Operation(
            summary = "행사 삭제",
            description =
                    "참가자가 하나도 없을 때만 지워진다(D9). 참가자가 있으면 409 EVENT_HAS_PARTICIPANT이며"
                            + " — 명단은 활동 이력으로 영구 보존되므로(D16) 그 경우 보관(ARCHIVE)이 경로다.")
    @DeleteMapping("/{eventId}")
    public ApiResponse<Void> deleteEvent(@PathVariable Long eventId) {
        eventService.deleteEvent(eventId);
        return ApiResponse.successWithNoData();
    }
}
