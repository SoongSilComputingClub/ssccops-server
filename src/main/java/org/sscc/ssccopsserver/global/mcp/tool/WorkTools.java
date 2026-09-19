package org.sscc.ssccopsserver.global.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemSaveRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistMutationResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchCondition;
import org.sscc.ssccopsserver.global.mcp.client.McpListResult;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;
import org.sscc.ssccopsserver.global.mcp.tool.patch.SubWorkPatch;
import org.sscc.ssccopsserver.global.mcp.tool.patch.WorkPatch;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 업무·하위 업무 쓰기 도구 (ssccops#365 W1 · ADR-0027 · ADR-0037).
 *
 * `OperationTools`(읽기 7 + 전이·체크 2)의 옆에 선다 — 그 파일의 규약이 전부 그대로 적용된다:
 * **REST만 부르고**(서비스 주입 금지 — 인가 1층이 빠진다), 입력·출력 타입은 컨트롤러 record
 * 그대로, 로그는 도구 이름과 대상 id만.
 *
 * **수정은 읽고-합치기다.** 서버 PATCH가 전체 교체라 부분 호출이 다른 필드를 지운다(F2) —
 * `WorkPatch`·`SubWorkPatch`가 상세를 읽어 비어 있는 필드를 채운다. 그래서 수정 도구는 GET 한
 * 홉이 더 있고, 그 사이 남이 고친 값을 되돌릴 수 있다(도구 설명에 밝힌다).
 *
 * **삭제 도구는 여기 없다** — 소프트 삭제만 열기로 했고(ADR-0037) 그것은 W5에서 한 번에 낸다.
 */
@Component
@RequiredArgsConstructor
public class WorkTools {

    private static final Logger log = LoggerFactory.getLogger(WorkTools.class);

    private final McpRestClient client;

    @McpTool(
            name = "list_works",
            description =
                    "업무(행사·상시·정례 운영 단위) 목록을 조건으로 찾는다. 조건은 전부 선택이며"
                            + " 비우면 전체다 — workStatus(PLANNING·IN_PROGRESS·REVIEW·DONE),"
                            + " workType, keyword(제목), mine(true면 내가 담당), size(기본 20·최대 100),"
                            + " sort. 하위 업무는 list_sub_works가 따로 답한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public McpListResult<WorkListItemResponse> listWorks(
            @McpToolParam(description = "검색 조건. 전부 선택이며 비우면 전체", required = false)
                    WorkSearchCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_works");
        return client.getList(context, "/v1/works", condition, WorkListItemResponse.class);
    }

    @McpTool(
            name = "create_work",
            description =
                    "업무를 등록한다. title·itemType·ownerId가 필수이며 상태는 항상 기획(PLANNING)으로"
                            + " 시작한다 — 등록과 동시에 진행으로 놓는 길은 없다. 담당자(ownerId)는 활동"
                            + " 회원이어야 하고 아니면 서버가 거절한다. 업무 관리(WORK_MANAGE) 권한이"
                            + " 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public WorkCreateResponse createWork(
            @McpToolParam(description = "업무 등록 요청") WorkCreateRequest request,
            McpTransportContext context) {
        log.info("mcp tool create_work");
        return client.post(context, "/v1/works", request, WorkCreateResponse.class);
    }

    @McpTool(
            name = "update_work",
            description =
                    "업무의 값을 바꾼다. **바꿀 필드만 준다** — 나머지는 현재 값이 그대로 유지된다"
                            + "(도구가 상세를 먼저 읽어 채운다). 상태는 이 도구로 바꾸지 않는다."
                            + " 읽은 뒤 저장하기까지 다른 사람이 같은 업무를 고치면 그 변경이 되돌려질"
                            + " 수 있으니, 방금 화면에서 누가 고친 업무라면 get_work로 먼저 확인한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public WorkDetailResponse updateWork(
            @McpToolParam(description = "업무 id") Long workId,
            @McpToolParam(description = "바꿀 필드만. 비운 필드는 현재 값을 유지한다") WorkPatch patch,
            McpTransportContext context) {
        log.info("mcp tool update_work workId={}", workId);
        WorkDetailResponse current =
                client.get(context, "/v1/works/" + workId, WorkDetailResponse.class);
        return client.patch(
                context, "/v1/works/" + workId, patch.merge(current), WorkDetailResponse.class);
    }

    @McpTool(
            name = "create_sub_work",
            description =
                    "하위 업무를 등록한다. workId(상위 업무)·title·subWorkTypeId·ownerId가 필수다."
                            + " 유형이 승인 규칙·완료 점검 항목을 정하며 **등록 시점의 규칙이 복사되어"
                            + " 나중에 유형을 바꿀 수 없다** — 유형은 list_sub_work_types로 먼저 확인한다."
                            + " 꺼진 유형을 고르면 서버가 거절한다. 업무 관리(WORK_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SubWorkCreateResponse createSubWork(
            @McpToolParam(description = "하위 업무 등록 요청") SubWorkCreateRequest request,
            McpTransportContext context) {
        log.info("mcp tool create_sub_work");
        return client.post(context, "/v1/sub-works", request, SubWorkCreateResponse.class);
    }

    @McpTool(
            name = "update_sub_work",
            description =
                    "하위 업무의 값을 바꾼다. **바꿀 필드만 준다** — 나머지는 현재 값이 유지된다."
                            + " 유형·상태·승인은 이 도구로 바꾸지 않는다(유형은 소급이 불가능하고, 상태는"
                            + " transition_sub_work가 답한다). 읽은 뒤 저장까지의 남의 변경을 되돌릴 수"
                            + " 있다는 점은 update_work와 같다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SubWorkDetailResponse updateSubWork(
            @McpToolParam(description = "하위 업무 id") Long subWorkId,
            @McpToolParam(description = "바꿀 필드만. 비운 필드는 현재 값을 유지한다") SubWorkPatch patch,
            McpTransportContext context) {
        log.info("mcp tool update_sub_work subWorkId={}", subWorkId);
        SubWorkDetailResponse current =
                client.get(context, "/v1/sub-works/" + subWorkId, SubWorkDetailResponse.class);
        return client.patch(
                context,
                "/v1/sub-works/" + subWorkId,
                patch.merge(current),
                SubWorkDetailResponse.class);
    }

    @McpTool(
            name = "vote_sub_work_approval",
            description =
                    "정족수 투표에 찬성(AGREE)·반대(DISAGREE)를 던진다. 찬반 투표(APPROVAL_VOTE) 권한이"
                            + " 필요하고 한 사람이 한 번만 던질 수 있다. **정족수가 채워져도 완료는"
                            + " 승인자가 누른다** — 투표는 승인을 대체하지 않는다. 승인 대기 목록은"
                            + " list_approvals가 답한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SubWorkVoteResponse voteSubWorkApproval(
            @McpToolParam(description = "하위 업무 id") Long subWorkId,
            @McpToolParam(description = "vote — AGREE 또는 DISAGREE") SubWorkVoteRequest request,
            McpTransportContext context) {
        log.info("mcp tool vote_sub_work_approval subWorkId={}", subWorkId);
        return client.post(
                context,
                "/v1/sub-works/" + subWorkId + "/approvals/votes",
                request,
                SubWorkVoteResponse.class);
    }

    @McpTool(
            name = "add_sub_work_checklist_item",
            description =
                    "하위 업무의 완료 점검 항목을 하나 더한다. 담당자이거나 업무 관리(WORK_MANAGE)"
                            + " 권한이 필요하고, 검토 이후 단계에서는 서버가 거절한다(그 단계에서는"
                            + " 체크·해제만 된다). 체크 표시는 check_sub_work_item이 답한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SubWorkChecklistMutationResponse addSubWorkChecklistItem(
            @McpToolParam(description = "하위 업무 id") Long subWorkId,
            @McpToolParam(description = "article — 점검 항목 내용")
                    SubWorkChecklistItemSaveRequest request,
            McpTransportContext context) {
        log.info("mcp tool add_sub_work_checklist_item subWorkId={}", subWorkId);
        return client.post(
                context,
                "/v1/sub-works/" + subWorkId + "/checklist",
                request,
                SubWorkChecklistMutationResponse.class);
    }

    @McpTool(
            name = "update_sub_work_checklist_item_article",
            description =
                    "완료 점검 항목의 문구를 고친다. 체크 여부는 바뀌지 않는다(그것은"
                            + " check_sub_work_item이다). 항목을 지우는 도구는 없다 — 화면에서 한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SubWorkChecklistMutationResponse updateSubWorkChecklistItemArticle(
            @McpToolParam(description = "하위 업무 id") Long subWorkId,
            @McpToolParam(description = "체크리스트 항목 id") Long checklistItemId,
            @McpToolParam(description = "article — 새 점검 항목 내용")
                    SubWorkChecklistItemSaveRequest request,
            McpTransportContext context) {
        log.info(
                "mcp tool update_sub_work_checklist_item_article subWorkId={} itemId={}",
                subWorkId,
                checklistItemId);
        return client.patch(
                context,
                "/v1/sub-works/" + subWorkId + "/checklist/" + checklistItemId + "/article",
                request,
                SubWorkChecklistMutationResponse.class);
    }
}
