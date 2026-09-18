package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.DashboardResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaItemRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeResponse;
import org.sscc.ssccopsserver.global.mcp.client.McpListResult;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 회의 쓰기 도구와 운영 읽기 보강 (ssccops#365 W1 · ADR-0027).
 *
 * 규약은 `OperationTools`와 같다 — REST만 부르고, 타입은 컨트롤러 record 그대로, 로그는 이름과 id만.
 *
 * **회의 수정 도구는 없다.** 회의는 PATCH 자체가 없고(전이와 안건으로 움직인다) 제목·일시를 고치는
 * 경로가 REST에 열려 있지 않다 — 도구가 화면보다 많은 것을 할 수는 없다.
 *
 * 승인함·대시보드·하위 업무 유형 읽기는 쓰기 도구가 쓰는 재료라 같은 파도에 넣는다 — 유형 id를
 * 모르면 `create_sub_work`를 부를 수 없고, 정족수 상태를 모르면 투표할 자리를 찾을 수 없다.
 */
@Component
@RequiredArgsConstructor
public class MeetingTools {

    private static final Logger log = LoggerFactory.getLogger(MeetingTools.class);

    private final McpRestClient client;

    @McpTool(
            name = "create_meeting",
            description =
                    "회의를 등록한다. title·meetingCategory·personInChargeId·startAt이 필수다."
                            + " agendas에 안건을 함께 넣을 수 있고 비워도 된다 — 나중에"
                            + " add_meeting_agenda로 더한다. **회의 책임자는 담당자와 같은 회원이며"
                            + " 따로 받지 않는다.** 회의 관리(MEETING_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MeetingDetailResponse createMeeting(
            @McpToolParam(description = "회의 등록 요청") MeetingCreateRequest request,
            McpTransportContext context) {
        log.info("mcp tool create_meeting");
        return client.post(context, "/v1/meetings", request, MeetingDetailResponse.class);
    }

    @McpTool(
            name = "transition_meeting",
            description =
                    "회의 상태를 넘긴다 — transition에 OPEN(개회)·WRITE_MINUTES(회의록 작성)·"
                            + "CLOSE(종료)·CANCEL(취소) 중 하나. CANCEL은 reason이 필수다."
                            + " **처리하지 않은 안건이 남아 있으면 종료가 거절된다**(409) — 그때는"
                            + " 안건을 보류로 표시하거나 처리한 뒤 다시 부른다. 회의 책임자만 할 수 있다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MeetingTransitionResponse transitionMeeting(
            @McpToolParam(description = "회의 id") Long meetingId,
            @McpToolParam(description = "전이 요청 — transition(필수) · reason(CANCEL이면 필수)")
                    MeetingTransitionRequest request,
            McpTransportContext context) {
        log.info("mcp tool transition_meeting meetingId={}", meetingId);
        return client.post(
                context,
                "/v1/meetings/" + meetingId + "/transitions",
                request,
                MeetingTransitionResponse.class);
    }

    @McpTool(
            name = "list_meeting_agendas",
            description = "회의의 안건 목록. 회의 조회(MEETING_READ) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<MeetingAgendaResponse> listMeetingAgendas(
            @McpToolParam(description = "회의 id") Long meetingId, McpTransportContext context) {
        log.info("mcp tool list_meeting_agendas meetingId={}", meetingId);
        return client.getList(
                        context,
                        "/v1/meetings/" + meetingId + "/agendas",
                        null,
                        MeetingAgendaResponse.class)
                .items();
    }

    @McpTool(
            name = "add_meeting_agenda",
            description =
                    "회의에 안건을 올린다. **연결할 운영 건(targetOperationId)과 안건명(agendaName)"
                            + " 중 하나만** 준다 — 둘을 함께 주면 서버가 거절한다. 안건 추가는"
                            + " 회의 안건 작성(MEETING_AGENDA_WRITE) 권한이며 회의 관리와 다르다"
                            + "(국원도 가진다).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MeetingAgendaResponse addMeetingAgenda(
            @McpToolParam(description = "회의 id") Long meetingId,
            @McpToolParam(description = "안건 — targetOperationId 또는 agendaName 하나")
                    MeetingAgendaItemRequest request,
            McpTransportContext context) {
        log.info("mcp tool add_meeting_agenda meetingId={}", meetingId);
        return client.post(
                context,
                "/v1/meetings/" + meetingId + "/agendas",
                request,
                MeetingAgendaResponse.class);
    }

    @McpTool(
            name = "list_approvals",
            description =
                    "승인함 — 내가 처리할 수 있는 하위 업무 승인 건. status로 대기·정족수·반려를"
                            + " 거른다(비우면 전체). **정족수가 모여도 완료는 승인자가 누른다** —"
                            + " 투표는 vote_sub_work_approval, 승인·반려는 transition_sub_work다."
                            + " 업무 관리(WORK_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public McpListResult<ApprovalInboxItemResponse> listApprovals(
            @McpToolParam(description = "검색 조건 — status·size·cursor. 전부 선택", required = false)
                    ApprovalInboxSearchCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_approvals");
        return client.getList(context, "/v1/approvals", condition, ApprovalInboxItemResponse.class);
    }

    @McpTool(
            name = "get_dashboard",
            description =
                    "운영 대시보드 — 내 승인 대기·다가오는 마감·내 업무를 한 번에. «지금 뭘 해야"
                            + " 하나»에 답하는 자리라 목록을 따로 부르기 전에 이것을 먼저 본다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public DashboardResponse getDashboard(McpTransportContext context) {
        log.info("mcp tool get_dashboard");
        return client.get(context, "/v1/dashboard", DashboardResponse.class);
    }

    @McpTool(
            name = "list_sub_work_types",
            description =
                    "하위 업무 유형 목록 — create_sub_work에 넣을 subWorkTypeId를 여기서 얻는다."
                            + " 유형이 승인 필요 여부·승인자 권한·정족수·완료 점검 항목을 정하며"
                            + " **꺼진 유형(useYn=false)은 새 하위 업무에 쓸 수 없다**."
                            + " 하위 업무 유형 조회(SUB_WORK_TYPE_READ) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<SubWorkTypeResponse> listSubWorkTypes(McpTransportContext context) {
        log.info("mcp tool list_sub_work_types");
        return client.getList(context, "/v1/sub-work-types", null, SubWorkTypeResponse.class)
                .items();
    }
}
