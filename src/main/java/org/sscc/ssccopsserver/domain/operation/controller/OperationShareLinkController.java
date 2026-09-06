package org.sscc.ssccopsserver.domain.operation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.OperationShareLinkResponse;
import org.sscc.ssccopsserver.domain.operation.service.OperationShareLinkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 운영 건 공유 링크의 발급·폐기 (ssccops#200 · ADR-0016). 운영자용이라 인증이 필요하다 —
 * 익명이 여는 것은 미리보기(PublicShareController)뿐이다.
 *
 * **경로가 /v1/operations/{operationId}/share인 것은 대상이 oper이기 때문이다.** 업무·하위
 * 업무·회의가 모두 oper를 상속하므로 공유는 종류를 가리지 않고 한 경로로 끝난다 — 종류마다
 * 경로를 두면 같은 규칙이 세 벌이 된다.
 *
 * **권한은 WORK_READ다.** 새 권한 코드를 만들지 않는다: 어휘를 늘리면 역할마다 다시 부여해야
 * 하고, "공유할 수 있지만 볼 수는 없는" 조합이 생긴다. 공유가 내주는 것이 제목뿐이라
 * 조회 권한보다 넓은 것을 요구할 근거도 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/operations/{operationId}/share")
public class OperationShareLinkController {

    private final OperationShareLinkService operationShareLinkService;

    /*
     * 발급. **이미 유효한 링크가 있으면 그것을 그대로 돌려준다**(수용 기준 3) — 그래서 201이
     * 아니라 200이다. 두 경우(새로 만듦·기존 반환)를 상태 코드로 가르지 않는 것은 화면이 할
     * 일이 같기 때문이다(URL을 만들어 복사한다). 가르면 화면에 쓸모없는 분기가 하나 는다.
     */
    @Operation(
            summary = "운영 건 공유 링크 발급",
            description =
                    "이 운영 건의 공유 토큰을 발급한다. **이미 유효한 링크가 있으면 새로 만들지 않고"
                            + " 그것을 돌려준다** — 누를 때마다 쌓이면 무엇을 중지해야 할지 알 수 없다."
                            + " 응답은 토큰이며 완성된 URL이 아니다(공유 페이지 주소는 웹의 것이다)."
                            + " **만료가 없다** — 거두는 길은 공유 중지 하나다(ADR-0016)."
                            + " 토큰이 주는 것은 미리보기 권한까지이며, 링크를 누른 사람은"
                            + " 여전히 로그인과 권한 검사를 거쳐야 상세를 본다."
                            + " 없는 운영 건·삭제된 운영 건은 404 NOT_FOUND다.")
    @PostMapping
    @RequireAuthority(AuthorityCode.WORK_READ)
    public ApiResponse<OperationShareLinkResponse> issue(
            @PathVariable Long operationId, @CurrentMember MemberEntity creator) {
        return ApiResponse.success(operationShareLinkService.issue(operationId, creator));
    }

    /*
     * 공유 중지. **유효한 링크가 없어도 성공(204)이다** — 사용자가 원한 상태가 이미 성립하기
     * 때문이다. 404로 거절하면 '공유 중지'를 두 번 누른 것만으로 오류를 보게 되고, 그 오류로
     * 사용자가 할 수 있는 일이 없다.
     */
    @Operation(
            summary = "운영 건 공유 중지",
            description =
                    "이 운영 건의 유효한 공유 링크를 폐기한다. 그 뒤로 그 토큰은 미리보기도 열지"
                            + " 못한다. **유효한 링크가 없어도 204다** — 원한 상태가 이미 성립한다."
                            + " 행은 지우지 않고 폐기 일시만 남긴다(누가 언제 무엇을 공유했는지가"
                            + " 저장을 택한 이유다)."
                            + " **이미 메신저에 퍼진 카드는 중지해도 남는다** — 메신저 캐시는"
                            + " 우리가 지울 수 없고, 중지가 막는 것은 링크를 눌렀을 때다."
                            + " 없는 운영 건·삭제된 운영 건은 404 NOT_FOUND다.")
    @DeleteMapping
    @RequireAuthority(AuthorityCode.WORK_READ)
    public ResponseEntity<Void> revoke(@PathVariable Long operationId) {
        operationShareLinkService.revoke(operationId);
        return ResponseEntity.noContent().build();
    }
}
