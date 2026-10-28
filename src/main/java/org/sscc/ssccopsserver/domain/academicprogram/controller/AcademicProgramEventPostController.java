package org.sscc.ssccopsserver.domain.academicprogram.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramEventPostResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramEventPostService;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventIntroductionUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 스터디장의 행사 게시물(소개 글) 편집 (ssccops#618 · ADR-0069 참고). LMS «소개 글 편집»이 부른다.
 *
 * **@RequireAuthority가 없다.** 자격이 권한이 아니라 «이 프로그램의 리더인가»(leadr_mbr_id)이기 때문이다 —
 * 리더 역할에는 권한을 붙일 수 없고(ADR-0067), 판정은 서비스의 AcademicProgramOwnershipPolicy가 한다.
 * 행사 API(/v1/events · EVENT_MANAGE)는 손대지 않았다 — 학술국장 · 국원은 그쪽으로 고친다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/event-post")
public class AcademicProgramEventPostController {

    private final AcademicProgramEventPostService academicProgramEventPostService;

    @Operation(
            summary = "학술 활동 행사 게시물 조회 (스터디장)",
            description =
                    "프로그램 행사의 제목 · 본문 · 대표 이미지 · 장소와 지금 고칠 수 있는지(isEditable · lockReason)를 내린다. **이"
                        + " 프로그램의 리더 본인만** 부를 수 있고 학술국장 · 국원을 포함한 그 밖의 회원은 403 FORBIDDEN이다(관리자 페이지"
                        + " 행사 편집을 쓴다). 잠겨 있어도 200이다. lockReason은 PROGRAM_COMPLETED ·"
                        + " PROGRAM_DISCONTINUED · RECRUITMENT_CLOSED(모집 폼 접수 상태가 EXPIRED · CLOSED)"
                        + " 중 하나이고 열려 있으면 null이다. 없는 활동은 404, 모집 폼이 없는 활동은 409 FORM_NOT_LINKED.")
    @GetMapping
    public ApiResponse<AcademicProgramEventPostResponse> getEventPost(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                academicProgramEventPostService.getEventPost(academicProgramId, requester));
    }

    @Operation(
            summary = "학술 활동 행사 게시물 수정 (스터디장)",
            description =
                    "제목 · 본문 · 대표 이미지 · 장소 **넷만** 바꾼다 — 기간 · 정원 · 분류 · 폼 · 게시 상태는 받지 않고 저장된 값 그대로다."
                            + " 검증은 행사 저장과 같다(제목 필수 · 256자, 본문 필수 · 10만 자 초과 413"
                            + " EVENT_CONTENT_TOO_LARGE, 대표 이미지 200자, 장소 100자). 리더 본인이 아니면 403"
                            + " FORBIDDEN, 수료 · 폐지된 활동은 409 ACADEMIC_PROGRAM_COMPLETED ·"
                            + " ACADEMIC_PROGRAM_DISCONTINUED, 모집 접수가 끝났으면(EXPIRED · CLOSED) 409"
                            + " ACADEMIC_PROGRAM_EVENT_POST_LOCKED. 응답은 조회와 같은 모양이다.")
    @PutMapping
    public ApiResponse<AcademicProgramEventPostResponse> updateEventPost(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody EventIntroductionUpdateRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(
                academicProgramEventPostService.updateEventPost(academicProgramId, request, actor));
    }

    @Operation(
            summary = "학술 활동 행사 게시물 본문 이미지 업로드 URL 발급 (스터디장)",
            description =
                    "POST /v1/events/{eventId}/images와 같은 요청 · 응답(201)이고 같은 키(events/{eventId}/…)와"
                            + " 영구 읽기 주소(imageUrl)를 쓴다. 자격과 잠금은 수정과 같다(403 · 409).")
    @PostMapping("/images")
    public ResponseEntity<ApiResponse<EventImageUploadResponse>> issueImageUploadUrl(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody EventImageUploadRequest request,
            @CurrentMember MemberEntity actor) {
        // 행사 이미지 발급과 같은 201 Created — 화면이 두 경로를 같은 코드로 다룬다
        EventImageUploadResponse response =
                academicProgramEventPostService.issueImageUploadUrl(
                        academicProgramId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }
}
