package org.sscc.ssccopsserver.domain.event.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.dto.ShareLinkResponse;
import org.sscc.ssccopsserver.domain.share.service.ShareLinkService;
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
 *
 * 삭제(DELETE)·되살리기(/restore)·휴지통(/deleted)은 #347(ADR-0020)에서 돌아왔다 — ADR-0014가
 * 걷어낸 하드 삭제가 아니라 소프트 삭제다. 세 경로 모두 클래스 레벨 EVENT_MANAGE 그대로다:
 * 권한을 쪼갤 자식이 없기도 하지만(D8), 지울 수 있는 사람이 되돌릴 수 없으면 삭제를 감당
 * 가능하게 만드는 조건이 권한 배분 하나로 깨진다(FormController의 삭제·복구가 같은 FORM_WRITE인
 * 이유).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/events")
@RequireAuthority(AuthorityCode.EVENT_MANAGE)
public class EventController {

    private final EventService eventService;
    private final EventImageService eventImageService;
    private final ShareLinkService shareLinkService;

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

    /*
     * 휴지통 목록 (#347). 지워진 행사만 지운 시각 역순으로 돌려준다.
     *
     * **목록에 필터 값을 하나 더 두지 않고 경로를 나눴다.** eventSttsCd에 DELETED를 더하면 삭제
     * 여부가 게시 상태와 같은 축인 것처럼 읽히고(EventStatusAction 전이표의 축이 아니다), 지운
     * 행사가 '전체'(상태 미지정)에 섞여 들어온다. 삭제 여부는 운영진이 고르는 축이 아니라 언제나
     * 붙는 조건이다 (FormController의 /deleted와 같은 판단).
     *
     * 경로가 /{eventId}와 겹치지 않는 것은 리터럴 세그먼트가 경로 변수보다 먼저 매칭되기
     * 때문이다(Spring의 패턴 비교 규칙 · /my-applications가 이미 같은 자리에 있다). eventId가
     * Long이라 'deleted'는 어차피 변환되지 않는다.
     */
    @Operation(
            summary = "삭제된 행사 목록 조회",
            description =
                    "휴지통 화면. 소프트 삭제된 행사만 지운 시각(delDt) 역순으로 돌려준다. 항목의 모양은 행사 목록과 같고 delDt만 값이 있다 —"
                        + " 살아 있는 행사의 delDt는 언제나 null이다."
                        + " eventSttsCd·eventPhase·receiptStatus·confirmedCount는 지우기 직전 값 그대로이며,"
                        + " confirmedCount로 '이 행사에 참가자가 몇 명이었는가'를 보고 되살릴지 정한다. 되살리기는 POST"
                        + " /v1/events/{eventId}/restore다.")
    @GetMapping("/deleted")
    public ApiResponse<List<EventSummaryResponse>> getDeletedEvents() {
        return ApiResponse.success(eventService.getDeletedEvents());
    }

    @Operation(
            summary = "행사 단건 조회",
            description =
                    "행사 상세·편집 화면이 진입 시 호출한다. 본문(mtxtCn)은 md 원문 그대로다(D12)."
                            + " 없는 행사와 소프트 삭제된 행사는 같은 404 EVENT_NOT_FOUND로 응답한다.")
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
                            + " 폼 연결은 신청(제출 이후 응답·참가자)이 있어도 바꾸거나 해제할 수 있다 —"
                            + " 옛 폼의 응답은 그 폼에 남고 이미 등록된 참가자도 행사에 남지만,"
                            + " 옛 폼의 응답으로는 이 행사의 참가자를 등록할 수 없게 된다(404)."
                            + " 다른 행사에 전속된 폼을 연결하면 409 FORM_ALREADY_LINKED로 응답한다.")
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
     * 행사 소프트 삭제 (#347 · ADR-0020). ADR-0014가 걷어낸 DELETE가 돌아온 자리다 — 그때는
     * 하드 삭제였고 학술 활동이 딸린 행사에서 FK 위반 500이 났다. 이번에는 행이 남고(del_dt),
     * 학술 활동이 딸린 행사는 409로 끊는다.
     *
     * 삭제는 생성이 아니고 돌려줄 표현도 없으므로 204가 아니라 **본문 없는 200**이다 —
     * 모든 응답이 ApiResponse 봉투를 쓰는데 이 하나만 본문이 없으면 웹의 공통 응답 처리가
     * 예외를 하나 갖게 된다 (FormController.deleteForm과 같은 모양).
     */
    @Operation(
            summary = "행사 삭제",
            description =
                    "소프트 삭제다 — 운영 목록·상세·공개 목록·공개 상세·공유 링크·내 신청·참가자 명단에서"
                            + " 빠지지만 데이터는 남고 POST /v1/events/{eventId}/restore로 되살릴 수 있다."
                            + " **참가자가 있어도 지워진다** — 참가자 수를 보지 않는다."
                            + " 그 대가로 참가자의 '내 신청' 목록에서 그 항목이 사라진다(되살리면 그대로 돌아온다)."
                            + " 게시 상태는 그대로 남으므로 게시 중이던 행사는 되살리면 다시 게시 중이다."
                            + " R2 이미지는 지우지 않는다."
                            + " 지워진 행사는 없는 행사와 같은 404 EVENT_NOT_FOUND로 응답하며 이는 공개 상세·"
                            + "공유 링크가 존재 여부를 알려주지 않기 위해서다."
                            + " 학술 활동이 딸린 행사는 409 EVENT_HAS_ACADEMIC_PROGRAM(학술 쪽에서 프로그램을"
                            + " 정리한 뒤에야 지울 수 있다), 이미 지워진 행사는 409 ALREADY_DELETED,"
                            + " 없는 행사는 404 EVENT_NOT_FOUND다."
                            + " 지운 행사는 연결 폼을 붙잡지 않는다 — 그 폼을 다른 행사에 연결할 수 있다.")
    @DeleteMapping("/{eventId}")
    public ApiResponse<Void> deleteEvent(@PathVariable Long eventId) {
        eventService.deleteEvent(eventId);
        return ApiResponse.successWithNoData();
    }

    /*
     * 행사 되살리기 (#347). 삭제의 역이며 **이 경로가 있다는 것이 삭제를 여는 전제였다**
     * (ADR-0020 — 되돌릴 수 없으면 하드 삭제와 다를 것이 없고, 그때는 참가자의 기록이 영영 닫힌다).
     *
     * DELETE의 역이라고 해서 PUT이나 PATCH로 두지 않고 행위 경로를 쓰는 것은 /status·/duplicate와
     * 같은 판단이다 (AP-03). 새 자원이 생기지 않으므로 201이 아니라 200이다.
     */
    @Operation(
            summary = "행사 되살리기",
            description =
                    "소프트 삭제된 행사를 목록으로 되돌린다. 게시 상태(eventSttsCd)·폼 연결·일시·본문·참가자·"
                            + "이미지는 지울 때 그대로 남아 있으므로 지우기 직전 모습으로 돌아온다 — 게시 중이던"
                            + " 행사는 다시 공개된다. 참가자의 '내 신청' 항목도 함께 돌아온다."
                            + " 지워진 동안 그 행사의 폼을 다른 행사가 연결했으면 409 FORM_ALREADY_LINKED로"
                            + " 되살리지 않는다 — 그 행사에서 폼을 풀거나 그 행사를 지운 뒤 다시 시도한다."
                            + " 지워지지 않은 행사는 409 NOT_DELETED, 없는 행사는 404 EVENT_NOT_FOUND다."
                            + " 요구 권한은 삭제와 같은 EVENT_MANAGE다.")
    @PostMapping("/{eventId}/restore")
    public ApiResponse<Void> restoreEvent(@PathVariable Long eventId) {
        eventService.restoreEvent(eventId);
        return ApiResponse.successWithNoData();
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
                            + " 없는 행사와 소프트 삭제된 행사는 404 EVENT_NOT_FOUND다."
                            + " uploadUrl은 expiresInSeconds 뒤 만료되므로 저장해 두고 재사용하지 않는다.")
    @PostMapping("/{eventId}/images")
    public ResponseEntity<ApiResponse<EventImageUploadResponse>> issueImageUploadUrl(
            @PathVariable Long eventId, @Valid @RequestBody EventImageUploadRequest request) {
        EventImageUploadResponse response = eventImageService.issueUploadUrl(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }

    /*
     * 공유 링크 발급 (ssccops#312 · ADR-0016). 행사 편집 화면의 '공유' 버튼이 부른다.
     *
     * **이 셋이 `ShareController` 하나가 아니라 여기 있는 이유**는 `ShareLinkService` 주석에
     * 있다 — ssccops#306이 후보 ①로 확정했고 여기서는 형판을 반복한다.
     *
     * **채우는 것은 게시 전(DRAFT) 행사다.** 게시된 행사는 이미 `apps/www`의
     * `/events/{eventId}`로 익명이 열 수 있어 이 기능이 없어도 나눌 수 있었고, 없던 것은
     * "기획하는 동안 봐 달라"고 말할 방법이었다. 그래서 게시·보관된 행사에 오는 발급 요청은
     * 409 EVENT_SHARE_NOT_DRAFT로 거절한다 — 근거는 그 에러 코드 주석에 있다.
     *
     * **요구 권한은 클래스 레벨 EVENT_MANAGE 그대로다.** 행사 도메인은 조회/쓰기를 나눌 자식
     * 권한이 없어(D8) 이것이 곧 "이 행사를 볼 수 있는 사람"이며, ADR-0016의 *볼 수 있는
     * 사람이 공유할 수 있다*가 여기서는 이 한 권한으로 떨어진다.
     *
     * **멱등이다** — 살아 있는 링크가 있으면 그것을 돌려주므로 몇 번을 눌러도 결과가 같다.
     * 새 자원이 만들어지지 않는 호출이 있으므로 201이 아니라 200이다.
     */
    @Operation(
            summary = "행사 공유 링크 발급",
            description =
                    "게시 전(DRAFT) 행사의 미리보기를 익명에게 여는 토큰을 발급한다."
                            + " **게시된 행사에는 발급하지 않는다** — 이미 공개 주소"
                            + " (/events/{eventId})가 있어 토큰이 더하는 것은 폐기 기능뿐인데 그"
                            + " 폐기가 원본 공개 URL을 막지 못한다. 보관된 행사도 같다(409"
                            + " EVENT_SHARE_NOT_DRAFT). 없는 행사는 404 EVENT_NOT_FOUND다."
                            + " 살아 있는 링크가 있으면 새로 만들지 않고 그것을 돌려주므로"
                            + " 멱등이며 200이다. 응답은 토큰이고 링크 주소는 웹이 조립한다.")
    @PostMapping("/{eventId}/share")
    public ApiResponse<ShareLinkResponse> issueShareLink(
            @PathVariable Long eventId, @CurrentMember MemberEntity issuer) {
        // 없는 행사를 여기서 404로 끊는다 — shr_lnk에 FK가 없어 DB가 막아 주지 않는다.
        // 게시 여부 판정도 같은 호출이 한다(LY-02 — 상태 분기는 도메인의 일이다).
        eventService.requireShareableDraft(eventId);
        return ApiResponse.success(shareLinkService.issue(ShareTargetType.EVENT, eventId, issuer));
    }

    /*
     * 현재 공유 상태 (ssccops#312). 화면이 '공유하기'와 '공유 중지' 중 무엇을 그릴지 정한다.
     *
     * **발급과 달리 상태를 보지 않는다.** 게시 전에 발급한 링크는 행사가 게시된 뒤에도 살아
     * 있으므로(카드가 깨지지 않게 하려는 판단 — `EventSharePreviewProvider`), 그때 화면이
     * 살아 있는 링크를 보지 못하면 **폐기할 방법이 없어진다.**
     *
     * 공유한 적이 없거나 폐기했으면 data가 null인 200이다 — '공유 중이 아니다'는 오류가 아니라
     * 정상적인 조회 결과다(업무·하위 업무와 같은 판단).
     */
    @Operation(
            summary = "행사 공유 상태 조회",
            description =
                    "살아 있는 공유 링크가 있으면 토큰을, 없으면 data가 null인 200을 준다."
                            + " 발급과 달리 게시 상태를 보지 않는다 — 게시 전에 발급한 링크는"
                            + " 게시 뒤에도 살아 있고, 보이지 않으면 폐기할 수단이 없어진다."
                            + " 없는 행사는 404 EVENT_NOT_FOUND다.")
    @GetMapping("/{eventId}/share")
    public ApiResponse<ShareLinkResponse> getShareLink(@PathVariable Long eventId) {
        eventService.getEvent(eventId);
        return ApiResponse.success(
                shareLinkService.findActive(ShareTargetType.EVENT, eventId).orElse(null));
    }

    /*
     * 공유 중지 (ssccops#312). 폐기하면 그 토큰으로는 미리보기가 열리지 않는다.
     *
     * **게시된 행사에서도 폐기는 열어 둔다.** 발급을 막는 것과 어긋나 보이지만 방향이 반대다 —
     * 막는 쪽은 새 노출을 만드는 일이고 이쪽은 이미 있는 노출을 거두는 일이다. 다만 **이것이
     * `/events/{eventId}` 공개 주소를 막지는 않는다**: 게시된 행사에 발급을 거절하는 이유가
     * 바로 그것이며, 폐기가 지키는 것은 토큰 하나뿐이다.
     *
     * 살아 있는 링크가 없어도 조용히 지나가며 언제나 200이다.
     */
    @Operation(
            summary = "행사 공유 중지",
            description =
                    "공유 링크를 폐기한다. 그 토큰으로는 미리보기가 열리지 않는다(404)."
                            + " **게시된 행사의 공개 주소(/events/{eventId})는 막지 못한다** —"
                            + " 폐기가 거두는 것은 토큰뿐이다."
                            + " 살아 있는 링크가 없어도 200이며, 없는 행사는 404 EVENT_NOT_FOUND다.")
    @DeleteMapping("/{eventId}/share")
    public ApiResponse<Void> revokeShareLink(@PathVariable Long eventId) {
        eventService.getEvent(eventId);
        shareLinkService.revoke(ShareTargetType.EVENT, eventId);
        return ApiResponse.successWithNoData();
    }
}
