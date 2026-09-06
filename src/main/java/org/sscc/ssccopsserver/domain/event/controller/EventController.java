package org.sscc.ssccopsserver.domain.event.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.sscc.ssccopsserver.domain.event.dto.EventDuplicateResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventSaveRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventSummaryResponse;
import org.sscc.ssccopsserver.domain.event.service.EventImageService;
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
    private final EventImageService eventImageService;

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
     * 행사 복제 (ssccops#198). 상태를 PUT으로 쓰는 대신 행위 경로를 두는 것과 같은 이유로
     * /duplicate를 쓴다 (AP-03 · 폼 /duplicate 선례). 새 행사가 생기므로 201 + Location이다.
     * 권한은 클래스 레벨 EVENT_MANAGE 그대로다 — 생성과 같은 문이다.
     */
    @Operation(
            summary = "행사 복제",
            description =
                    "기존 행사를 본떠 새 행사를 DRAFT로 만든다. 본문·분류·장소·정원·대표 이미지는"
                            + " 승계하고, 제목에는 ' (복사본)'이 붙으며 행사 기간은 비운다."
                            + " 참가자 명단은 따라오지 않는다."
                            + " 원본에 폼이 연결돼 있으면 **폼도 함께 복제해 사본을 연결한다**"
                            + " (두 행사가 같은 신청서를 공유하지 않는다) — 응답의 formId가 그 새 폼이다."
                            + " 본문·대표 이미지가 이 행사의 이미지를 가리키면 오브젝트를 사본의 키로"
                            + " 복사하고 주소를 옮겨 적는다(원본을 보관해도 사본이 깨지지 않는다)."
                            + " 없는 행사는 404 EVENT_NOT_FOUND, 이미지 복사에 실패하면"
                            + " 502 EVENT_IMAGE_COPY_FAILED이며 그때는 아무것도 만들어지지 않는다.")
    @PostMapping("/{eventId}/duplicate")
    public ResponseEntity<ApiResponse<EventDuplicateResponse>> duplicateEvent(
            @PathVariable Long eventId, @CurrentMember MemberEntity creator) {
        EventDuplicateResponse response = eventService.duplicateEvent(eventId, creator);
        URI location = URI.create("/v1/events/" + response.eventId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 본문 이미지 업로드 URL 발급 (#161 · D6). **서버는 파일 바이트를 받지 않는다** — 이
     * 컨트롤러에 multipart 핸들러를 더하지 말 것. 업로드는 웹 → R2 직행이고 서버가 하는 일은
     * 짧게 사는 서명된 PUT 주소를 내주는 것뿐이다.
     *
     * 새 오브젝트 키를 발급하는 요청이라 201이며 **Location은 두지 않는다** — 그 시점에는
     * 아직 아무것도 올라와 있지 않아 가리킬 자원이 없다(응답의 imageUrl이 업로드 뒤 열릴
     * 주소다). 권한은 클래스 레벨 EVENT_MANAGE 그대로다.
     *
     * imageUrl은 R2의 주소가 아니라 **우리 API의 리다이렉트 주소**다 (#208 ·
     * GET /public/v1/events/{eventId}/images/{fileName}). 버킷이 비공개라(ssccops#156) 읽기도
     * 서명이 필요하고, 서명은 만료되는데 이 값은 본문 마크다운에 문자열로 굳기 때문이다.
     */
    @Operation(
            summary = "행사 본문 이미지 업로드 URL 발급",
            description =
                    "R2로 직접 PUT 할 presigned URL을 발급한다(서버는 파일을 받지 않는다)."
                            + " 요청은 확장자(fileExt)와 크기(fileSize)뿐이며 형식은 서버가 정한다 —"
                            + " 앞의 점·대소문자는 서버가 정규화하므로 jpg·.JPG·jpeg 모두 같은 형식이다."
                            + " 웹은 uploadUrl로 **응답의 contentType을 그대로 Content-Type 헤더에 붙여**"
                            + " 한 번 PUT 한다(파일에서 다시 읽으면 서명과 어긋난다)."
                            + " 본문 마크다운에는 imageUrl을 넣는다."
                            + " imageUrl은 만료되지 않는 우리 API의 주소이며, 열릴 때마다 서명된"
                            + " R2 GET URL로 302 리다이렉트된다(버킷은 비공개다)."
                            + " 허용 확장자는 png·jpg·jpeg·webp·gif이며 그 밖의 확장자는"
                            + " 400 UNSUPPORTED_IMAGE_TYPE, 10MB를 넘으면 413 IMAGE_TOO_LARGE,"
                            + " 없는 행사는 404 EVENT_NOT_FOUND다."
                            + " uploadUrl은 expiresInSeconds 뒤 만료되므로 저장해 두고 재사용하지 않는다.")
    @PostMapping("/{eventId}/images")
    public ResponseEntity<ApiResponse<EventImageUploadResponse>> issueImageUploadUrl(
            @PathVariable Long eventId, @Valid @RequestBody EventImageUploadRequest request) {
        EventImageUploadResponse response = eventImageService.issueUploadUrl(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }
}
