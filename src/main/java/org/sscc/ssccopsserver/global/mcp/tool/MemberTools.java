package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeHistoryResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignmentResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleUpdateRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSummaryResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleResponse;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 회원 도구 (ssccops#365 W5 · #589 · ADR-0027 · ADR-0037) — «○○의 등급을 정회원으로»가 되게.
 *
 * ── 이 클래스가 존재할 수 있는 근거 ────────────────────────────
 *
 * 1차(ADR-0027)가 회원을 뺀 이유는 개인정보였다. ADR-0037이 **«이름은 싣고 연락처·이메일·학번은
 * 지운다»**로 그 자리를 정했고, 저울을 기울인 것은 «이름이 없으면 도구가 아니다»다 — 운영진의 말은
 * «○○의 등급을 정회원으로»이고 id 로는 그 문장이 만들어지지 않는다. 지우는 세 값은 **운영진이
 * 대화로 물을 이유가 없는 것**이라 실용성을 잃지 않는다.
 *
 * 그 마스킹이 실제로 도는지는 `ToolOutputRedactor`가 답한다. 회원 도메인 DTO 는 `phoneNumber`·
 * `email`·`studentNumber`를, 응답·역할 쪽은 데이터사전 약어 `telno`·`eml`·`stdntNo`를 쓰므로
 * **목록이 여섯이고**(#567) `ToolOutputRedactorCoverageTest`가 `domain/…/dto` 전수를 훑어 새 이름이
 * 생기면 깨진다. 이 클래스를 열 수 있게 한 것이 그 대조다 — 문장이 아니라 검사다.
 *
 * ── 없는 도구와 그 이유 ───────────────────────────────────────
 *
 * - **일괄 등급·상태 변경이 없다**(`POST /v1/members/{grade,status}-changes`). 한 호출이 여러 명을
 *   바꾸고 부분 성공이 정상 응답이라, 모델이 «2학년 전부 정회원으로» 같은 **대상 집합을 스스로
 *   만들면** 틀린 집합 하나가 되돌리기 어려운 변경 여러 건이 된다. 화면은 사람이 체크박스로 고른
 *   것이 집합이라 사정이 다르다.
 * - **회원 정보 수정이 없다**(`PATCH /v1/members/{memberId}`). 이름·학번·학과·연락처를 고치는
 *   자리라 **출력에서 지운 값을 입력으로 받는 도구**가 되어 ADR-0037과 어긋난다. 명부 정정은
 *   화면·CSV 이관이 하는 일이다.
 * - **하드 삭제가 없다**(ADR-0037 · ADR-0021). 화면은 그 앞에 회원명 직접 입력을 세워 두는데 도구
 *   에는 그 계단을 만들 방법이 없다 — 모델이 그 이름을 알고 있으므로 «확인»만 남는다.
 * - **역할·권한 트리 편집이 없다**(ssccops#365 범위 밖). `list_roles`만 있는 것은 부여에 필요한
 *   `roleId`를 얻기 위한 읽기다(`list_form_labels`가 라벨 id 를 주는 것과 같은 자리).
 *
 * ── 목록 상한은 서버가 이미 막는다 ────────────────────────────
 *
 * ADR-0037은 «명부 전량이 한 호출로 나가지 않게 목록 도구의 페이지 상한을 도구가 고정한다»를
 * 따라오는 일로 적었다. 실제로는 `MemberSearchCondition`이 `@Max(100)`으로 **서버에서** 막고
 * 기본이 20 이라, 도구에서 같은 숫자를 다시 고정하면 상한이 두 곳에 생겨 서버가 바뀔 때 갈린다.
 * 그 요구는 이미 충족돼 있으므로 도구는 조건을 그대로 넘긴다 — 넘긴 값이 규칙을 어기면 서버의
 * 400 이 곧 안내다.
 */
@Component
@RequiredArgsConstructor
public class MemberTools {

    private static final Logger log = LoggerFactory.getLogger(MemberTools.class);

    private static final String MEMBERS = "/v1/members";
    private static final String MEMBER_ID = "회원 id";

    private final McpRestClient client;

    /** list_member_histories의 조건 — type 복수. 생략하면 전부 */
    public record MemberHistoryListCondition(List<String> type) {}

    /** list_roles의 조건 — 분류 코드로 좁힌다 */
    public record RoleListCondition(String roleClsfCd) {}

    /** list_member_roles의 조건 — current=true면 지금 유효한 배정만 */
    public record MemberRoleListCondition(Boolean current) {}

    @McpTool(
            name = "list_members",
            description =
                    "회원 목록 — 이름·학번 부분일치 q, 등급 mbrGrdCd·상태 mbrSttsCd 필터(둘 다 복수),"
                            + " 정렬 sort(mbrNm·genNo·sysJoinYmd·mdfcnDt, 앞에 '-'를 붙이면 내림차순),"
                            + " 커서 페이징(size 기본 20 · 최대 100, 넘기면 400). 등급·상태는 코드와"
                            + " 표시명을 함께 내리고 현재 역할도 실린다."
                            + " **연락처·이메일·학번은 도구 출력에서 지워진다**(ADR-0037) — 그 값이 필요하면"
                            + " 어드민 화면에서 본다. 기준 코드 밖의 필터 값은 400 INVALID_CODE_VALUE."
                            + " 회원 관리(MEMBER_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<MemberSummaryResponse> listMembers(
            @McpToolParam(
                            description = "검색 조건 — q·mbrGrdCd·mbrSttsCd·sort·size·cursor. 전부 선택",
                            required = false)
                    MemberSearchCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_members");
        return client.getList(context, MEMBERS, condition, MemberSummaryResponse.class).items();
    }

    @McpTool(
            name = "get_member",
            description =
                    "회원 한 명 — 등급·상태·기수·학년·현재 역할과 최근 변경 이력(recentChanges)까지."
                            + " 연락처·이메일·학번은 지워진다(ADR-0037). 없는 회원은 404."
                            + " 회원 관리(MEMBER_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public MemberDetailResponse getMember(
            @McpToolParam(description = MEMBER_ID) Long memberId, McpTransportContext context) {
        log.info("mcp tool get_member memberId={}", memberId);
        return client.get(context, MEMBERS + "/" + memberId, MemberDetailResponse.class);
    }

    @McpTool(
            name = "change_member_grade",
            description =
                    "회원 등급을 바꾸고 변경 이력을 한 트랜잭션에서 남긴다. aftrMbrGrdCd는 기준 코드의"
                            + " 등급 코드(코드 목록은 어드민 기준정보에 있다 — 표시명으로 보내면 400"
                            + " INVALID_CODE_VALUE). grdAplcnYmd를 생략하면 오늘이고 **미래 일자는 400**,"
                            + " 지금과 같은 등급은 400 NO_CHANGE, 없는 회원은 404다."
                            + " grdChgRsnCn(사유, 500자)은 이력에 남으므로 왜 바꾸는지 함께 보낼 것."
                            + " 변경자는 인증 주체에서 오므로 요청에 담지 않는다."
                            + " 회원 관리(MEMBER_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MemberGradeChangeResponse changeMemberGrade(
            @McpToolParam(description = MEMBER_ID) Long memberId,
            @McpToolParam(description = "aftrMbrGrdCd(필수) · grdAplcnYmd · grdChgRsnCn")
                    MemberGradeChangeRequest request,
            McpTransportContext context) {
        log.info("mcp tool change_member_grade memberId={}", memberId);
        return client.post(
                context,
                MEMBERS + "/" + memberId + "/grade-changes",
                request,
                MemberGradeChangeResponse.class);
    }

    @McpTool(
            name = "change_member_status",
            description =
                    "회원 상태를 바꾸고 변경 이력을 한 트랜잭션에서 남긴다. **sttsEndPrnmntYmd(종료"
                            + " 예정일)는 휴학·군휴학에만 실을 수 있고** 그 밖의 상태에 실려 오면 400이다."
                            + " **탈퇴·제명으로 바꿔도 역할과 담당 업무를 자동으로 정리하지 않는다** —"
                            + " 남아 있는 현재 역할·담당 하위 업무 건수가 응답의 warnings로 오므로 그것을"
                            + " 사용자에게 알릴 것(정리는 역할 종료·담당자 변경으로 따로 한다)."
                            + " 적용 일자 생략은 오늘, 미래 일자는 400, 같은 상태는 400 NO_CHANGE,"
                            + " 기준 코드 밖은 400 INVALID_CODE_VALUE, 없는 회원은 404다."
                            + " 회원 관리(MEMBER_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MemberStatusChangeResponse changeMemberStatus(
            @McpToolParam(description = MEMBER_ID) Long memberId,
            @McpToolParam(
                            description =
                                    "aftrMbrSttsCd(필수) · sttsAplcnYmd · sttsEndPrnmntYmd(휴학·군휴학만)"
                                            + " · sttsChgRsnCn")
                    MemberStatusChangeRequest request,
            McpTransportContext context) {
        log.info("mcp tool change_member_status memberId={}", memberId);
        return client.post(
                context,
                MEMBERS + "/" + memberId + "/status-changes",
                request,
                MemberStatusChangeResponse.class);
    }

    @McpTool(
            name = "list_member_histories",
            description =
                    "회원의 등급·상태·역할·정보 변경 이력을 한 타임라인으로(발생 시각 역순). type으로"
                            + " 출처를 고른다 — GRADE·STATUS·ROLE·PROFILE, 복수 허용, 생략하면 전부."
                            + " 역할은 한 배정이 부여·종료 두 줄로 나오고 **역할 줄의 changedBy는 항상"
                            + " null이다**(그 표에 변경자 컬럼이 없다). 없는 회원은 404, 알 수 없는 type은"
                            + " 400이다. 회원 관리(MEMBER_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<MemberChangeHistoryResponse> listMemberHistories(
            @McpToolParam(description = MEMBER_ID) Long memberId,
            @McpToolParam(
                            description = "type — GRADE·STATUS·ROLE·PROFILE 중 여럿. 생략하면 전부",
                            required = false)
                    MemberHistoryListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_member_histories memberId={}", memberId);
        return client.getList(
                        context,
                        MEMBERS + "/" + memberId + "/histories",
                        condition,
                        MemberChangeHistoryResponse.class)
                .items();
    }

    @McpTool(
            name = "list_roles",
            description =
                    "역할 목록(분류 순번 → 역할 순번). roleClsfCd로 분류만 걸러 낸다. memberCount는 지금"
                            + " 그 역할을 맡고 있는 회원 수다(종료된 배정은 세지 않는다)."
                            + " **assign_member_role에 넘길 roleId가 여기서 나온다.** 역할을 만들거나"
                            + " 고치는 도구는 없다 — 역할·권한 트리 편집은 화면에서 한다."
                            + " 역할 관리(ROLE_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<RoleResponse> listRoles(
            @McpToolParam(description = "roleClsfCd — 분류 코드로 좁히기. 선택", required = false)
                    RoleListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_roles");
        return client.getList(context, "/v1/roles", condition, RoleResponse.class).items();
    }

    @McpTool(
            name = "list_member_roles",
            description =
                    "회원의 역할 배정을 시작일 내림차순으로. current=true면 지금 유효한 것만, 생략하면"
                            + " **종료된 지난 임기까지 전부**다(종료는 삭제가 아니다) — 행마다 실리는"
                            + " current가 그중 유효한 것을 가리킨다. mbrRoleId는"
                            + " update_member_role_assignment에 넘길 값이다."
                            + " 역할 관리(ROLE_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<MemberRoleAssignmentResponse> listMemberRoles(
            @McpToolParam(description = MEMBER_ID) Long memberId,
            @McpToolParam(description = "current — true면 지금 유효한 배정만. 선택", required = false)
                    MemberRoleListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_member_roles memberId={}", memberId);
        return client.getList(
                        context,
                        MEMBERS + "/" + memberId + "/roles",
                        condition,
                        MemberRoleAssignmentResponse.class)
                .items();
    }

    @McpTool(
            name = "assign_member_role",
            description =
                    "회원에게 역할을 부여한다(roleId는 list_roles에서). roleBgngYmd를 생략하면 오늘이고"
                            + " **종료일은 받지 않는다** — 부여는 언제나 무기한으로 시작하고 끝내는 것은"
                            + " update_member_role_assignment다. rprsRoleYn=true로 대표를 지정하면 그"
                            + " 회원의 기존 대표 역할이 같은 트랜잭션에서 내려간다."
                            + " 같은 역할이 기간을 겹쳐 이미 부여돼 있으면 409 ROLE_ALREADY_ASSIGNED이고"
                            + " **재시도해도 같다**(기간이 겹치지 않는 재임은 허용한다)."
                            + " 없는 회원은 404, 없는 역할은 404 ROLE_NOT_FOUND다."
                            + " 부여는 재로그인 없이 다음 요청부터 반영된다."
                            + " 역할 관리(ROLE_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MemberRoleAssignmentResponse assignMemberRole(
            @McpToolParam(description = MEMBER_ID) Long memberId,
            @McpToolParam(description = "roleId(필수) · roleBgngYmd · rprsRoleYn")
                    MemberRoleAssignRequest request,
            McpTransportContext context) {
        log.info("mcp tool assign_member_role memberId={}", memberId);
        return client.post(
                context,
                MEMBERS + "/" + memberId + "/roles",
                request,
                MemberRoleAssignmentResponse.class);
    }

    @McpTool(
            name = "update_member_role_assignment",
            description =
                    "역할 배정의 종료일과 대표 여부를 바꾼다(mbrRoleId는 list_member_roles에서)."
                            + " **roleEndYmd를 채우는 것이 임기를 끝내는 유일한 길이고 행을 지우지 않는다** —"
                            + " 지우면 «언제까지 그 역할이었는가»가 사라진다. null인 필드는 건드리지 않고"
                            + " 시작일은 바꿀 수 없다."
                            + " 종료일이 시작일보다 이르면 400, 다른 회원의 배정이거나 없는 배정은 404다."
                            + " **요청자 자신이 이 조작으로 역할 관리 권한을 잃게 되면 409"
                            + " CANNOT_REVOKE_OWN_ROLE_MANAGE로 거절한다**(다른 사람이 끝내는 것은 막지"
                            + " 않는다) — 재시도해도 같으므로 사용자에게 알리고 멈출 것."
                            + " 역할 관리(ROLE_MANAGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public MemberRoleAssignmentResponse updateMemberRoleAssignment(
            @McpToolParam(description = MEMBER_ID) Long memberId,
            @McpToolParam(description = "역할 배정 id — list_member_roles의 mbrRoleId") Long mbrRoleId,
            @McpToolParam(description = "roleEndYmd · rprsRoleYn — 준 것만 바뀐다")
                    MemberRoleUpdateRequest request,
            McpTransportContext context) {
        log.info(
                "mcp tool update_member_role_assignment memberId={} mbrRoleId={}",
                memberId,
                mbrRoleId);
        return client.patch(
                context,
                MEMBERS + "/" + memberId + "/roles/" + mbrRoleId,
                request,
                MemberRoleAssignmentResponse.class);
    }
}
