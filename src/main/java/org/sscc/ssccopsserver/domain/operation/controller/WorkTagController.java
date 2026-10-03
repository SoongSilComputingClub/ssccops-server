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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagAssignRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagAssignmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagSaveRequest;
import org.sscc.ssccopsserver.domain.operation.service.WorkTagService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 업무 태그 API (#624 · ssccops#565). 모양은 폼 라벨(FormLabelController)을 본뜬다.
 *
 * 클래스 레벨 @RequestMapping이 없는 것도 그쪽과 같은 이유다 — /v1/work-tags(태그 자원)와
 * /v1/works/{workId}/tags(업무의 하위 자원)를 함께 맡는다. 지정 교체는 경로상 업무 아래지만 규칙은
 * 전부 태그 쪽에 있어서, WorkController에 두면 같은 규칙이 두 컨트롤러로 갈라진다.
 *
 * 인가 (#9): 목록은 WORK_READ — 국원도 업무 목록의 태그 칩으로 거른다. 태그 생성·이름 변경·삭제와
 * 업무의 태그 지정은 WORK_MANAGE다. 폼 라벨은 관리 권한(FORM_LABEL_MANAGE)과 지정 권한(FORM_WRITE)이
 * 갈리지만, 업무는 등록·수정이 이미 WORK_MANAGE라 지정과 관리를 가를 사람이 없다 — 업무를 고칠 수
 * 있는 국장이 «학술국» 태그를 만들 수 없으면 업무명 앞에 다시 적게 된다.
 */
@RestController
@RequiredArgsConstructor
public class WorkTagController {

    private final WorkTagService workTagService;

    /*
     * 태그 목록. page 봉투를 싣지 않는다 (AP-11) — 운영진이 손으로 만드는 수십 건이고 화면이 전체를
     * 한 번에 그린다.
     */
    @Operation(
            summary = "업무 태그 목록 조회",
            description = "태그 전체를 이름 오름차순으로 조회한다. usageCount는 그 태그가 달린 살아 있는 업무 수다.")
    @RequireAuthority(AuthorityCode.WORK_READ)
    @GetMapping("/v1/work-tags")
    public ApiResponse<List<WorkTagResponse>> getTags() {
        return ApiResponse.success(workTagService.getTags());
    }

    @Operation(
            summary = "업무 태그 생성",
            description = "새 태그를 만든다. 같은 이름이 이미 있으면 409 WORK_TAG_NAME_DUPLICATED로 응답한다.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @PostMapping("/v1/work-tags")
    public ResponseEntity<ApiResponse<WorkTagResponse>> createTag(
            @Valid @RequestBody WorkTagSaveRequest request) {
        WorkTagResponse response = workTagService.createTag(request);
        URI location = URI.create("/v1/work-tags/" + response.workTagId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    @Operation(
            summary = "업무 태그 이름 변경",
            description =
                    "태그 이름을 바꾼다. 이미 달린 업무의 태그도 새 이름으로 보인다. 다른 태그와 이름이 겹치면"
                            + " 409 WORK_TAG_NAME_DUPLICATED, 없는 태그면 404 WORK_TAG_NOT_FOUND.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @PatchMapping("/v1/work-tags/{workTagId}")
    public ApiResponse<WorkTagResponse> renameTag(
            @PathVariable Long workTagId, @Valid @RequestBody WorkTagSaveRequest request) {
        return ApiResponse.success(workTagService.renameTag(workTagId, request));
    }

    /*
     * 태그 삭제. 지정도 함께 지워지고 업무는 그대로다(ssccops#565). 204가 아니라 data가 null인 200인
     * 것은 다른 삭제 엔드포인트와 같은 ApiResponse 컨벤션이다.
     */
    @Operation(summary = "업무 태그 삭제", description = "태그를 지운다. 그 태그가 달린 업무에서는 태그만 떨어지고 업무는 그대로 남는다.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @DeleteMapping("/v1/work-tags/{workTagId}")
    public ApiResponse<Void> deleteTag(@PathVariable Long workTagId) {
        workTagService.deleteTag(workTagId);
        return ApiResponse.successWithNoData();
    }

    /*
     * 업무의 태그 지정 전체 교체. 생성이 아니라 교체이므로 200이다 (LY-06).
     */
    @Operation(
            summary = "업무의 태그 지정 교체",
            description =
                    "업무에 달린 태그를 요청받은 목록으로 통째로 교체한다. 요청에 없는 태그는 떨어지고 빈 배열이면"
                            + " 전부 떨어진다. 유지되는 지정은 지정 시각(crtDt)이 보존되며, 같은 요청을 두 번 보내도"
                            + " 결과가 같다. 없는 태그가 섞이면 404 WORK_TAG_NOT_FOUND.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @PutMapping("/v1/works/{workId}/tags")
    public ApiResponse<List<WorkTagAssignmentResponse>> replaceWorkTags(
            @PathVariable Long workId, @Valid @RequestBody WorkTagAssignRequest request) {
        return ApiResponse.success(workTagService.replaceWorkTags(workId, request.tagIds()));
    }
}
