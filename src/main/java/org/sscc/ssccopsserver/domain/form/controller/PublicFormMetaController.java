package org.sscc.ssccopsserver.domain.form.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormMetaResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicOpenFormResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicSystemFormMetaResponse;
import org.sscc.ssccopsserver.domain.form.service.PublicFormMetaService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.apipayload.PublicCacheControl;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 익명 폼 메타 API (ssccops#201). 공개 폼 링크(/f/{formId})를 카카오톡·슬랙에 붙였을 때
 * 크롤러가 카드(OG)를 만들 수 있게 제목·안내 문구만 내준다.
 *
 * **`/public/v1` 아래에 있다 — 인증이 없다.** 이 접두사에 핸들러를 더하는 것은 permitAll을
 * 더하는 것과 같으므로(SecurityConfig), 여기 실리는 값은 익명에게 내줘도 되는 것으로 좁혀야
 * 한다. 그래서 응답은 제목과 첫 페이지 안내 문구뿐이고, 문항·접수 기간·접수 상태는 싣지 않는다
 * (PublicFormMetaResponse 주석). 문항이 필요한 응답자용 조회(PublicFormController ·
 * GET /v1/forms/{formId}/public)는 종전대로 인증이 필요하다 — 응답자는 전원 회원이다.
 *
 * PublicFormController와 클래스를 나눈 것은 경로 접두사가 다르기 때문이다. 그쪽은 /v1이고
 * 여기는 /public/v1이라, 한 클래스에 두면 익명 핸들러와 인증 핸들러가 같은 @RequestMapping을
 * 나눌 수 없고, 무엇이 익명에게 열려 있는지를 /public/v1 아래 클래스 목록만 보고 알 수 없게
 * 된다.
 *
 * 폼은 토큰을 쓰지 않는다(ssccops#201 · 운영 건 공유 링크 #200과 갈리는 지점). 공개 폼은
 * 애초에 링크를 널리 뿌리는 것이 목적이라 formId 그대로 연다 — 대신 접수를 연 적 없는 폼은
 * 존재를 감춘다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/v1/forms")
public class PublicFormMetaController {

    private final PublicFormMetaService publicFormMetaService;

    @Operation(
            summary = "공개 폼 메타 조회(익명 · 미리보기용)",
            description =
                    "메신저 크롤러가 공개 폼 링크의 카드를 만들 때 쓴다. **인증이 필요 없다.**"
                            + " 접수를 연 적 있는 폼(OPEN·CLOSED)의 제목(formTtlNm)과 첫 페이지 안내 문구"
                            + "(pageDescCn, 없으면 null)만 내려준다. 접수 기간·접수 상태·문항은 싣지 않는다 —"
                            + " 메신저가 카드를 한 번 캐싱하면 갱신하지 않으므로 시간에 따라 변하는 값을 담지"
                            + " 않는다. 작성 중(DRAFT)인 폼과 없는 폼은 **둘 다 404 NOT_FOUND**다 — 그 번호의"
                            + " 폼이 있는지 없는지가 드러나지 않는다."
                            + " **경로 변수는 폼 키(UUID)와 예전 숫자 id를 둘 다 받는다**(ADR-0036). 키면"
                            + " 연 적 있는 폼이 전부 열리고, 숫자면 **지금 접수 중(OPEN)인 폼만** 열린다 —"
                            + " 숫자를 훑어도 링크가 돌고 있는 폼의 제목만 얻는다. 새 링크는 키로 만든다.")
    @GetMapping("/{formId}/meta")
    public ApiResponse<PublicFormMetaResponse> getFormMeta(@PathVariable String formId) {
        return ApiResponse.success(publicFormMetaService.getFormMeta(formId));
    }

    /*
     * 접수 중인 폼 목록 (ssccops#381 · ADR-0038). 익명 콘텐츠 셋(페이지·포스트·이것) 중 폼 도메인에
     * 있는 하나다 — «접수 중»의 어휘(FormReceiptPolicy)가 여기 있어서다. /forms/open은 /{formId}/meta와
     * 세그먼트 수가 달라 충돌하지 않는다. Cache-Control은 콘텐츠와 같은 값(PublicCacheControl)이다.
     */
    @Operation(
            summary = "접수 중인 폼 목록(익명)",
            description =
                    "지금 응답을 받을 수 있는 폼(OPEN이고 접수 기간 안)의 폼 키·제목·마감(rcptEndDt, 없으면"
                            + " null)만 내려준다. **인증이 필요 없다.** 마감이 가까운 것부터이며 마감 없는 것은"
                            + " 뒤에 온다. 시스템 폼(기획안)은 부원 전용이라 뺀다. 새 링크는 /f/{formKey}다."
                            + " Cache-Control: public, s-maxage=300, stale-while-revalidate=600.")
    @GetMapping("/open")
    public ResponseEntity<ApiResponse<List<PublicOpenFormResponse>>> getOpenForms() {
        return ResponseEntity.ok()
                .cacheControl(PublicCacheControl.anonymousContent())
                .body(ApiResponse.success(publicFormMetaService.getOpenForms()));
    }

    /*
     * 지정 시스템 폼 메타 (#520 · ssccops#436 · ADR-0044). www의 모집 페이지(/join)가 «지원하기»
     * CTA를 그리는 재료 — 신입회원 모집 폼(RECRUIT)의 키·제목·접수 상태·기간이다.
     *
     * /forms/open이 시스템 폼을 빼는 규칙(ADR-0038)의 예외가 아니라 그 옆의 다른 문이다: 저쪽은
     * «지금 지원할 수 있는 것» 목록이고 이쪽은 «모집 페이지가 가리키는 폼 하나»다. RECRUIT 폼은
     * 여전히 /forms/open에 뜨지 않는다 — 신입회원 모집은 /join이 그리는 것이지 목록 카드가 아니다.
     *
     * 경로 /system/{sysFormCd}/meta는 /{formId}/meta와 세그먼트 수가 달라 충돌하지 않는다.
     * receiptStatus를 싣는 것은 /{formId}/meta와 갈리는 지점이다(PublicSystemFormMetaResponse 주석).
     */
    @Operation(
            summary = "지정 시스템 폼 메타 조회(익명)",
            description =
                    "신입회원 모집 지정 폼(sysFormCd = RECRUIT)의 폼 키·제목·접수 상태(receiptStatus)·접수 기간을 내려준다."
                        + " **인증이 필요 없다.** www의 /join이 이 값으로 «지원하기» 링크(/f/{formKey})와 모집 기간 안내를"
                        + " 그린다. **RECRUIT 말고는 열리지 않는다** — 다른 코드(기획안 PROPOSAL 포함)·아직 지정된 폼이 없음·지정된"
                        + " 폼이 아직 작성 중(DRAFT)은 전부 404 NOT_FOUND다. 마감된 폼(CLOSED·EXPIRED)은 200이고"
                        + " receiptStatus가 그것을 말한다. /forms/{formId}/meta와 달리 접수 상태·기간을 싣는 것은 이것이 OG"
                        + " 카드가 아니라 페이지 재료라 CDN 캐시 뒤 갱신되기 때문이다. Cache-Control: public,"
                        + " s-maxage=300, stale-while-revalidate=600. ADR-0044.")
    @GetMapping("/system/{sysFormCd}/meta")
    public ResponseEntity<ApiResponse<PublicSystemFormMetaResponse>> getSystemFormMeta(
            @PathVariable String sysFormCd) {
        return ResponseEntity.ok()
                .cacheControl(PublicCacheControl.anonymousContent())
                .body(ApiResponse.success(publicFormMetaService.getSystemFormMeta(sysFormCd)));
    }
}
