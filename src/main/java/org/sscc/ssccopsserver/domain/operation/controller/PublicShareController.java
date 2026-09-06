package org.sscc.ssccopsserver.domain.operation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.operation.dto.PublicSharePreviewResponse;
import org.sscc.ssccopsserver.domain.operation.service.OperationShareLinkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 공유 링크 미리보기 (ssccops#200 · ADR-0016). **익명 층에 더해지는 두 번째 업무 API다.**
 *
 * 이 접두사(/public/v1) 아래에 핸들러를 더하는 것은 permitAll을 더하는 것과 같으므로
 * (서버 AGENTS.md), 여기서 지키는 선은 둘이다.
 *
 * **① 받는 것은 토큰 하나뿐이다.** 식별자로 여는 경로를 만들지 않는다 — 그것이
 * /public/v1/sub-works/{id}/meta를 기각한 이유다. oper_id는 연속 정수라 1부터 훑는 것만으로
 * 동아리 업무 제목이 전부 수집되지만, 토큰은 256비트 난수라 그 공격면 자체가 없다.
 *
 * **② 내주는 것은 제목과 종류뿐이다.** 담당자·상태·진행률·마감일은 싣지 않는다 — 앞의 둘은
 * 익명에게 나갈 이유가 없고, 뒤의 둘은 메신저가 카드를 캐싱해 **한 번 굳기** 때문이다
 * (PublicSharePreviewResponse 주석).
 *
 * @RequireAuthority도 @CurrentMember도 없다 — 요청 주체가 없는 것이 정상이므로 주체를 받는
 * 자리를 만들지 않는다 (PublicEventController와 같은 모양).
 *
 * 남용 방어(rate limit)는 이 층이 아니라 Cloudflare Workers 레벨의 결정을 따른다
 * (PublicEventController 주석과 같은 근거 — 주체가 없어 IP당이 되고 그 값은 인스턴스마다
 * 다르게 세어진다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/v1/share")
public class PublicShareController {

    private final OperationShareLinkService operationShareLinkService;

    @Operation(
            summary = "공유 링크 미리보기(익명)",
            description =
                    "공유 토큰으로 운영 건의 제목과 종류만 조회한다. **인증이 필요 없다** —"
                            + " 메신저 크롤러가 OG 카드를 만들기 위해 여는 자리다."
                            + " 상태·진행률·마감일은 담지 않는다: 메신저는 카드를 캐싱하고"
                            + " 갱신하지 않아 한 번 굳으면 그 값이 남는다."
                            + " 없는 토큰·공유가 중지된 토큰·삭제된 운영 건은 **모두 같은**"
                            + " 404 NOT_FOUND다 — 나누면 그 차이가 곧 정보가 된다."
                            + " 토큰이 주는 것은 이 미리보기까지이며 상세 열람 권한이 아니다.")
    @GetMapping("/{token}")
    public ApiResponse<PublicSharePreviewResponse> preview(@PathVariable String token) {
        return ApiResponse.success(operationShareLinkService.preview(token));
    }
}
