package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendanceResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentApplicationResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossListResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionReviewCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionResponse;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 학술 도구 (ssccops#365 W3 · #567 · ADR-0027). «검토 대기 중인 회차 보여줘»가 되게.
 *
 * **이 파도를 먼저 한 이유는 승인 대기가 쌓이는 자리이기 때문이다.** 학술국장이 화면을 열지 않고
 * 처리하고 싶어 할 1순위이고, 남은 일이 «읽고 → 승인하거나 수정요청»이라 대화에 잘 맞는다.
 *
 * 규약은 다른 도구와 같다 — `McpRestClient`로 REST만 부르고, 타입은 컨트롤러 record 그대로, 로그는
 * 도구 이름과 대상 id만. 여기에 학술 도메인의 사정 셋이 더 붙는다.
 *
 * ── ① 만들기·고치기 도구가 없다 ────────────────────────────────
 *
 * 활동은 **기획안 승인 이관으로만 생긴다**(`domain/academicprogram/AGENTS.md`) — 「활동을 만들어줘」에
 * 해당하는 엔드포인트가 아예 없고, 회차 작성·재제출은 활동 구성원이 화면에서 하는 일이다. 그래서 이
 * 파도는 **읽기와 검토**로 이루어진다. 도구가 없는 것이 빠뜨린 것이 아니라 그 모양이다.
 *
 * ── ② 전이는 셋이고, 무엇이 가능한지는 서버가 안다 ─────────────
 *
 * 활동 전이는 `START_RECRUITMENT`·`APPROVE_COMPLETION`·`REOPEN` 셋이다 — 승인·반려·수정요청 3종은
 * **없다**(승인은 기획안 이관이 대신하고 반려·수정요청은 폼 응답 상태로 옮겨 갔다, #133).
 * **종료는 그 활동의 쓰기를 전부 멈추고**(409 `ACADEMIC_PROGRAM_COMPLETED`) `REOPEN`이 되돌린다
 * (#597 · ADR-0057) — 회차 검토·선발·출석 정정 도구가 전부 그 409를 받을 수 있다. 회차 전이는
 * `APPROVE`·`REQUEST_REVISION` 둘이고 **`REQUEST_REVISION`은 사유가 필수**이며 둘 다 `SUBMITTED`
 * 에서만 간다. **승인된 회차는 되돌리지 않는다** — 출석부·진행률의 기준선이라서다.
 *
 * 그 표를 도구 설명에 적는 것은 모델이 409를 받고 같은 호출을 되풀이하지 않게 하기 위해서다.
 *
 * ── ③ 모집 지원 목록에는 학번이 실려 있었다 ───────────────────
 *
 * `RecruitmentApplicationResponse`가 무는 `ResponseMemberSummary`에 `stdntNo`가 있고, 도구 출력의
 * 개인정보를 걷는 `ToolOutputRedactor`는 **이름으로 지우는데 그 이름을 몰랐다**(`studentNumber`만
 * 알고 있었다). 이 도구가 그 record를 처음 밖으로 내보내는 자리라 같은 PR에서 걷는 이름을 여섯으로
 * 넓히고 `ToolOutputRedactorCoverageTest`가 전수 대조하게 했다 — ADR-0037이 «계속 지운다»고 적은
 * 세 값이 실제로 지워지는지는 그 테스트가 답한다.
 */
@Component
@RequiredArgsConstructor
public class AcademicTools {

    private static final Logger log = LoggerFactory.getLogger(AcademicTools.class);

    private static final String PROGRAMS = "/v1/academic-programs";
    private static final String SESSIONS = "/sessions/";
    private static final String ATTENDANCES = "/attendances";

    /** 열 곳이 같은 인자를 받는다 — 문장이 갈리면 모델이 같은 값을 다르게 이해한다 */
    private static final String PROGRAM_ID = "활동 식별자";

    private static final String SESSION_ID = "회차 식별자";

    private final McpRestClient client;

    /** 팀원 목록의 조건 — 참가 상태 하나. 비우면 전체 */
    public record MemberListCondition(EventParticipantStatus ptcpSttsCd) {}

    /** 지원 목록의 조건 — 응답 상태 하나. 쿼리 이름이 `statusCode`라 필드 이름도 그것이다 */
    public record ApplicationListCondition(ResponseStatus statusCode) {}

    // ══ 읽기 ═══════════════════════════════════════════════════

    @McpTool(
            name = "list_academic_programs",
            description =
                    "학술 활동(스터디·프로젝트·트랙) 목록. typeCd(유형 코드)·sttsCd(상태)·keyword(제목)로"
                            + " 거르고, mine으로 내 것만 본다 — leader(내가 스터디장/팀장) ·"
                            + " proposer(내가 기획안 제출자) · true(둘 중 하나). 팀원으로 참여한 활동을 고르는"
                            + " 값은 없다. 그 밖의 값은 400이다. 전부 비우면 전체."
                            + " 상태는 APPROVED(승인됨 · 모집 전)·ONGOING(진행 중)·COMPLETED(종료) 셋이다."
                            + " isDelayed는 «진행 중인데 예정된 운영 기간이 끝났고 진행률(승인 회차 ÷ 계획 항목)이"
                            + " 100% 미만»이며, delayed=true면 그것만 본다(sttsCd와 함께 주면 AND)."
                            + " 목록에는 기획 내용(목표·준비·일정)이 없다 — get_academic_program으로."
                            + " 팀원은 list_academic_program_members, 회차는 list_academic_sessions로 본다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<AcademicProgramSummaryResponse> listAcademicPrograms(
            @McpToolParam(
                            description =
                                    "검색 조건 — typeCd·sttsCd·keyword·mine·delayed·size·cursor·sort."
                                            + " 전부 선택",
                            required = false)
                    AcademicProgramCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_academic_programs");
        return client.getList(context, PROGRAMS, condition, AcademicProgramSummaryResponse.class)
                .items();
    }

    @McpTool(
            name = "get_academic_program",
            description = "학술 활동 하나 — 기획 내용·기간·정원·리더·연결된 폼과 진행률. 없거나 볼 권한이 없으면 404·403.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public AcademicProgramDetailResponse getAcademicProgram(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            McpTransportContext context) {
        log.info("mcp tool get_academic_program academicProgramId={}", academicProgramId);
        return client.get(
                context, PROGRAMS + "/" + academicProgramId, AcademicProgramDetailResponse.class);
    }

    @McpTool(
            name = "list_academic_program_members",
            description =
                    "학술 활동의 팀원. ptcpSttsCd로 거른다(CONFIRMED 확정·WAITLISTED 대기·CANCELLED 취소),"
                            + " 비우면 전체. 리더 여부와 합류 시각이 함께 온다."
                            + " 연락처·학번은 도구 출력에서 지워진다(ADR-0037).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<AcademicProgramMemberResponse> listAcademicProgramMembers(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = "참가 상태로 거르기 — 선택", required = false)
                    MemberListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_academic_program_members academicProgramId={}", academicProgramId);
        return client.getList(
                        context,
                        PROGRAMS + "/" + academicProgramId + "/members",
                        condition,
                        AcademicProgramMemberResponse.class)
                .items();
    }

    @McpTool(
            name = "list_academic_sessions",
            description =
                    "한 활동의 회차 목록. sttsCd로 거른다(SUBMITTED 제출됨·APPROVED 승인됨·"
                            + "REVISION_REQUESTED 수정요청됨), 비우면 전체."
                            + " 아직 기록하지 않은 회차(NOT_SUBMITTED)는 이 목록에 행이 없다 —"
                            + " 그 값으로 거르면 언제나 빈 목록이다. 계획된 회차 수는"
                            + " get_academic_program의 curriculumItemCount다."
                            + " 여러 활동에 걸쳐 검토할 것을 찾을 때는 list_academic_sessions_to_review를 쓴다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<SessionSummaryResponse> listAcademicSessions(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = "검색 조건 — sttsCd·size·cursor·sort. 전부 선택", required = false)
                    SessionCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_academic_sessions academicProgramId={}", academicProgramId);
        return client.getList(
                        context,
                        PROGRAMS + "/" + academicProgramId + "/sessions",
                        condition,
                        SessionSummaryResponse.class)
                .items();
    }

    @McpTool(
            name = "get_academic_session",
            description = "회차 하나 — 일시·장소·내용·출석 요약과 승인 이력. 회차 번호가 아니라 회차 식별자(sessionId)다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public SessionDetailResponse getAcademicSession(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = SESSION_ID) Long sessionId,
            McpTransportContext context) {
        log.info("mcp tool get_academic_session sessionId={}", sessionId);
        return client.get(
                context,
                PROGRAMS + "/" + academicProgramId + SESSIONS + sessionId,
                SessionDetailResponse.class);
    }

    @McpTool(
            name = "list_academic_sessions_to_review",
            description =
                    "검토를 기다리는 회차 — 활동에 관계없이 제출된(SUBMITTED) 것만 모은다."
                            + " «승인할 게 뭐 있어»의 답이며, 여기서 고른 뒤 transition_academic_session으로 처리한다."
                            + " 종료(COMPLETED)된 활동의 회차는 빠진다 — 처리할 수 없는 줄이라서다."
                            + " 재시작(REOPEN)하면 다시 나온다."
                            + " 학술 활동 관리(ACADEMIC_PROGRAM_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<SessionCrossListResponse> listAcademicSessionsToReview(
            @McpToolParam(description = "검색 조건 — size·cursor·sort. 전부 선택", required = false)
                    SessionReviewCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_academic_sessions_to_review");
        return client.getList(
                        context,
                        PROGRAMS + "/reviews/sessions",
                        condition,
                        SessionCrossListResponse.class)
                .items();
    }

    // ══ 검토 ═══════════════════════════════════════════════════

    @McpTool(
            name = "transition_academic_program",
            description =
                    "학술 활동 상태를 옮긴다. 할 수 있는 것은 셋이다 —"
                            + " START_RECRUITMENT(APPROVED → ONGOING · 연결된 폼의 접수를 함께 연다,"
                            + " recruitmentStartDt·recruitmentEndDt를 주면 모집 기간이 된다) ·"
                            + " APPROVE_COMPLETION(ONGOING → COMPLETED · 진행률이 모자라도 막지 않는다."
                            + " **종료는 그 활동의 쓰기를 전부 멈춘다** — 회차 제출·검토, 출석 정정, 인증사진,"
                            + " 모집 선발·일정·문항이 409 ACADEMIC_PROGRAM_COMPLETED가 되고,"
                            + " 접수 중인 모집 폼은 함께 마감된다) ·"
                            + " REOPEN(COMPLETED → ONGOING · 종료를 되돌려 쓰기를 다시 받는다."
                            + " 모집 폼은 다시 열지 않는다)."
                            + " 승인·반려·수정요청은 여기 없다 — 활동의 승인은 기획안 폼 응답 검토가 대신한다."
                            + " 지금 상태에서 갈 수 없는 전이는 409이고 재시도해도 같다."
                            + " 학술 활동 관리(ACADEMIC_PROGRAM_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public AcademicProgramTransitionResponse transitionAcademicProgram(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(
                            description =
                                    "transition(필수 · START_RECRUITMENT·APPROVE_COMPLETION·REOPEN)과"
                                            + " 모집 기간(선택 · START_RECRUITMENT일 때만 뜻이 있다)")
                    AcademicProgramTransitionRequest request,
            McpTransportContext context) {
        log.info(
                "mcp tool transition_academic_program academicProgramId={} transition={}",
                academicProgramId,
                request == null ? null : request.transition());
        return client.post(
                context,
                PROGRAMS + "/" + academicProgramId + "/transitions",
                request,
                AcademicProgramTransitionResponse.class);
    }

    @McpTool(
            name = "transition_academic_session",
            description =
                    "회차를 승인하거나 수정요청한다. APPROVE(SUBMITTED → APPROVED) ·"
                            + " REQUEST_REVISION(SUBMITTED → REVISION_REQUESTED · **사유가 필수**)."
                            + " 둘 다 제출된 회차에서만 되고, **승인은 되돌릴 수 없다** —"
                            + " 승인된 회차 기록이 출석부·진행률의 기준선이라 '승인 취소'라는 것이 없다."
                            + " 사유는 통보의 전부이므로 무엇을 고쳐야 하는지 구체적으로 적는다."
                            + " 종료(COMPLETED)된 활동의 회차는 409 ACADEMIC_PROGRAM_COMPLETED다 —"
                            + " 처리하려면 먼저 transition_academic_program REOPEN으로 재시작한다."
                            + " 학술 활동 관리(ACADEMIC_PROGRAM_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SessionTransitionResponse transitionAcademicSession(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = SESSION_ID) Long sessionId,
            @McpToolParam(
                            description =
                                    "transition(필수 · APPROVE·REQUEST_REVISION)과"
                                            + " reason(REQUEST_REVISION이면 필수)")
                    SessionTransitionRequest request,
            McpTransportContext context) {
        log.info(
                "mcp tool transition_academic_session sessionId={} transition={}",
                sessionId,
                request == null ? null : request.transition());
        return client.post(
                context,
                PROGRAMS + "/" + academicProgramId + SESSIONS + sessionId + "/transitions",
                request,
                SessionTransitionResponse.class);
    }

    // ══ 모집 ═══════════════════════════════════════════════════

    @McpTool(
            name = "list_academic_recruitment_applications",
            description =
                    "모집 지원 목록 — 지원서(폼 응답)와 지금 참가 상태가 함께 온다."
                            + " statusCode로 응답 상태를 거른다, 비우면 전체."
                            + " **select_academic_recruitment에 넘길 formRspnsId가 여기서 나온다.**"
                            + " 지원자의 연락처·학번은 도구 출력에서 지워진다(ADR-0037).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<RecruitmentApplicationResponse> listAcademicRecruitmentApplications(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = "응답 상태로 거르기 — 선택", required = false)
                    ApplicationListCondition condition,
            McpTransportContext context) {
        log.info(
                "mcp tool list_academic_recruitment_applications academicProgramId={}",
                academicProgramId);
        return client.getList(
                        context,
                        PROGRAMS + "/" + academicProgramId + "/recruitment/applications",
                        condition,
                        RecruitmentApplicationResponse.class)
                .items();
    }

    @McpTool(
            name = "select_academic_recruitment",
            description =
                    "모집 선발 결과를 저장한다. selections에 {formRspnsId, ptcpSttsCd} 쌍을 담는다 —"
                            + " ptcpSttsCd는 CONFIRMED(확정)·WAITLISTED(대기) 둘이다."
                            + " 다시 저장할 수 있다 — 이미 고른 사람을 확정↔대기로 옮길 수 있고, 같은 값이면 그대로다."
                            + " **정원은 참고치라 넘겨도 막지 않는다** — 확정 인원이 정원을 넘는지는 호출한 쪽이 본다."
                            + " 지원 목록을 먼저 읽어 formRspnsId를 확인할 것."
                            + " 모집 전(APPROVED)이면 409 RECRUITMENT_NOT_STARTED,"
                            + " 종료(COMPLETED)된 활동이면 409 ACADEMIC_PROGRAM_COMPLETED다."
                            + " 학술 활동 관리(ACADEMIC_PROGRAM_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public List<AcademicProgramMemberResponse> selectAcademicRecruitment(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = "selections — {formRspnsId, ptcpSttsCd} 목록. 비울 수 없다")
                    RecruitmentSelectRequest request,
            McpTransportContext context) {
        log.info("mcp tool select_academic_recruitment academicProgramId={}", academicProgramId);
        AcademicProgramMemberResponse[] members =
                client.post(
                        context,
                        PROGRAMS + "/" + academicProgramId + "/recruitment/select",
                        request,
                        AcademicProgramMemberResponse[].class);
        return members == null ? List.of() : Arrays.asList(members);
    }

    // ══ 출석 ═══════════════════════════════════════════════════

    @McpTool(
            name = "list_academic_attendances",
            description =
                    "회차의 출석부. **correct_academic_attendances에 넘길 eventPtcpId가 여기서 나온다** —"
                            + " 회원 식별자가 아니라 그 활동의 참가자 식별자다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<AttendanceResponse> listAcademicAttendances(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = SESSION_ID) Long sessionId,
            McpTransportContext context) {
        log.info("mcp tool list_academic_attendances sessionId={}", sessionId);
        return client.getList(
                        context,
                        PROGRAMS + "/" + academicProgramId + SESSIONS + sessionId + ATTENDANCES,
                        null,
                        AttendanceResponse.class)
                .items();
    }

    @McpTool(
            name = "correct_academic_attendances",
            description =
                    "출석을 정정한다. attendances에 {eventPtcpId, atndYn} 쌍을 담으며"
                            + " **보낸 사람만 바뀐다**(안 보낸 참가자는 그대로다)."
                            + " 출석부를 먼저 읽어 eventPtcpId를 확인할 것 — 회원 식별자와 다르다."
                            + " **그 활동의 스터디장/팀장 본인만** 할 수 있다 — 학술 활동 관리 권한이 있어도"
                            + " 리더가 아니면 403이다."
                            + " 검토 대기(SUBMITTED)·수정요청된 회차의 출석은 고칠 수 있지만"
                            + " **승인된(APPROVED) 회차는 409 SESSION_NOT_EDITABLE**이고,"
                            + " 종료(COMPLETED)된 활동이면 409 ACADEMIC_PROGRAM_COMPLETED다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public AttendancePatchResponse correctAcademicAttendances(
            @McpToolParam(description = PROGRAM_ID) Long academicProgramId,
            @McpToolParam(description = SESSION_ID) Long sessionId,
            @McpToolParam(description = "attendances — {eventPtcpId, atndYn} 목록. 비울 수 없다")
                    AttendancePatchRequest request,
            McpTransportContext context) {
        log.info("mcp tool correct_academic_attendances sessionId={}", sessionId);
        return client.patch(
                context,
                PROGRAMS + "/" + academicProgramId + SESSIONS + sessionId + ATTENDANCES,
                request,
                AttendancePatchResponse.class);
    }
}
