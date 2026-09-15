package org.sscc.ssccopsserver.domain.assistant.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentApplyStatusUpdateRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentDetailResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentListResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentResponse;
import org.sscc.ssccopsserver.domain.assistant.service.RagDocumentService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 규정 도우미 코퍼스 API (#399 · #401 · 기획안 §10 · ADR-0029).
 *
 * **클래스 레벨 `@RequireAuthority(RAG_DOCUMENT_MANAGE)`이고 질의 컨트롤러(#403)와 나뉜다.**
 * 인가 요구가 다르기 때문이며(질의는 인증만 — 규정은 회원에게 공개된 문서다), 한 클래스에 두면
 * 클래스 레벨로 걸 수 없어 핸들러마다 붙이게 되고 **하나 빠뜨리는 순간 코퍼스가 열린다.**
 * 코퍼스를 바꾸는 것은 모든 답변의 근거를 갈아치우는 조작이라 프롬프트 인젝션 완화의 첫째
 * 층이기도 하다(§6.4).
 *
 * **`/public/v1/**` 아래에 두지 않는다** — 그 접두사에 핸들러를 더하는 것은 permitAll을 더하는
 * 것과 같다(루트 AGENTS.md).
 *
 * **목록 조회도 클래스 레벨 인가에서 예외가 아니다**(#401). 코퍼스에 무엇이 올라와 있는지도
 * 코퍼스 조작의 정보이고, 무엇보다 «읽기만 하는 핸들러는 빼도 된다»를 한 번 허용하면 그 판단을
 * 핸들러마다 다시 해야 한다 — `RoleAuthorityController`·`FormTemplateController`와 같은 판단이다.
 *
 * 기능 플래그(`ssccops.assistant.enabled`)가 꺼져 있으면 404 `ASSISTANT_DISABLED`이며 그 판정은
 * 서비스가 한다 — 컨트롤러가 먼저 보면 핸들러가 늘 때 빠뜨릴 자리가 하나 더 생긴다.
 */
@RestController
@RequestMapping("/v1/assistant/documents")
@RequireAuthority(AuthorityCode.RAG_DOCUMENT_MANAGE)
@RequiredArgsConstructor
public class RagDocumentController {

    private final RagDocumentService ragDocumentService;

    /*
     * 업로드 — **파싱까지 이 요청 안에서 하고 임베딩은 부르지 않는다**(§2).
     *
     * **201이고 `indexStatus`는 `PENDING`이다.** 202가 아닌 것은 행이 실제로 생겼기 때문이며,
     * 화면은 이 응답으로 목록의 «대기» 행을 즉시 그린다(§13.2). 202는 «받아 두었으나 아직
     * 아무것도 없다»는 뜻이라 그릴 것이 없어진다.
     *
     * **`docType`을 요청이 신고하지 않는다** — 확장자가 정한다(#210과 같은 판단). 신고를 받으면
     * 신고와 내용이 어긋난 파일을 다뤄야 한다.
     *
     * `Location`이 가리키는 상세 조회는 아래 `GET /{ragDocId}`다(#401이 열었다).
     *
     * `name`을 `@RequestPart`가 아니라 `@RequestParam`으로 받는 것은 화면이
     * `FormData.append('name', …)`로 보내는 값이 파트가 아니라 폼 필드이기 때문이다
     * (CSV 이관 위저드와 같은 모양 · #84). `required = false`로 받아 **누락도 서비스가 도메인
     * 오류로 돌려주는 것**은, 서블릿이 먼저 끊으면 `ApiResponse` 봉투가 붙지 않은 응답이 나가기
     * 때문이다.
     *
     * **`documentCode`가 있던 자리다**(ADR-0034). 화면이 「비우면 서버가 정합니다」로 안내했는데
     * 서버에는 그 경로가 없었고, 자동 생성은 이 값의 존재 이유(판본을 묶는다)와 모순이라 만들 수도
     * 없었다 — 그래서 고친 것이 문구가 아니라 값 자체다.
     */
    @Operation(
            summary = "규정 문서 업로드",
            description =
                    "문서 원본을 받아 그 자리에서 파싱한 뒤 새 문서를 PENDING·DRAFT로 만든다(201)."
                            + " 색인(임베딩)은 이 요청에서 하지 않으며 워커가 뒤에서 집어 간다."
                            + " file은 .md·.pdf·.docx 중 하나이고 10MB 이하다 — 밖이면 400"
                            + " RAG_DOCUMENT_UNSUPPORTED_TYPE, 넘으면 413 RAG_DOCUMENT_TOO_LARGE다."
                            + " 유형(STRUCTURED·GENERIC)은 요청이 신고하지 않고 확장자가 정한다."
                            + " .md는 회칙 계약(장·조)을 검사해 어기면 400"
                            + " RAG_DOCUMENT_PARSE_FAILED이고 **몇째 줄이 왜 걸렸는지**가 message에"
                            + " 실린다. .pdf·.docx는 텍스트가 한 글자도 추출되지 않으면 같은 코드다"
                            + " (스캔 이미지 PDF). 판본 개념이 없으므로 같은 규정을 갱신할 때는"
                            + " 옛 문서를 지우고 새로 올린다(ADR-0034). name을 비우면 파일명에서"
                            + " 확장자를 뗀 것이 표시명이 된다. 올린 회원은 요청이 아니라 인증"
                            + " 주체에서 오며, 적재는 회원당 하루 10건까지다(429"
                            + " ASSISTANT_RATE_LIMITED).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RagDocumentResponse>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @CurrentMember MemberEntity registrant) {

        RagDocumentResponse response = ragDocumentService.upload(file, name, registrant);
        URI location = URI.create("/v1/assistant/documents/" + response.ragDocId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 목록 — **요약 3값을 같은 응답에 싣는다**(§10 · §13.2).
     *
     * 별도 요약 엔드포인트를 두지 않은 것은 화면이 카드와 표를 언제나 함께 그리기 때문이다:
     * 나누면 두 요청 사이에 색인이 끝나 **카드와 표가 다른 시점을 가리킨다**(폼 상세가
     * `responseSummary`를 함께 내리는 것과 같은 자리 · #37).
     *
     * **검색은 서버가 한다** — 클라이언트 필터링은 «목록을 통째로 내려받은 뒤»에만 성립하고,
     * 그 전제가 깨지는 날 화면과 서버를 함께 고쳐야 한다.
     */
    @Operation(
            summary = "규정 문서 목록",
            description =
                    "등록된 문서 판본을 최신 업로드 순으로 전부 내려준다(페이징 없음 — 이 표는 문서 종류 ×"
                            + " 판본이라 행이 수십 단위다). 화면의 카드 셋이 쓰는 요약 3값"
                            + "(registeredCount·indexedCount·totalChunkCount)이 같은 응답의 summary에"
                            + " 함께 실린다 — 별도 요청으로 나누면 두 요청 사이에 색인이 끝나 카드와"
                            + " 표가 다른 시점을 가리킨다. q를 주면 문서명 부분 일치(대소문자 무시)로"
                            + " 거르며, **요약은 q와 무관하게 언제나 코퍼스 전체**다."
                            + " totalChunkCount는 활성 청크(INDEXED이고 옛 판본이 아닌 것)의 합이며"
                            + " 색인 워커가 상한 3,000을 판정할 때 보는 수와 같다.")
    @GetMapping
    public ApiResponse<RagDocumentListResponse> list(
            @RequestParam(value = "q", required = false) String keyword) {

        return ApiResponse.success(ragDocumentService.list(keyword));
    }

    /*
     * 상세 — 목록 한 행 + 조 목록(`STRUCTURED`) · 원본 다운로드 URL · 색인 시각.
     *
     * 다운로드 URL은 **서명만 받아 온다**(#220) — 「누가 볼 수 있는가」는 클래스 레벨
     * `@RequireAuthority`가 이미 끝냈다. 조 목록은 R2의 원본을 다시 파싱해 만든다(서비스 주석).
     */
    @Operation(
            summary = "규정 문서 상세",
            description =
                    "판본 하나의 상세. document에 목록 한 행과 같은 값이 그대로 들어가고, 여기에 등록자·색인"
                            + " 시작/종료 시각·원본 다운로드 URL이 더해진다. downloadUrl은 15분짜리"
                            + " 서명된 R2 주소이며 남은 시간이 downloadUrlExpiresInSeconds다 —"
                            + " 만료되면 이 상세를 다시 부른다. articles는 docType이 STRUCTURED일 때만"
                            + " 채워지고(원본을 다시 파싱한다) GENERIC은 조가 없어 빈 배열이다."
                            + " 색인 실패 사유는 document.failureReason이다. 없는 판본은 404"
                            + " RAG_DOCUMENT_NOT_FOUND — 삭제가 하드라 «없음»이 정상 상태다.")
    @GetMapping("/{ragDocId}")
    public ApiResponse<RagDocumentDetailResponse> detail(@PathVariable Long ragDocId) {
        return ApiResponse.success(ragDocumentService.detail(ragDocId));
    }

    /*
     * 적용 상태 전환 — `DRAFT → EFFECTIVE` · `EFFECTIVE → SUPERSEDED`(§5.5).
     *
     * **색인 상태를 여기서 바꾸지 않는다** — 사람이 정하는 값이 아니라 워커가 적는 값이다.
     * 열면 «색인 완료»인데 청크가 없는 행이 생긴다.
     */
    @Operation(
            summary = "규정 문서 적용 상태 전환",
            description =
                    "DRAFT → EFFECTIVE(시행 중으로 올리기) 또는 EFFECTIVE → SUPERSEDED(내려두기)."
                            + " **다른 문서를 함께 내리지 않으며 시행 중인 문서는 여러 건일 수"
                            + " 있다**(ADR-0034 — 갱신된 규정의 옛 문서를 지우는 것은 운영진의 몫이다)."
                            + " SUPERSEDED로 내리면 그 문서의 청크가 커밋 뒤에 지워진다. **INDEXED가"
                            + " 아니면 올릴 수"
                            + " 없다 — 409 RAG_DOCUMENT_NOT_INDEXED**(통과시키면 «시행 중인데 검색되지"
                            + " 않는 문서»가 된다). 그 밖의 전이(SUPERSEDED에서 되돌리기, DRAFT로"
                            + " 내리기)는 400 INVALID_RAG_APPLY_STATUS_TRANSITION이다 — 되돌리려면 그"
                            + " 파일을 다시 올린다. effectiveFrom을 비우면 오늘이 들어가며"
                            + " 답변의 «시행 기준» 배지가 그 값이다. 색인 상태는 이 API가 바꾸지"
                            + " 않는다(워커가 적는 값이다).")
    @PatchMapping("/{ragDocId}/apply-status")
    public ApiResponse<RagDocumentResponse> changeApplyStatus(
            @PathVariable Long ragDocId,
            @Valid @RequestBody RagDocumentApplyStatusUpdateRequest request) {

        return ApiResponse.success(ragDocumentService.changeApplyStatus(ragDocId, request));
    }

    /* 재색인 — 상태 지정이 아니라 **`PENDING`으로 다시 줄을 세우는 조작**이다(#400) */
    @Operation(
            summary = "규정 문서 재색인",
            description =
                    "판본을 색인 대기(PENDING)로 되돌린다 — 워커가 그 상태만 집으므로 이것이 재색인의"
                            + " 전부이며, 상태를 직접 지정하는 API는 없다. 청크는 여기서 지우지 않고"
                            + " 워커가 새 청크를 넣기 직전에 지운다(중간에 실패해도 직전 색인의 답이"
                            + " 사라지지 않는다). 이미 대기 중인 판본에 부르면 400"
                            + " INVALID_RAG_INDEX_STATUS_TRANSITION이다.")
    @PostMapping("/{ragDocId}/reindex")
    public ApiResponse<RagDocumentResponse> reindex(@PathVariable Long ragDocId) {
        return ApiResponse.success(ragDocumentService.reindex(ragDocId));
    }

    /* 하드 삭제 — 행 · 청크 · R2 오브젝트(§5.6 · ADR-0029). **소프트 삭제가 아니고 되살리기가 없다** */
    @Operation(
            summary = "규정 문서 삭제",
            description =
                    "판본을 지운다 — **하드 삭제**다. 행·청크·R2 원본을 함께 지우며 되살리는 길이 없다"
                            + "(되돌리려면 같은 파일을 새 판본으로 올린다). 폼·행사의 소프트 삭제와"
                            + " 갈리는 것은 그쪽이 «치우기»이고 이쪽은 «잘못 올린 파일을 없었던 것으로"
                            + " 만들기»이기 때문이며, 남길 값이 있는 옛 판본은 SUPERSEDED가 맡는다."
                            + " 시행 중인 판본도 지울 수 있다 — 그 순간 그 규정에 대한 답이 사라지므로"
                            + " 화면이 확인을 받는다. 없는 판본은 404 RAG_DOCUMENT_NOT_FOUND다.")
    @DeleteMapping("/{ragDocId}")
    public ApiResponse<Void> delete(@PathVariable Long ragDocId) {
        ragDocumentService.delete(ragDocId);
        return ApiResponse.successWithNoData();
    }
}
