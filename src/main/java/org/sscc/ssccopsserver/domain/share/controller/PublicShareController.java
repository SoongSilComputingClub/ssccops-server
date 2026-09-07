package org.sscc.ssccopsserver.domain.share.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.share.dto.PublicSharePreviewResponse;
import org.sscc.ssccopsserver.domain.share.service.ShareLinkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 익명 공유 미리보기 API (ssccops#200 · ADR-0016).
 *
 * **`/public/v1` 아래에 있다 — 인증이 없다.** 이 접두사에 핸들러를 더하는 것은 permitAll을
 * 더하는 것과 같으므로(SecurityConfig), 실리는 값을 제목·요약과 대상 좌표로 좁혔다.
 *
 * **경로에 대상 식별자가 없는 것이 요점이다.** `/public/v1/sub-works/{id}/meta` 같은 것을 열면
 * 식별자가 연속 정수라 1부터 훑는 것만으로 동아리 업무 제목이 전부 수집된다 — 토큰은 그
 * 공격면 자체를 없앤다(ADR-0016이 공개 메타 API를 기각한 근거다).
 *
 * 폼 미리보기(`PublicFormMetaController` · ssccops#201)와 갈리는 지점이며, 갈리는 기준은
 * **"이 링크를 몇 사람에게 뿌릴 작정인가"**다. 공개 폼은 널리 뿌리는 것이 목적이라 `formId`
 * 그대로 열고 대신 접수를 연 적 없는 폼을 감춘다. 운영 건은 뿌릴 대상이 정해져 있어 토큰을 쓴다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/v1/share")
public class PublicShareController {

    private final ShareLinkService shareLinkService;

    @Operation(
            summary = "공유 링크 미리보기 조회(익명)",
            description =
                    "메신저 크롤러가 공유 링크의 카드를 만들 때 쓴다. **인증이 필요 없다** — 토큰이"
                            + " 미리보기 권한 그 자체다(ADR-0016). 실리는 것은 제목(title)·요약(summary,"
                            + " 없으면 null)과 대상 좌표(trgtSeCd·trgtId)뿐이며 **상태·진행률·마감일은 담지"
                            + " 않는다** — 메신저가 카드를 한 번 캐싱하면 갱신하지 않아 시간에 따라 변하는 값을"
                            + " 담으면 마감된 뒤에도 진행 중이라 말하는 카드가 남는다. 대상 좌표를 알아도 내용은"
                            + " 볼 수 없다(상세는 종전대로 로그인과 권한 검사를 지난다). **없는 토큰·폐기된"
                            + " 토큰·대상이 사라진 토큰은 전부 404 NOT_FOUND**이며, 나누면 어느 토큰이 한때"
                            + " 존재했는지가 드러난다.")
    @GetMapping("/{token}")
    public ApiResponse<PublicSharePreviewResponse> getSharePreview(@PathVariable String token) {
        return ApiResponse.success(shareLinkService.preview(token));
    }
}
