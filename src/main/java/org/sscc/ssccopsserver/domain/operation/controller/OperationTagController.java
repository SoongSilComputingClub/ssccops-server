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
import org.sscc.ssccopsserver.domain.operation.dto.OperationTagAssignRequest;
import org.sscc.ssccopsserver.domain.operation.dto.OperationTagAssignmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationTagResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationTagSaveRequest;
import org.sscc.ssccopsserver.domain.operation.service.OperationTagService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 운영 태그 API (#637 · ssccops#576 · 처음 #624). 모양은 폼 라벨(FormLabelController)을 본뜬다.
 *
 * 태그는 운영 건(oper)에 달린다 — 업무·하위 업무·회의가 같은 태그 목록을 쓰고, 지정도 각자의 상세
 * 응답에 든 operationId로 같은 경로를 부른다. 처음(#624)에는 /v1/work-tags · /v1/works/{workId}/tags였고
 * 릴리스 전에 바꿔 별칭을 남기지 않았다.
 *
 * 클래스 레벨 @RequestMapping이 없는 것도 폼 라벨과 같은 이유다 — /v1/operation-tags(태그 자원)와
 * /v1/operations/{operationId}/tags(운영 건의 하위 자원)를 함께 맡는다. 지정 교체는 경로상 운영 건
 * 아래지만 규칙은 전부 태그 쪽에 있어서, OperationController에 두면 같은 규칙이 두 컨트롤러로 갈라진다.
 *
 * 인가 (#9): 목록은 WORK_READ — 국원도 목록의 태그 칩으로 거른다. 태그 생성·이름 변경·삭제와 운영
 * 건의 태그 지정은 WORK_MANAGE다. 폼 라벨은 관리 권한(FORM_LABEL_MANAGE)과 지정 권한(FORM_WRITE)이
 * 갈리지만, 업무는 등록·수정이 이미 WORK_MANAGE라 지정과 관리를 가를 사람이 없다 — 업무를 고칠 수
 * 있는 국장이 «학술국» 태그를 만들 수 없으면 업무명 앞에 다시 적게 된다. 회의도 지정은 WORK_MANAGE다
 * — 시드에서 MEETING_MANAGE는 늘 WORK_MANAGE와 같은 묶음(OPERATOR)으로 부여되고(OperationController
 * 주석과 같은 판단), 유형마다 권한을 가르면 같은 칩 편집기가 화면마다 다르게 잠긴다.
 */
@RestController
@RequiredArgsConstructor
public class OperationTagController {

    private final OperationTagService operationTagService;

    /*
     * 태그 목록. page 봉투를 싣지 않는다 (AP-11) — 운영진이 손으로 만드는 수십 건이고 화면이 전체를
     * 한 번에 그린다.
     */
    @Operation(
            summary = "운영 태그 목록 조회",
            description =
                    "태그 전체를 이름 오름차순으로 조회한다. usageCount는 그 태그가 달린 살아 있는 운영 건(업무·하위"
                            + " 업무·회의를 합친) 수다.")
    @RequireAuthority(AuthorityCode.WORK_READ)
    @GetMapping("/v1/operation-tags")
    public ApiResponse<List<OperationTagResponse>> getTags() {
        return ApiResponse.success(operationTagService.getTags());
    }

    @Operation(
            summary = "운영 태그 생성",
            description = "새 태그를 만든다. 같은 이름이 이미 있으면 409 OPERATION_TAG_NAME_DUPLICATED로 응답한다.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @PostMapping("/v1/operation-tags")
    public ResponseEntity<ApiResponse<OperationTagResponse>> createTag(
            @Valid @RequestBody OperationTagSaveRequest request) {
        OperationTagResponse response = operationTagService.createTag(request);
        URI location = URI.create("/v1/operation-tags/" + response.operationTagId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    @Operation(
            summary = "운영 태그 이름 변경",
            description =
                    "태그 이름을 바꾼다. 이미 달린 운영 건의 태그도 새 이름으로 보인다. 다른 태그와 이름이 겹치면 409"
                            + " OPERATION_TAG_NAME_DUPLICATED, 없는 태그면 404 OPERATION_TAG_NOT_FOUND.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @PatchMapping("/v1/operation-tags/{operationTagId}")
    public ApiResponse<OperationTagResponse> renameTag(
            @PathVariable Long operationTagId,
            @Valid @RequestBody OperationTagSaveRequest request) {
        return ApiResponse.success(operationTagService.renameTag(operationTagId, request));
    }

    /*
     * 태그 삭제. 지정도 함께 지워지고 운영 건은 그대로다(ssccops#565). 204가 아니라 data가 null인
     * 200인 것은 다른 삭제 엔드포인트와 같은 ApiResponse 컨벤션이다.
     */
    @Operation(
            summary = "운영 태그 삭제",
            description = "태그를 지운다. 그 태그가 달린 운영 건에서는 태그만 떨어지고 운영 건은 그대로 남는다.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @DeleteMapping("/v1/operation-tags/{operationTagId}")
    public ApiResponse<Void> deleteTag(@PathVariable Long operationTagId) {
        operationTagService.deleteTag(operationTagId);
        return ApiResponse.successWithNoData();
    }

    /*
     * 운영 건의 태그 지정 전체 교체. 생성이 아니라 교체이므로 200이다 (LY-06).
     */
    @Operation(
            summary = "운영 건의 태그 지정 교체",
            description =
                    "운영 건(업무·하위 업무·회의의 operationId)에 달린 태그를 요청받은 목록으로 통째로 교체한다."
                            + " 요청에 없는 태그는 떨어지고 빈 배열이면 전부 떨어진다. 유지되는 지정은 지정"
                            + " 시각(crtDt)이 보존되며, 같은 요청을 두 번 보내도 결과가 같다. 없는 태그가 섞이면"
                            + " 404 OPERATION_TAG_NOT_FOUND, 없거나 지운 운영 건이면 404 NOT_FOUND.")
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @PutMapping("/v1/operations/{operationId}/tags")
    public ApiResponse<List<OperationTagAssignmentResponse>> replaceOperationTags(
            @PathVariable Long operationId, @Valid @RequestBody OperationTagAssignRequest request) {
        return ApiResponse.success(
                operationTagService.replaceOperationTags(operationId, request.tagIds()));
    }
}
