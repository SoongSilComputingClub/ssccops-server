package org.sscc.ssccopsserver.domain.form.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormMetaResponse;
import org.sscc.ssccopsserver.domain.form.service.PublicFormMetaService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

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
                            + " 폼이 있는지 없는지가 드러나지 않는다.")
    @GetMapping("/{formId}/meta")
    public ApiResponse<PublicFormMetaResponse> getFormMeta(@PathVariable Long formId) {
        return ApiResponse.success(publicFormMetaService.getFormMeta(formId));
    }
}
