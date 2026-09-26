package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelAssignRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelAssignmentResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseReviewRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 폼 도구 (ssccops#365 W2 · #494 → W4 · #587 · ADR-0027) — 조회 · 접수 열고 닫기 · 라벨 지정 · 응답 심사.
 *
 * **문항을 만들거나 고치는 도구는 없다.** 문항 구성(qitemCpstCn)은 편집기가 쥐는 JSON이라 자연어로 받아
 * 조립하면 편집기가 못 여는 폼이 생긴다 — «이 내용으로 모집폼 만들어줘»는 편집기와 같은 스키마 검증을
 * 붙인 뒤이고, 지금은 ssccops#365의 범위 밖이다.
 *
 * 접수 상태 판정은 화면과 같은 파생값(receiptStatus · ADR-0019)이다.
 *
 * ── 응답 **상세**를 열지 않는 이유 (#587 결정, 2026-09-26) ──────
 *
 * `get_form_response`에 해당하는 도구가 없다. W2에서 «응답 도구는 별도 결정»으로 미뤄 둔 자리이고,
 * 그 결정이 «열지 않는다»다.
 *
 * `FormResponseDetailResponse.rspnsCn`은 `ResponseContent` = **`Map<String, Object> answers`**이고
 * **키가 문항 id**다. `ToolOutputRedactor`는 **필드 이름**으로 지우므로(stdntNo·telno·eml 등 여섯) 이
 * 맵 **안에는 닿지 않는다.** 그리고 문항은 운영진이 자유롭게 만든다 — 연락처·주소·자기소개를 묻는
 * 문항이 있으면 그 값이 그대로 나간다. **서버는 그 답에 무엇이 들었는지 모른다.**
 *
 * ADR-0037이 못 박은 «이름은 싣고 연락처·이메일·학번은 지운다»는 **서버가 모양을 아는 필드**에 대한
 * 규칙이다. 답 원문은 그 규칙이 적용될 수 있는 대상이 아니라 같은 ADR로 덮이지 않는다.
 *
 * 그래서 이 파도가 여는 것은 **목록과 심사**다. 목록은 순번·**대표 문항의 답**(responseTitle — 기획안
 * 이면 활동명, 선언은 SystemFormContract)·상태·제출 일시·응답자를 주므로 «심사 대기 몇 건이야»·
 * «3번 승인해줘»가 된다. **답 원문을 읽어야 결론이 나는 폼(기획안 심사)은 화면에서** 하고, 도구
 * 설명이 그 말을 한다. 미룬 것이 아니라 지금 구조에서 이게 맞다 — 열려면 rspnsCn을 통째로 비우는
 * 규칙을 redactor에 더하거나(그러면 상세가 목록과 거의 같아진다) 새 ADR이 선행이다.
 */
@Component
@RequiredArgsConstructor
public class FormTools {

    private static final Logger log = LoggerFactory.getLogger(FormTools.class);

    private final McpRestClient client;

    /** list_forms의 조건 — 전부 선택. receiptStatuses는 여러 값의 합집합 */
    public record FormListCondition(List<FormReceiptStatus> receiptStatuses, Long labelId) {}

    @McpTool(
            name = "list_forms",
            description =
                    "폼 목록(카드 목록 그대로 — 제목·접수 상태·기간·라벨·응답 수). receiptStatuses에"
                            + " DRAFT·SCHEDULED·ACCEPTING·EXPIRED·CLOSED를 여러 개 줄 수 있고 비우면 전체."
                            + " labelId로 라벨을 거른다. 문항은 없다 — get_form으로. 폼 조회(FORM_READ) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<FormSummaryResponse> listForms(
            @McpToolParam(description = "검색 조건 — receiptStatuses·labelId. 전부 선택", required = false)
                    FormListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_forms");
        return client.getList(context, "/v1/forms", condition, FormSummaryResponse.class).items();
    }

    @McpTool(
            name = "get_form",
            description =
                    "폼 하나 — 문항 구성(qitemCpstCn)·접수 기간·상태·공개 주소용 formKey까지. 없거나 지운"
                            + " 폼은 404. 폼 조회(FORM_READ) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public FormDetailResponse getForm(
            @McpToolParam(description = "폼 id") Long formId, McpTransportContext context) {
        log.info("mcp tool get_form formId={}", formId);
        return client.get(context, "/v1/forms/" + formId, FormDetailResponse.class);
    }

    @McpTool(
            name = "change_form_status",
            description =
                    "폼의 접수를 열거나 닫는다 — action에 OPEN(작성 중·마감 → 접수) 또는 CLOSE(접수 → 마감)."
                            + " 허용되지 않는 전이는 409. 접수 기간이 정해져 있으면 OPEN 해도 그 기간 밖에서는"
                            + " «접수 예정»·«기간 종료»로 보인다(파생값). 폼 상태 변경(FORM_STATUS_CHANGE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public FormStatusChangeResponse changeFormStatus(
            @McpToolParam(description = "폼 id") Long formId,
            @McpToolParam(description = "action — OPEN 또는 CLOSE") FormStatusChangeRequest request,
            McpTransportContext context) {
        log.info("mcp tool change_form_status formId={}", formId);
        return client.post(
                context,
                "/v1/forms/" + formId + "/status",
                request,
                FormStatusChangeResponse.class);
    }

    /* ── 라벨 ─────────────────────────────────────────────── */

    /** list_form_labels의 조건 — useYn만. 생략하면 활성·비활성 전부 */
    public record FormLabelListCondition(Boolean useYn) {}

    @McpTool(
            name = "list_form_labels",
            description =
                    "폼 라벨 목록(이름 오름차순 · 각 라벨이 걸린 폼 수 usageCount 포함). useYn=true면 활성만,"
                            + " false면 비활성만, 비우면 전부. **assign_form_labels에 넘길 formLblId가 여기서"
                            + " 나온다.** 라벨을 새로 만들거나 비활성으로 바꾸는 도구는 없다 — 한 학기에 몇 번"
                            + " 손대는 기준정보라 화면에서 한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<FormLabelResponse> listFormLabels(
            @McpToolParam(description = "useYn — 활성만/비활성만. 생략하면 전부", required = false)
                    FormLabelListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_form_labels");
        return client.getList(context, "/v1/form-labels", condition, FormLabelResponse.class)
                .items();
    }

    @McpTool(
            name = "assign_form_labels",
            description =
                    "폼에 걸린 라벨을 labelIds로 **통째로 교체한다.** 요청에 없는 라벨은 해제되고 빈 배열이면"
                            + " 전부 해제다 — 한 개를 더하려면 **지금 걸린 것들을 함께 보내야 한다**(get_form의"
                            + " labels를 먼저 읽을 것). 같은 요청을 두 번 보내도 결과가 같고 유지되는 지정은"
                            + " 지정 시각이 보존된다. 비활성 라벨은 새로 더할 때만 400 FORM_LABEL_NOT_USABLE로"
                            + " 막히고 이미 걸려 있던 것은 유지된다. 폼 쓰기(FORM_WRITE) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public List<FormLabelAssignmentResponse> assignFormLabels(
            @McpToolParam(description = "폼 id") Long formId,
            @McpToolParam(description = "labelIds — 이 폼에 걸어 둘 라벨 id 전부. 빈 배열이면 전부 해제")
                    FormLabelAssignRequest request,
            McpTransportContext context) {
        log.info("mcp tool assign_form_labels formId={}", formId);
        FormLabelAssignmentResponse[] assignments =
                client.put(
                        context,
                        "/v1/forms/" + formId + "/labels",
                        request,
                        FormLabelAssignmentResponse[].class);
        return assignments == null ? List.of() : Arrays.asList(assignments);
    }

    /* ── 응답 ─────────────────────────────────────────────── */

    /** list_form_responses의 조건 — statusCode만. 기본값 규칙은 도구 설명에 있다 */
    public record FormResponseListCondition(ResponseStatus statusCode) {}

    @McpTool(
            name = "list_form_responses",
            description =
                    "폼 응답 목록(제출 일시 내림차순) — 순번 rspnsSeq · **대표 문항의 답 responseTitle** ·"
                            + " 상태 · 제출 일시 · 응답자(이름·학과·등급·상태). **statusCode를 생략하면 작성"
                            + " 중(DRAFT)을 뺀 전부**이고 작성 중은 statusCode=DRAFT로 명시했을 때만 나온다 —"
                            + " 제출 전 답안이 심사 대기 목록에 섞이지 않게 하는 규칙이다. 값은 DRAFT ·"
                            + " SUBMITTED · ACCEPTED · CHANGES_REQUESTED · REJECTED."
                            + " responseTitle은 그 폼의 대표 문항이 서버에 선언돼 있을 때만 있고 없으면 null이다"
                            + " (순번으로 가리킬 것). **답 원문은 목록에도 상세에도 도구로 나오지 않는다** —"
                            + " 답을 읽어야 결론이 나는 심사는 어드민 화면에서 한다."
                            + " 응답자의 학번은 도구 출력에서 지워진다(ADR-0037)."
                            + " 응답 심사(RESPONSE_REVIEW) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<FormResponseSummaryResponse> listFormResponses(
            @McpToolParam(description = "폼 id") Long formId,
            @McpToolParam(description = "statusCode로 거르기 — 생략하면 작성 중을 뺀 전부", required = false)
                    FormResponseListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_form_responses formId={}", formId);
        return client.getList(
                        context,
                        "/v1/forms/" + formId + "/responses",
                        condition,
                        FormResponseSummaryResponse.class)
                .items();
    }

    @McpTool(
            name = "review_form_response",
            description =
                    "응답을 심사한다 — rspnsSttsCd에 ACCEPTED(승인) · CHANGES_REQUESTED(수정요청) ·"
                            + " REJECTED(반려). **수정요청·반려는 rvwOpnnCn(검토 의견)이 필수**이고 승인은"
                            + " 선택이다(비면 400 REVIEW_OPINION_REQUIRED)."
                            + " **승인·반려는 종결이라 되돌릴 수 없다** — 이미 ACCEPTED·REJECTED인 응답에 다시"
                            + " 걸면 400 INVALID_RESPONSE_STATUS_TRANSITION이고 **재시도해도 같다.** 같은 상태로"
                            + " 다시 지정하는 것, SUBMITTED로 되돌리는 것, DRAFT가 얽힌 전이도 같은 400이다 —"
                            + " 미심사로 돌아가는 길은 응답자의 재제출뿐이다. 아직 결론이 나지 않은 SUBMITTED ·"
                            + " CHANGES_REQUESTED에서는 셋 중 무엇이든 고를 수 있다."
                            + " 다른 폼의 응답 id는 없는 응답과 같은 404 FORM_RESPONSE_NOT_FOUND다."
                            + " 처리자는 인증 주체에서 오므로 요청에 담지 않는다."
                            + " 응답 심사(RESPONSE_REVIEW) 권한.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public FormResponseSummaryResponse reviewFormResponse(
            @McpToolParam(description = "폼 id") Long formId,
            @McpToolParam(description = "응답 id — list_form_responses의 formRspnsId")
                    Long formRspnsId,
            @McpToolParam(description = "rspnsSttsCd와 rvwOpnnCn — 수정요청·반려는 의견 필수")
                    FormResponseReviewRequest request,
            McpTransportContext context) {
        log.info("mcp tool review_form_response formId={} formRspnsId={}", formId, formRspnsId);
        return client.post(
                context,
                "/v1/forms/" + formId + "/responses/" + formRspnsId + "/reviews",
                request,
                FormResponseSummaryResponse.class);
    }
}
