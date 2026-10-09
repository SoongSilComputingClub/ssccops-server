package org.sscc.ssccopsserver.domain.member.controller;

import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterExportCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterFile;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterPreviewResponse;
import org.sscc.ssccopsserver.domain.member.service.MemberRosterExportService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;

/*
 * 회원명부 내려받기 (#674 · 상위 ssccops#598).
 *
 * **클래스 레벨 @RequireAuthority(MEMBER_MANAGE)다** — CSV 회원 이관(MemberImportController)과 같은
 * 권한이고 웹도 같은 메뉴 묶음(설정 › 회원)에 둔다. 명부를 통째로 다루는 두 화면이며, 연락처·학번이
 * 실리므로 응답 CSV의 결정(연락처는 MEMBER_MANAGE 없이는 보이지 않는다 · ssccops#223)과도 맞는다.
 *
 * ── ApiResponse 봉투의 두 번째 예외 ─────────────────────────────
 * 성공 응답은 봉투가 아니라 xlsx 파일 그대로다(첫째 예외는 규정 도우미 SSE · #447). 파일 하나를
 * 봉투에 넣으면 base64가 되어 화면이 다시 풀어야 하고 그 사이 크기가 3분의 4로 는다. **거절은
 * 종전대로 상태 코드 + 봉투다** — 400(조건) · 401 · 403 · 409(회장 없음)는 워크북을 만들기 전에
 * 끊기고, GlobalExceptionHandler가 Content-Type을 JSON으로 박으므로(#656) produces가 xlsx여도
 * 봉투가 나간다. 웹은 2xx가 아니면 파일을 저장하지 않고 메시지를 띄운다.
 *
 * 파일 이름은 서버가 정한다 — Content-Disposition의 filename*(UTF-8)이며 CORS가 그 헤더를
 * 노출한다(SecurityConfig). 화면이 따로 지으면 제목·파일 이름 규칙이 두 벌이 된다.
 *
 * ── 미리보기는 봉투다 ──────────────────────────────────────────
 * GET …/preview는 같은 조건으로 줄 수·빠진 인원·제목·파일 이름을 ApiResponse로 낸다(#676).
 * 파일이 아니므로 예외가 아니다. 회장이 없어도 409가 아니라 presidentMissing으로 알린다.
 */
@RestController
@RequestMapping("/v1/members/roster-export")
@RequireAuthority(AuthorityCode.MEMBER_MANAGE)
@RequiredArgsConstructor
public class MemberRosterController {

    /* 애노테이션 속성이 컴파일 타임 상수를 요구해 문자열로 둔다 */
    static final String XLSX_VALUE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    static final MediaType XLSX = MediaType.parseMediaType(XLSX_VALUE);

    private final MemberRosterExportService memberRosterExportService;

    @Operation(
            summary = "회원명부 내려받기(xlsx)",
            description =
                    "동아리연합회 회원명부 양식을 채운 xlsx를 내려준다. 오늘 기준으로 회장·부회장 역할이"
                            + " 유효한 회원은 상태·등급과 무관하게 항상 들어가고, 그 밖에는 mbrSttsCd로"
                            + " 고른 상태(생략하면 ENROLLED) 중 하나이면서 임시회원(TEMP)이 아닌 회원이"
                            + " 들어간다. 순서는 회장 → 부회장 → 나머지(학번 오름차순). 직책은"
                            + " positionNotation이 FEDERATION(기본)이면 회장·부회장 외 전원 «정회원»,"
                            + " SSCC면 대표 역할 이름(없으면 빈칸)이다. year·semester는 제목과 파일"
                            + " 이름에만 쓴다. 단대는 학과명이 양식의 학과 목록과 정확히 같을 때만 채운다."
                            + " 유효한 회장이 없으면 옵션과 무관하게 409 ROSTER_PRESIDENT_MISSING,"
                            + " mbr_stts에 없는 상태 코드·모르는 positionNotation은 400"
                            + " INVALID_CODE_VALUE, year·semester 누락·범위 밖은 400 VALIDATION_FAILED다."
                            + " **성공 응답은 ApiResponse 봉투가 아니라 파일**이고 거절은 봉투다. 제목의 괄호는"
                            + " 재학만 고르면 «(재학생)», 상태를 넓히면 고른 상태 이름(«(재학·일반휴학)»),"
                            + " 전부 고르면 괄호가 없다. 같은 조건의 미리보기는 GET …/preview다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "회원명부 xlsx. 파일 이름은 Content-Disposition의 filename*(UTF-8)이다.",
            content =
                    @Content(
                            mediaType = XLSX_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
    @GetMapping(produces = XLSX_VALUE)
    public ResponseEntity<Resource> export(
            @Valid @ModelAttribute MemberRosterExportCondition condition) {
        MemberRosterFile file = memberRosterExportService.export(condition);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                // 연락처·학번이 담긴 파일이다 — 브라우저·프록시 어디에도 남기지 않는다
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }

    @Operation(
            summary = "회원명부 미리보기",
            description =
                    "회원명부 내려받기와 같은 조건으로, 명단 기준일(baseDate · 서버의 오늘 — 연도·학기와"
                            + " 무관), 내려받을 파일의 제목(title)·이름(fileName)과 명부에"
                            + " 오를 줄 수(rowCount · 회장·부회장 officerCount 포함), 빠지는 인원을 내린다."
                            + " 빠지는 인원은 임시회원이라서(excludedTemporaryCount · 고른 상태와 무관)와 고르지"
                            + " 않은 상태라서(excludedByStatusCount)로 나뉘고, 셋의 합이 전체 회원"
                            + " 수(totalMemberCount)다. 유효한 회장이 없으면 409가 아니라 presidentMissing ="
                            + " true로 알린다(내려받기는 409다). 조건 검증은 내려받기와 같다 — 모르는 상태"
                            + " 코드·표기법은 400 INVALID_CODE_VALUE, year·semester 누락·범위 밖은 400"
                            + " VALIDATION_FAILED. 회원 값이 실리지 않아 감사를 남기지 않는다.")
    @GetMapping("/preview")
    public ApiResponse<MemberRosterPreviewResponse> preview(
            @Valid @ModelAttribute MemberRosterExportCondition condition) {
        return ApiResponse.success(memberRosterExportService.preview(condition));
    }
}
