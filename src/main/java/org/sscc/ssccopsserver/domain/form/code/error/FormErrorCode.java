package org.sscc.ssccopsserver.domain.form.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 폼 도메인 전용 에러 코드.
 *
 * 코드 문자열을 영문 UPPER_SNAKE_CASE로 두는 것은 MemberErrorCode·OperationErrorCode와
 * 같은 이유다 — 개발지침서 EX-10(숫자 코드 금지)을 따르고 프론트가 코드 문자열로 분기한다.
 */
@Getter
@AllArgsConstructor
public enum FormErrorCode implements ErrorCode {

    // 404 — 폼 자체를 찾을 수 없을 때. 공개 링크로 접근한 미공개(DRAFT) 폼도 여기에 걸린다
    FORM_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "폼을 찾을 수 없습니다."),

    /*
     * 422 — 저장된 문항 구성(qitem_cpst_cn) JSON을 읽을 수 없을 때.
     *
     * JSONB는 DB가 문법만 보장할 뿐 우리 구조까지 보장하지 않는다. 기준 코드 밖의
     * qitemTypeCd가 섞이거나 스키마가 어긋나면 역직렬화가 깨지는데, 그대로 두면 Jackson
     * 예외가 그대로 올라가 500이 된다. 500은 "서버가 고장났다"는 뜻이라 프론트가 할 수 있는
     * 일이 없지만, 실제로는 이 폼 한 건의 데이터가 잘못된 것이므로 도메인 오류로 내린다.
     * 변환 지점은 JsonFormatMapperConfig다.
     */
    FORM_CONTENT_MALFORMED(
            HttpStatus.UNPROCESSABLE_ENTITY, "FORM_CONTENT_MALFORMED", "폼 문항 구성을 읽을 수 없습니다."),

    // 422 — 저장된 응답 내용(rspns_cn) JSON을 읽을 수 없을 때. 위와 같은 이유
    RESPONSE_CONTENT_MALFORMED(
            HttpStatus.UNPROCESSABLE_ENTITY, "RESPONSE_CONTENT_MALFORMED", "폼 응답 내용을 읽을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
