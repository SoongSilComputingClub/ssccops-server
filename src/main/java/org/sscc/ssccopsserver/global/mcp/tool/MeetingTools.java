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
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaPromoteResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.global.mcp.client.McpListResult;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 회의 쓰기 도구와 운영 읽기 보강 (ssccops#365 W1 · ADR-0027). 드래프트 안건 승격
 * (promote_meeting_agenda · #625 · ADR-0059)도 여기 둔다 — 업무를 만들지만 출발점이 안건이다.
 *
 * 규약은 `OperationTools`와 같다 — REST만 부르고, 타입은 컨트롤러 record 그대로, 로그는 이름과 id만.
 *
 * **회의 수정 도구는 없다.** 회의는 PATCH 자체가 없고(전이와 안건으로 움직인다) 제목·일시를 고치는
 * 경로가 REST에 열려 있지 않다 — 도구가 화면보다 많은 것을 할 수는 없다. 회의의 **내용**은 안건이
 * 들고(V25가 회의 단위 본문을 걷었다) 그 자리를 `update_meeting_agenda`가 연다(#599).
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
            description =
                    "회의의 안건 목록. 연결 안건의 targetOperation.targetId 는 운영 유형(operationType)의"
                            + " 상세 id 다 — WORK 면 get_work, SUB_WORK 면 get_sub_work, MEETING 이면"
                            + " get_meeting 에 넘긴다(operationId 는 운영 id 라 그 자리에 쓰면 다른 건이"
                            + " 열린다). 회의 조회(MEETING_READ) 권한이 필요하다.",
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
                    "회의에 안건을 올린다. **targetOperationId(연결할 운영 건 — 업무·하위 업무·회의)와"
                            + " agendaName(제목) 중 정확히 하나**를 준다 — 둘 다 주거나 둘 다 없으면 400."
                            + " 운영 건을 주면 안건의 제목은 그 운영 건의 제목이다. 아직 업무가 아닌"
                            + " 논의는 agendaName 만으로 **드래프트 안건**으로 올리고, 업무가 될 만하면"
                            + " promote_meeting_agenda 로 업무를 만들어 잇는다(ADR-0059) — 업무를"
                            + " 먼저 만들 필요가 없다."
                            + " 없는 운영 건은 404. 종료·취소된 회의에는 올릴 수 없다."
                            + " 상정 시점에는 결과가 없다 — 논의 결과와 처리 구분은 그 뒤"
                            + " update_meeting_agenda 로 적는다."
                            + " 안건 추가는 회의 안건 작성(MEETING_AGENDA_WRITE) 권한이며 회의 관리와"
                            + " 다르다(국원도 가진다).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MeetingAgendaResponse addMeetingAgenda(
            @McpToolParam(description = "회의 id") Long meetingId,
            @McpToolParam(
                            description =
                                    "안건 — targetOperationId 또는 agendaName 중 하나(필수) ·"
                                            + " processStatus · content")
                    MeetingAgendaItemRequest request,
            McpTransportContext context) {
        log.info("mcp tool add_meeting_agenda meetingId={}", meetingId);
        return client.post(
                context,
                "/v1/meetings/" + meetingId + "/agendas",
                request,
                MeetingAgendaResponse.class);
    }

    /*
     * 안건 수정 (#599 · ssccops#365). **회의의 내용을 적을 수 있는 유일한 도구다.**
     *
     * 회의 단위 회의록 본문(insd_mtg_dtl_cn·otsd_mtg_dtl_cn)은 쓰는 경로가 끝내 생기지 않아
     * V25 가 걷었고, 그래서 논의·결과는 안건(mtg_dtl)이 든다. 그전에는 도구가 안건을 올릴 수만
     * 있어서, transition_meeting 이 WRITE_MINUTES(회의록 작성)로 보내 놓고 정작 그 상태에서
     * 모델이 쓸 것이 없었다.
     *
     * **철회(DELETE)는 열지 않는다** — 되살리는 API 가 없다(ADR-0053 의 판정 기준 그대로).
     */
    @McpTool(
            name = "update_meeting_agenda",
            description =
                    "올라온 안건의 논의 내용·결과 내용·처리 구분을 고친다. 드래프트 안건(draft=true)이면"
                            + " agendaName 으로 제목도 고칠 수 있다 — 생략하면 제목은 그대로다."
                            + " **전체 교체다** — content·resultContent 를 생략하면 지운 것으로 본다."
                            + " 한 칸만 바꾸려면 list_meeting_agendas 로 지금 값을 읽어 함께 보낸다"
                            + "(update_work 같은 읽고-합치기 도구와 다르다)."
                            + " processStatus 는 필수다."
                            + " 바꿀 수 없는 것: 연결 운영 건·제출자 — 다시 상정하는 것과 같아"
                            + " 이 API 의 범위 밖이다. 운영 건을 가리키는 안건에 agendaName 을 주면"
                            + " 400 이다(제목은 그 운영 건의 제목이다). 드래프트를 업무에 잇는 길은"
                            + " promote_meeting_agenda 하나다."
                            + " 종료·취소된 회의의 안건은 409 다. 없는 안건은 404."
                            + " 회의 안건 작성(MEETING_AGENDA_WRITE) 권한이며 회의 관리와 다르다"
                            + "(국원도 가진다).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MeetingAgendaResponse updateMeetingAgenda(
            @McpToolParam(description = "회의 id") Long meetingId,
            @McpToolParam(description = "안건 id") Long agendaId,
            @McpToolParam(
                            description =
                                    "바꿀 값 전부 — processStatus(필수) · content · resultContent ·"
                                            + " agendaName(드래프트만 · 생략하면 그대로)."
                                            + " 생략한 내용 칸은 비워진다")
                    MeetingAgendaUpdateRequest request,
            McpTransportContext context) {
        log.info("mcp tool update_meeting_agenda meetingId={} agendaId={}", meetingId, agendaId);
        return client.patch(
                context,
                "/v1/meetings/" + meetingId + "/agendas/" + agendaId,
                request,
                MeetingAgendaResponse.class);
    }

    /*
     * 드래프트 안건 승격 — «업무로 만들기» (#625 · ADR-0059).
     *
     * 입력은 create_work 와 같은 WorkCreateRequest 다. **도구가 안건 제목으로 업무 제목을 채우지
     * 않는다** — 서버가 필수 값을 지어내지 않는다는 결정(ADR-0059)을 도구도 따른다. 모델이 안건
     * 제목을 쓰고 싶으면 list_meeting_agendas 로 읽어 title 에 넣는다(설명에 적었다).
     */
    @McpTool(
            name = "promote_meeting_agenda",
            description =
                    "드래프트 안건(draft=true · 제목만 있는 안건)을 업무로 만든다 — 업무를 등록하고"
                            + " 안건이 그 업무를 가리키게 한다(한 트랜잭션 · ADR-0059). 입력은"
                            + " create_work 와 같다: title·itemType·ownerId 가 필수다. **제목을 서버가"
                            + " 채우지 않는다** — 안건 제목을 그대로 쓰려면 list_meeting_agendas 로"
                            + " agendaName 을 읽어 title 에 넣는다. 결과는 바뀐 안건(agenda)과 새"
                            + " 업무(work). 이미 운영 건을 가리키는 안건은 409(되돌아가지 않는다)."
                            + " 종료·취소된 회의의 드래프트 안건도 업무로 만들 수 있다(안건 추가·수정은"
                            + " 409). 업무 등록과 같은 업무 관리(WORK_MANAGE)"
                            + " 권한이 필요하다(안건 작성 권한만으로는 안 된다).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MeetingAgendaPromoteResponse promoteMeetingAgenda(
            @McpToolParam(description = "회의 id") Long meetingId,
            @McpToolParam(description = "드래프트 안건 id") Long agendaId,
            @McpToolParam(description = "만들 업무 — create_work 와 같은 요청") WorkCreateRequest request,
            McpTransportContext context) {
        log.info("mcp tool promote_meeting_agenda meetingId={} agendaId={}", meetingId, agendaId);
        return client.post(
                context,
                "/v1/meetings/" + meetingId + "/agendas/" + agendaId + "/promote",
                request,
                MeetingAgendaPromoteResponse.class);
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
