package org.sscc.ssccopsserver.global.apipayload.exception;

import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.Getter;

/*
 * 서비스 레이어의 표준 예외 — ErrorCode 하나를 감싼다.
 *
 * ── detail은 왜 생겼나 (#150) ─────────────────────────────────
 * 기획안 승인 이관이 실패하면 화면은 "무엇이 잘못됐는가"를 보여줘야 한다 — 커리큘럼 3번째 줄의
 * 형식이 틀렸는지, 유형 문자열이 기준정보에 없는지에 따라 검토자가 할 일이 다르기 때문이다.
 * ErrorCode의 message는 enum 상수라 그 자리에 값을 실을 수 없다.
 *
 * 후보였던 "실패 사유마다 에러 코드를 하나씩 만든다"는 쓰지 않았다. 사유는 몇 번째 줄인지까지
 * 담아야 쓸모가 있는데 그것은 코드가 아니라 값이고, 코드를 늘리면 화면은 그 전부에 대해 같은
 * 안내("기획안을 고쳐 다시 받으세요")를 반복해야 한다.
 *
 * 값이 없으면 종전 그대로 ErrorCode의 message가 나간다 — 기존 호출부(대부분)는 한 인자
 * 생성자를 그대로 쓰므로 응답이 달라지지 않는다. 실어 보낼 때는 **사용자에게 보여도 되는
 * 문장만** 담을 것: 예외 메시지·스택을 그대로 넣으면 내부 구조가 응답으로 새어 나간다.
 */
@Getter
public class GeneralException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 화면에 보여줄 구체적인 사유. 없으면 null이고 그때는 ErrorCode의 message가 나간다 */
    private final String detail;

    public GeneralException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public GeneralException(ErrorCode errorCode, String detail) {
        super(detail == null ? errorCode.getMessage() : detail);
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
