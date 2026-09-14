package org.sscc.ssccopsserver.domain.assistant.controller;

import java.net.URI;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentUploadResponse;
import org.sscc.ssccopsserver.domain.assistant.service.RagDocumentService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 규정 도우미 코퍼스 API (#399 · 기획안 §10 · ADR-0029).
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
     * `Location`이 가리키는 상세 조회는 **#401이 연다** — 그 전까지 따라가면 405다. 그래도 싣는
     * 것은 201의 계약이 «만들어진 자원이 여기 있다»이고, 주소가 나중에 서는 것이 주소를 아예
     * 말하지 않는 것보다 낫기 때문이다(경로는 §10이 이미 정해 두었다).
     *
     * `documentCode`·`name`을 `@RequestPart`가 아니라 `@RequestParam`으로 받는 것은 화면이
     * `FormData.append('documentCode', …)`로 보내는 값이 파트가 아니라 폼 필드이기 때문이다
     * (CSV 이관 위저드와 같은 모양 · #84). `required = false`로 받아 **누락도 서비스가 도메인
     * 오류로 돌려주는 것**은, 서블릿이 먼저 끊으면 `ApiResponse` 봉투가 붙지 않은 응답이 나가기
     * 때문이다.
     */
    @Operation(
            summary = "규정 문서 업로드",
            description =
                    "문서 원본을 받아 그 자리에서 파싱한 뒤 새 판본을 PENDING·DRAFT로 만든다(201)."
                            + " 색인(임베딩)은 이 요청에서 하지 않으며 워커가 뒤에서 집어 간다."
                            + " file은 .md·.pdf·.docx 중 하나이고 10MB 이하다 — 밖이면 400"
                            + " RAG_DOCUMENT_UNSUPPORTED_TYPE, 넘으면 413 RAG_DOCUMENT_TOO_LARGE다."
                            + " 유형(STRUCTURED·GENERIC)은 요청이 신고하지 않고 확장자가 정한다."
                            + " .md는 회칙 계약(장·조)을 검사해 어기면 400"
                            + " RAG_DOCUMENT_PARSE_FAILED이고 **몇째 줄이 왜 걸렸는지**가 message에"
                            + " 실린다. .pdf·.docx는 텍스트가 한 글자도 추출되지 않으면 같은 코드다"
                            + " (스캔 이미지 PDF). documentCode는 판본을 가로지르는 열쇠이며 같은"
                            + " 값으로 다시 올리면 doc_ver가 1 늘어난다. name을 비우면 파일명에서"
                            + " 확장자를 뗀 것이 표시명이 된다. 올린 회원은 요청이 아니라 인증"
                            + " 주체에서 오며, 적재는 회원당 하루 10건까지다(429"
                            + " ASSISTANT_RATE_LIMITED).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RagDocumentUploadResponse>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "documentCode", required = false) String documentCode,
            @RequestParam(value = "name", required = false) String name,
            @CurrentMember MemberEntity registrant) {

        RagDocumentUploadResponse response =
                ragDocumentService.upload(file, documentCode, name, registrant);
        URI location = URI.create("/v1/assistant/documents/" + response.ragDocId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }
}
