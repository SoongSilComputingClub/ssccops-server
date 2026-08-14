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

    /*
     * 400 — 문항 구성(qitem_cpst_cn)이 스스로 모순될 때. 존재하지 않는 페이지로 분기하거나,
     * 컴파일되지 않는 정규식이거나, 선택지 없는 선택형 문항이면 여기에 걸린다.
     *
     * 형식 오류(VALIDATION_FAILED)와 코드를 나눈 것은 프론트가 할 일이 다르기 때문이다 —
     * 전자는 입력란을 붉게 칠하면 되지만, 이쪽은 폼 편집기의 어느 문항이 잘못됐는지
     * 찾아 보여줘야 한다. Bean Validation으로는 표현할 수 없는 문항 간 상호 규칙이다.
     */
    INVALID_QUESTION_COMPOSITION(
            HttpStatus.BAD_REQUEST, "INVALID_QUESTION_COMPOSITION", "문항 구성이 올바르지 않습니다."),

    /*
     * 400 — 접수 종료가 시작보다 빠를 때. DTO의 @AssertTrue로 잡으면 전역 핸들러가
     * VALIDATION_FAILED로 바꿔 버려 계약표의 코드와 어긋나므로 서비스가 직접 던진다.
     */
    INVALID_RECEIPT_PERIOD(
            HttpStatus.BAD_REQUEST, "INVALID_RECEIPT_PERIOD", "접수 종료 일시는 시작 일시보다 빠를 수 없습니다."),

    /*
     * 400 — 전이표에 없는 상태 전이 (#33). 이미 열린 폼을 또 열거나(OPEN → OPEN), 작성 중인
     * 폼을 마감하려는(DRAFT → CLOSE) 요청이 여기에 걸린다.
     *
     * 409가 아니라 400인 것은 이슈 #33의 계약표를 따른 것이다. 상태 충돌이라는 점에서는 409에
     * 가깝지만, 웹은 현재 상태를 이미 화면에 들고 있어 보낼 수 있는 액션이 하나로 정해진다 —
     * 즉 이 오류는 "지금 할 수 없는 일"보다 "보내면 안 되는 요청"에 가깝다.
     */
    INVALID_FORM_STATUS_TRANSITION(
            HttpStatus.BAD_REQUEST, "INVALID_FORM_STATUS_TRANSITION", "허용되지 않는 폼 상태 전이입니다."),

    /*
     * 400 — 문항이 하나도 없는 폼을 접수 시작하려 할 때 (#33).
     *
     * 문항 0개는 저장 시점에는 정상이다 — 편집을 막 시작한 DRAFT가 그 상태다
     * (QuestionCompositionValidator). 하지만 그대로 열면 응답자가 아무것도 입력할 수 없는
     * 공개 링크가 나가고, 빈 응답이 접수된 뒤에는 문항을 추가해도 이미 낸 응답이 비어 있다.
     */
    FORM_HAS_NO_QUESTION(
            HttpStatus.BAD_REQUEST, "FORM_HAS_NO_QUESTION", "문항이 없는 폼은 접수를 시작할 수 없습니다."),

    // 404 — 폼 자체를 찾을 수 없을 때. 공개 링크로 접근한 미공개(DRAFT) 폼도 여기에 걸린다
    FORM_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "폼을 찾을 수 없습니다."),

    /*
     * 409 — 이미 응답이 있는 폼에서 기존 qitemId를 지우거나 이름을 바꾸려 할 때.
     *
     * rspns_cn의 key가 qitemId라, 문항 식별자가 끊기는 순간 과거 응답이 어느 문항의 답인지
     * 알 수 없게 된다. 되돌릴 수 없는 손실이라 400(요청이 틀렸다)이 아니라 409(지금 상태에서
     * 할 수 없다)로 내린다 — 문항을 새로 추가하는 것은 계속 허용된다.
     */
    QUESTION_ITEM_IN_USE(
            HttpStatus.CONFLICT, "QUESTION_ITEM_IN_USE", "이미 응답이 있는 폼에서는 기존 문항을 삭제하거나 변경할 수 없습니다."),

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
            HttpStatus.UNPROCESSABLE_ENTITY, "RESPONSE_CONTENT_MALFORMED", "폼 응답 내용을 읽을 수 없습니다."),

    // 404 — 존재하지 않는 라벨. 비활성 라벨은 여기에 걸리지 않는다 (지워지지 않고 살아 있다)
    FORM_LABEL_NOT_FOUND(HttpStatus.NOT_FOUND, "FORM_LABEL_NOT_FOUND", "폼 라벨을 찾을 수 없습니다."),

    /*
     * 409 — 이미 같은 이름의 라벨이 있을 때.
     *
     * 라벨 이름은 화면에서 사람이 읽고 고르는 유일한 단서라 같은 이름이 둘이면 어느 쪽을 골랐는지
     * 알 수 없다. 선조회로 대부분 걸리지만 동시 생성은 uk_form_lbl_name 위반으로만 드러나므로
     * 그 경로에서도 같은 코드로 내린다 (#21 학번 중복과 같은 방식).
     */
    FORM_LABEL_NAME_DUPLICATED(
            HttpStatus.CONFLICT, "FORM_LABEL_NAME_DUPLICATED", "이미 등록된 라벨 이름입니다."),

    /*
     * 400 — 비활성(use_yn = false) 라벨을 폼에 새로 지정하려 할 때.
     *
     * 이미 지정된 라벨이 비활성으로 바뀐 경우는 여기 걸리지 않는다 — 비활성은 "새로 달 수 없다"는
     * 뜻이지 "달려 있던 것을 떼라"는 뜻이 아니기 때문이다. 그래서 지정 교체는 새로 추가되는
     * 라벨만 이 규칙으로 검사한다.
     */
    FORM_LABEL_NOT_USABLE(
            HttpStatus.BAD_REQUEST, "FORM_LABEL_NOT_USABLE", "비활성 라벨은 새로 지정할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
