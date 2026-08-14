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

    // 404 — 폼 자체를 찾을 수 없을 때. 미공개(DRAFT) 폼은 존재하므로 여기가 아니라 FORM_NOT_ACCEPTING이다
    FORM_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "폼을 찾을 수 없습니다."),

    /*
     * 409 — 지금 응답을 받을 수 없는 폼일 때 (#35). DRAFT·CLOSED와 접수 기간 밖(SCHEDULED·EXPIRED)이
     * 전부 여기에 걸린다. 판정은 FormReceiptPolicy 하나가 하며 이 코드를 쓰는 쪽이 다시 따지지 않는다.
     *
     * 네 경우를 한 코드로 묶은 것은 응답자가 할 수 있는 일이 전부 같기 때문이다 — 어느 쪽이든
     * 지금은 답을 낼 수 없고, 언제 열리는지는 응답 본문이 아니라 폼 상세(운영자용)가 갖는다.
     * 반대로 코드를 넷으로 쪼개면 DRAFT 폼의 존재 여부보다 더 많은 것(아직 열지 않은 폼인지,
     * 이미 닫은 폼인지)이 링크만 가진 사람에게 새어 나간다.
     *
     * 400이 아니라 409인 것은 요청 자체는 올바르고 폼의 현재 상태가 거절 이유이기 때문이다.
     */
    FORM_NOT_ACCEPTING(HttpStatus.CONFLICT, "FORM_NOT_ACCEPTING", "지금은 응답을 받지 않는 폼입니다."),

    /*
     * 400 — 폼에 없는 qitemId가 응답에 섞여 있을 때 (#35).
     *
     * 조용히 버리지 않는 것은 이것이 "낡은 화면에서 제출됐다"는 신호이기 때문이다. 문항이 바뀐
     * 뒤 열어 둔 탭에서 제출하면 응답자는 성공했다고 믿지만 실제로 저장된 답은 화면에서 본 것과
     * 다르다. 버리면 그 어긋남이 접수 마감 후 집계에서야 드러난다.
     */
    UNKNOWN_QUESTION_ITEM(
            HttpStatus.BAD_REQUEST, "UNKNOWN_QUESTION_ITEM", "폼에 없는 문항에 대한 응답이 포함되어 있습니다."),

    // 400 — 도달한 페이지의 필수 문항에 답이 없을 때 (#35). 분기로 건너뛴 페이지는 검사 대상이 아니다
    REQUIRED_ANSWER_MISSING(
            HttpStatus.BAD_REQUEST, "REQUIRED_ANSWER_MISSING", "필수 문항에 대한 응답이 없습니다."),

    // 400 — 문항의 입력 형식(ptrnCn)과 답이 맞지 않을 때 (#35)
    ANSWER_PATTERN_MISMATCH(
            HttpStatus.BAD_REQUEST, "ANSWER_PATTERN_MISMATCH", "문항의 입력 형식과 맞지 않는 응답입니다."),

    // 400 — 다중선택 문항의 최대 선택 수(maxSlctCnt)를 넘겼을 때 (#35)
    ANSWER_SELECTION_LIMIT_EXCEEDED(
            HttpStatus.BAD_REQUEST, "ANSWER_SELECTION_LIMIT_EXCEEDED", "선택할 수 있는 개수를 초과했습니다."),

    /*
     * 400 — 답의 모양이 문항 유형과 맞지 않을 때 (#35). 이슈 초안의 계약표에는 없지만, 없으면
     * 서버가 최종 방어선이라는 전제가 깨져서 넣는다.
     *
     * rspns_cn은 JSONB라 값에 무엇이든 들어갈 수 있다. 다중선택이 아닌데 배열이 오거나, 답 자리에
     * 객체·숫자가 오거나, 선택지 목록에 없는 값이 오면 저장은 되지만 응답 조회(#37)와 집계가
     * 그 행 하나 때문에 깨진다. 형식(정규식) 불일치와 코드를 나눈 것은 프론트가 할 일이 다르기
     * 때문이다 — 이쪽은 입력란 문제가 아니라 화면과 폼이 어긋났다는 뜻이다.
     */
    INVALID_ANSWER_VALUE(HttpStatus.BAD_REQUEST, "INVALID_ANSWER_VALUE", "문항 유형과 맞지 않는 응답 값입니다."),

    /*
     * 409 — 이미 제출한 폼에 다시 제출하려 할 때 (#35).
     *
     * 한 회원은 한 폼에 1건만 낸다. 선조회로 대부분 걸리지만 같은 사람이 두 탭에서 동시에 누르면
     * 둘 다 선조회를 통과하므로 (form_id, mbr_id) UNIQUE 위반도 같은 코드로 옮긴다
     * (#21 학번 중복·#34 라벨 이름 중복과 같은 방식).
     *
     * 재제출·수정 제출은 이번 범위 밖이라 400(요청이 틀렸다)이 아니라 409(지금 상태에서 할 수 없다)다.
     */
    RESPONSE_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "RESPONSE_ALREADY_SUBMITTED", "이미 제출한 폼입니다."),

    /*
     * 409 — 같은 응답 행을 두 요청이 동시에 만들려다 (form_id, mbr_id) UNIQUE에 걸렸을 때 (#36).
     *
     * 자동 저장은 응답자가 타이핑하는 동안 계속 날아오므로, 두 탭이 열려 있거나 디바운스가 겹치면
     * 첫 저장 두 건이 동시에 도착할 수 있다. 둘 다 선조회에서 "행이 없다"를 보고 INSERT를 시도하면
     * 하나는 반드시 제약에 걸린다. 그대로 두면 500이라 웹은 서버가 고장난 것으로 읽지만, 실제로는
     * 잠깐 뒤 다시 보내면 되는 상황이다.
     *
     * RESPONSE_ALREADY_SUBMITTED로 묶지 않는 것은 뜻이 다르기 때문이다 — 경합한 상대는
     * 제출이 아니라 같은 사람의 다른 임시저장일 수 있고, 그 경우 "이미 제출했다"는 거짓이다.
     */
    RESPONSE_SAVE_CONFLICT(
            HttpStatus.CONFLICT, "RESPONSE_SAVE_CONFLICT", "응답이 동시에 저장되어 처리하지 못했습니다. 다시 시도해주세요."),

    /*
     * 413 — 응답 내용(rspns_cn)이 상한을 넘겼을 때 (#36).
     *
     * 자동 저장은 응답자가 글자를 칠 때마다 같은 본문을 통째로 다시 보낸다. 상한이 없으면 붙여넣기
     * 한 번으로 수 MB짜리 JSONB가 매 요청마다 오가고, 그 행은 폼 목록·응답 조회가 읽을 때마다
     * 따라온다. 문항 수는 폼이 정하므로(폼에 없는 qitemId는 거절된다) 남는 축은 답 하나하나의
     * 길이뿐이라, 답 전체의 글자 수 합으로 끊는다.
     *
     * 제출(#35)에도 같은 상한을 건다. 자동 저장만 막으면 상한을 넘긴 내용이 제출로는 들어와
     * 결국 같은 크기의 행이 남고, 반대로 제출만 막으면 자동 저장으로 저장해 둔 응답자가 무엇을
     * 지워야 낼 수 있는지 알 수 없는 채로 거절당한다.
     */
    RESPONSE_CONTENT_TOO_LARGE(
            HttpStatus.PAYLOAD_TOO_LARGE, "RESPONSE_CONTENT_TOO_LARGE", "응답 내용이 너무 큽니다."),

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

    /*
     * 404 — 존재하지 않는 응답 (#37).
     *
     * **다른 폼의 응답 식별자도 같은 코드로 내린다.** 경로에 formId와 formRspnsId가 둘 다 있는데
     * 응답 식별자만 보고 조회하면 폼 간 데이터가 그대로 새어 나가므로, 조회는 반드시 두 값을 함께
     * 건다. 그 결과 "없는 응답"과 "남의 폼 응답"이 한 코드로 합쳐지는데, 이것은 의도된 것이다 —
     * 코드를 나누면 그 폼에 그 번호의 응답이 있는지 없는지가 권한 없는 운영자에게 새어 나간다.
     */
    FORM_RESPONSE_NOT_FOUND(HttpStatus.NOT_FOUND, "FORM_RESPONSE_NOT_FOUND", "폼 응답을 찾을 수 없습니다."),

    /*
     * 400 — 허용되지 않는 응답 상태 전이 (#37).
     *
     * SUBMITTED ↔ ACCEPTED ↔ REJECTED는 자유롭게 오간다(심사 번복). 여기에 걸리는 것은 DRAFT가
     * 얽힌 전이뿐이다 — 작성 중인 응답을 운영자가 승인하면 응답자가 아직 쓰고 있던 내용이 그대로
     * 심사 결과로 굳고, 반대로 제출된 응답을 DRAFT로 되돌리면 sbmsn_dt가 남아 있는 '미제출'
     * 응답이 생겨 데이터가 스스로 모순된다. DRAFT → SUBMITTED는 오직 응답자의 제출로만 일어난다.
     *
     * 400인 것은 폼 상태 전이(INVALID_FORM_STATUS_TRANSITION)와 같은 이유다 — 웹은 현재 상태를
     * 이미 화면에 들고 있어 보낼 수 있는 값이 정해지므로 "지금 할 수 없는 일"보다 "보내면 안 되는
     * 요청"에 가깝다.
     */
    INVALID_RESPONSE_STATUS_TRANSITION(
            HttpStatus.BAD_REQUEST, "INVALID_RESPONSE_STATUS_TRANSITION", "허용되지 않는 응답 상태 전이입니다."),

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
