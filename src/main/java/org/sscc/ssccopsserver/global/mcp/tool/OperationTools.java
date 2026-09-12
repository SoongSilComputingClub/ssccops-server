package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.auth.dto.AuthSessionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationHubResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.global.mcp.client.McpListResult;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * MCP 1차 도구 — 읽기 7종 + 하위 업무 전이·체크리스트 (#385 · ADR-0027 · 분석 문서 4.4).
 *
 * **전부 `McpRestClient`로 자기 REST를 부른다.** 서비스를 주입받는 도구를 여기에 더하지 말 것 —
 * 그 순간 `@RequireAuthority`·`@Valid`·`@CurrentMember`·감사가 그 도구에서만 빠진다(ADR-0027 C안을
 * 기각한 이유). 입력·출력 타입은 컨트롤러의 Request/Response record **그대로**다 — 도구 스키마를
 * 따로 쓰면 서버·웹 계약이 갈렸던 ssccops#305가 도구에서 재현된다. 목록 조건도 컨트롤러의
 * `@ModelAttribute` record를 그대로 받는다(필드 이름 = 쿼리 파라미터 이름).
 *
 * **update 도구는 없다.** 운영 도메인 PATCH가 전체 교체라(F2) 모델의 부분 호출이 본문·완료 기준·
 * 외부 링크를 지운다. 생성·삭제·회원·폼·역할도 1차 범위 밖이다.
 *
 * `McpTransportContext` 인자는 입력 스키마에 나타나지 않는다(mcp-annotations가 특수 인자로 뺀다) —
 * SecurityContext가 비었을 때의 예비 경로로 Bearer를 꺼내는 데 쓴다(`BearerTokenSource`).
 *
 * 로그는 **도구 이름과 대상 id만** INFO로 남긴다. 인자 본문(사유 문장 등)은 싣지 않는다 —
 * ADR-0024의 값 미탑재 원칙이 일반 로그에도 적용된다. 감사 로그 자체는 REST를 지나며 기존 지점이
 * 그대로 찍힌다(채널 `client.id`로 웹과 갈린다, #384).
 */
@Component
@RequiredArgsConstructor
public class OperationTools {

    private static final Logger log = LoggerFactory.getLogger(OperationTools.class);

    private final McpRestClient client;

    @McpTool(
            name = "list_operations",
            description =
                    "운영 통합 목록 — 업무·하위 업무·회의를 한 번에 돌려준다(각 목록의 첫 페이지 요약)."
                            + " 전체 현황을 훑을 때 쓴다. 권한 WORK_MANAGE 필요 — 담당자 권한(WORK_READ)만"
                            + " 있으면 403이므로 그때는 list_sub_works를 쓴다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public OperationHubResponse listOperations(McpTransportContext context) {
        log.info("mcp tool list_operations");
        return client.get(context, "/v1/operations", OperationHubResponse.class);
    }

    @McpTool(
            name = "get_work",
            description =
                    "업무(work) 상세 — 제목·상태·담당자·기간·진행률과 딸린 하위 업무 요약을 돌려준다."
                            + " 하위 업무 목록에서 얻은 workId로 부른다. 권한 WORK_READ.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public WorkDetailResponse getWork(
            @McpToolParam(description = "업무 id") Long workId, McpTransportContext context) {
        log.info("mcp tool get_work target={}", workId);
        return client.get(context, "/v1/works/" + workId, WorkDetailResponse.class);
    }

    @McpTool(
            name = "list_sub_works",
            description =
                    "하위 업무 목록 — 조건(workStatus: PLANNING·IN_PROGRESS·REVIEW·DONE / approvalStatus:"
                        + " NOT_REQUIRED·PENDING·APPROVED·REJECTED·REAPPROVAL_REQUIRED / isOverdue"
                        + " / dueBefore(ISO 일시) / isReadyForReview / isReviewStale / keyword /"
                        + " mine=true는 내 담당만 / sort: dueAt·-dueAt·createdAt·-createdAt /"
                        + " size≤100)으로 거른다. 커서 페이징을 최대 3페이지까지만 따라가며 hasMore면 nextCursor를 조건의"
                        + " cursor에 넣어 이어 부른다. 권한 WORK_READ.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public McpListResult<SubWorkSummaryResponse> listSubWorks(
            @McpToolParam(description = "검색 조건. 전부 선택이며 비우면 전체", required = false)
                    SubWorkSearchCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_sub_works");
        return client.getList(context, "/v1/sub-works", condition, SubWorkSummaryResponse.class);
    }

    @McpTool(
            name = "get_sub_work",
            description =
                    "하위 업무 상세 — 상태·승인 상태·담당자·마감·본문·완료 기준·체크리스트(항목 id 포함)·"
                            + "결재 정족수·내 투표·최근 반려 사유·내가 승인/반려할 수 있는지를 돌려준다."
                            + " 전이·체크리스트 도구를 부르기 전에 이걸로 현재 상태를 확인한다. 권한 WORK_READ.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public SubWorkDetailResponse getSubWork(
            @McpToolParam(description = "하위 업무 id") Long subWorkId, McpTransportContext context) {
        log.info("mcp tool get_sub_work target={}", subWorkId);
        return client.get(context, "/v1/sub-works/" + subWorkId, SubWorkDetailResponse.class);
    }

    @McpTool(
            name = "list_meetings",
            description =
                    "회의 목록 전체 — 제목·분류·상태(SCHEDULED·IN_PROGRESS·MINUTES·CLOSED·CANCELED)·"
                            + "주재자·장소·안건 수·일시. 페이징 없이 전량이다. 권한 MEETING_READ.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<MeetingListItemResponse> listMeetings(McpTransportContext context) {
        log.info("mcp tool list_meetings");
        return client.getList(context, "/v1/meetings", null, MeetingListItemResponse.class).items();
    }

    @McpTool(
            name = "get_meeting",
            description = "회의 상세 — 목록 항목에 더해 내부 상세·외부 요약·안건 목록(처리 상태·결과 포함)." + " 권한 MEETING_READ.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public MeetingDetailResponse getMeeting(
            @McpToolParam(description = "회의 id") Long meetingId, McpTransportContext context) {
        log.info("mcp tool get_meeting target={}", meetingId);
        return client.get(context, "/v1/meetings/" + meetingId, MeetingDetailResponse.class);
    }

    @McpTool(
            name = "get_me",
            description =
                    "지금 연결된 사용자 — 가입 여부(signedUp)와 회원 정보(이름·등급·상태·역할·권한 목록"
                            + " capabilities). 다른 도구가 403이면 이걸로 어떤 권한이 있는지 본다."
                            + " signedUp=false면 운영 웹에서 가입해야 하며 도구는 전부 «가입 필요»로 끝난다."
                            + " 인증만 필요.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public AuthSessionResponse getMe(McpTransportContext context) {
        log.info("mcp tool get_me");
        return client.get(context, "/v1/auth/session", AuthSessionResponse.class);
    }

    @McpTool(
            name = "transition_sub_work",
            description =
                    "하위 업무 상태 전이. transition은 START(기획→진행) · REQUEST_REVIEW(진행→검토) ·"
                            + " APPROVE_COMPLETE(검토→완료, 승인과 완료가 한 단계) · REJECT(검토→진행, reason"
                            + " 필수) 중 하나. 담당자·결재 권한 판정은 서버가 하며 403이면 재시도하지"
                            + " 말고 사용자에게 알린다. 전이 전에 get_sub_work로 현재 상태를 확인한다."
                            + " 되돌릴 수 없으므로 사용자가 명시적으로 요청한 경우에만 부른다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SubWorkTransitionResponse transitionSubWork(
            @McpToolParam(description = "하위 업무 id") Long subWorkId,
            @McpToolParam(description = "전이 요청 — transition(필수)·reason(REJECT면 필수, 500자)")
                    SubWorkTransitionRequest request,
            McpTransportContext context) {
        // reason은 사람이 쓴 문장이라 로그에 싣지 않는다 — 대상 id와 전이 이름만
        log.info(
                "mcp tool transition_sub_work target={} transition={}",
                subWorkId,
                request == null ? null : request.transition());
        return client.post(
                context,
                "/v1/sub-works/" + subWorkId + "/transitions",
                request,
                SubWorkTransitionResponse.class);
    }

    @McpTool(
            name = "check_sub_work_item",
            description =
                    "하위 업무 체크리스트 항목의 완료 여부를 바꾼다(isCompleted true/false). 항목 id는"
                            + " get_sub_work의 checklist에서 얻는다. 담당자만 가능하며 완료된 하위 업무의"
                            + " 항목은 바꿀 수 없다(409). 사용자가 명시적으로 요청한 경우에만 부른다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SubWorkChecklistItemUpdateResponse checkSubWorkItem(
            @McpToolParam(description = "하위 업무 id") Long subWorkId,
            @McpToolParam(description = "체크리스트 항목 id") Long checklistItemId,
            @McpToolParam(description = "isCompleted — 완료로 표시하면 true")
                    SubWorkChecklistItemUpdateRequest request,
            McpTransportContext context) {
        log.info("mcp tool check_sub_work_item target={} item={}", subWorkId, checklistItemId);
        return client.patch(
                context,
                "/v1/sub-works/" + subWorkId + "/checklist/" + checklistItemId,
                request,
                SubWorkChecklistItemUpdateResponse.class);
    }
}
