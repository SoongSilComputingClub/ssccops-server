package org.sscc.ssccopsserver.domain.share.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 공유 링크 도메인 전용 에러 코드 (ssccops#200).
 *
 * 코드 문자열은 운영 도메인과 같은 영문 UPPER_SNAKE_CASE다.
 */
@Getter
@AllArgsConstructor
public enum ShareErrorCode implements ErrorCode {

    /*
     * 404 — 토큰으로 미리보기를 찾을 수 없다.
     *
     * **없는 토큰·폐기된 토큰·대상이 사라진 토큰이 전부 이 하나다.** 나누면 어느 토큰이 한때
     * 존재했는지가 드러나고, 그것은 토큰을 무작위로 둔 이유(열거를 막는다 — ADR-0016)를
     * 절반 무효로 만든다. 폼 미리보기가 DRAFT와 없는 폼을 같은 404로 답하는 것과 같은 태도다.
     */
    SHARE_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "공유 링크를 찾을 수 없습니다."),

    /*
     * 500 — 대상 종류에 미리보기 제공자가 등록되지 않았다.
     *
     * 발급은 됐는데 읽을 수 없는 상태이므로 요청의 잘못이 아니다. 기동 시점에 막지 못하는 것은
     * 구분 코드가 enum이라 값이 늘어도 컴파일이 통과하기 때문이다 — 제공자를 함께 만들지 않은
     * 것을 여기서 드러낸다.
     */
    SHARE_PREVIEW_PROVIDER_MISSING(
            HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "공유 미리보기를 만들 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
