package org.sscc.ssccopsserver.domain.event.controller;

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
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryCreateRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryUpdateRequest;
import org.sscc.ssccopsserver.domain.event.service.EventCategoryService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 행사 분류 관리 API (ssccops#140).
 *
 * **클래스 레벨 @RequireAuthority(EVENT_MANAGE)다** — 역할 분류(#80)가 조회를 열어 둔 것과
 * 갈리는데, 그쪽은 역할 목록의 필터 칩이 권한 없이도 그려져야 했지만 행사 분류를 쓰는 화면은
 * 행사 관리(EVENT_MANAGE 필요) 하나뿐이라 열어 둘 이유가 없다. 공개 앱의 분류 노출은 공개
 * 행사 API(ssccops#143)가 행사에 실어 내리는 eventClsfNm으로 충분하다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/event-categories")
@RequireAuthority(AuthorityCode.EVENT_MANAGE)
public class EventCategoryController {

    private final EventCategoryService eventCategoryService;

    /*
     * 분류 목록. 목록이지만 page 봉투를 싣지 않는다 (AP-11) — 운영진이 손으로 만드는 데이터라
     * 수십 건을 넘지 않는다.
     */
    @Operation(
            summary = "행사 분류 목록 조회",
            description =
                    "행사 분류 전체를 indctSeqno 오름차순으로 조회한다(동률은 코드순). 각 분류의"
                            + " eventCount는 그 분류를 쓰는 행사 수이며, 0이 아니면 삭제가 409"
                            + " EVENT_CLASSIFICATION_IN_USE로 거절된다.")
    @GetMapping
    public ApiResponse<List<EventCategoryResponse>> getCategories() {
        return ApiResponse.success(eventCategoryService.getCategories());
    }

    @Operation(
            summary = "행사 분류 생성",
            description =
                    "새 행사 분류를 만든다. eventClsfCd는 요청이 정하며 ^[A-Z][A-Z0-9_]{1,19}$ 형식이어야"
                            + " 한다(어기면 400 VALIDATION_FAILED). 같은 코드가 이미 있으면 409"
                            + " EVENT_CLASSIFICATION_CODE_DUPLICATED다. 새 분류는 데이터사전의 표준코드"
                            + " 시트에도 등재해야 한다.")
    @PostMapping
    public ResponseEntity<ApiResponse<EventCategoryResponse>> createCategory(
            @Valid @RequestBody EventCategoryCreateRequest request) {
        EventCategoryResponse response = eventCategoryService.createCategory(request);
        return ResponseEntity.created(URI.create("/v1/event-categories/" + response.eventClsfCd()))
                .body(ApiResponse.created(response));
    }

    @Operation(
            summary = "행사 분류 수정",
            description =
                    "이름(eventClsfNm)과 표시 순번(indctSeqno)을 바꾼다. indctSeqno를 생략하면 현재 값을"
                            + " 유지한다. eventClsfCd는 PK이자 event가 FK로 가리키는 값이라 본문에 없으며"
                            + " 바꿀 수 없다 — 새로 만들고 행사를 옮긴 뒤 기존 것을 지우는 것이 경로다.")
    @PatchMapping("/{eventClsfCd}")
    public ApiResponse<EventCategoryResponse> updateCategory(
            @PathVariable String eventClsfCd,
            @Valid @RequestBody EventCategoryUpdateRequest request) {
        return ApiResponse.success(eventCategoryService.updateCategory(eventClsfCd, request));
    }

    /*
     * 삭제. 204가 아니라 data가 null인 200인 것은 모든 응답이 ApiResponse 봉투를 쓰기 때문이다
     * (#36·#65·#80과 같은 판단).
     */
    @Operation(
            summary = "행사 분류 삭제",
            description =
                    "그 분류를 쓰는 행사가 하나도 없을 때만 지워진다. 사용 중이면 409"
                            + " EVENT_CLASSIFICATION_IN_USE이며(행사를 다른 분류로 먼저 옮겨야 한다),"
                            + " 없는 분류는 404 EVENT_CLASSIFICATION_NOT_FOUND다.")
    @DeleteMapping("/{eventClsfCd}")
    public ApiResponse<Void> deleteCategory(@PathVariable String eventClsfCd) {
        eventCategoryService.deleteCategory(eventClsfCd);
        return ApiResponse.successWithNoData();
    }
}
