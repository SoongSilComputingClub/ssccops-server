package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 폼 도구 (ssccops#365 W2 · #494 · ADR-0027) — 조회와 접수 열고 닫기까지.
 *
 * **문항을 만들거나 고치는 도구는 없다.** 문항 구성(qitemCpstCn)은 편집기가 쥐는 JSON이라 자연어로 받아
 * 조립하면 편집기가 못 여는 폼이 생긴다 — «이 내용으로 모집폼 만들어줘»는 다음 파도에서 편집기와 같은
 * 스키마 검증을 붙인 뒤. 응답(개인정보)도 여기 없다(ADR-0037의 마스킹은 이름을 남기므로 응답 도구는
 * 별도 결정).
 *
 * 접수 상태 판정은 화면과 같은 파생값(receiptStatus · ADR-0019)이다.
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
}
