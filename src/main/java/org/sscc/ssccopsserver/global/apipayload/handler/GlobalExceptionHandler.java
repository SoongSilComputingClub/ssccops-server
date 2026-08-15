package org.sscc.ssccopsserver.global.apipayload.handler;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice(annotations = {RestController.class})
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<Object> handleGeneralException(GeneralException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return handleExceptionInternal(errorCode);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException: {}", ex.getMessage());
        ErrorCode errorCode = CommonErrorCode.INVALID_PARAMETER;
        return handleExceptionInternal(errorCode, ex.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        log.warn("HttpRequestMethodNotSupportedException: {}", ex.getMessage());
        ErrorCode errorCode = CommonErrorCode.METHOD_NOT_ALLOWED;
        return handleExceptionInternal(errorCode);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        log.warn("MethodArgumentNotValidException");
        ErrorCode errorCode = CommonErrorCode.VALIDATION_FAILED;
        return handleExceptionInternal(errorCode, getDefaultMessage(ex));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // Jackson이 DTO로 변환하다 실패(예: enum/숫자 타입 변환 실패)한 경우 상세 메시지 제공
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String fieldName = ife.getPath().get(0).getFieldName();
            String value = String.valueOf(ife.getValue());
            String targetType =
                    ife.getTargetType() != null ? ife.getTargetType().getSimpleName() : "Unknown";

            String message =
                    String.format(
                            "'%s'는 %s 필드에 유효하지 않은 값입니다. (%s 타입)", value, fieldName, targetType);
            // 대상이 enum이면 기준 코드 위반이다. 단순 타입 변환 실패와 구분해야
            // 프론트가 "허용값 목록을 다시 확인" 과 "형식 오류" 를 다르게 안내할 수 있다.
            ErrorCode errorCode =
                    isEnumTarget(ife)
                            ? CommonErrorCode.INVALID_CODE_VALUE
                            : CommonErrorCode.INVALID_BODY;
            return handleExceptionInternal(errorCode, message);
        }

        ErrorCode errorCode = CommonErrorCode.INVALID_BODY;
        return handleExceptionInternal(errorCode, CommonErrorCode.INVALID_BODY.getMessage());
    }

    /*
     * 서블릿 계층의 업로드 상한(spring.servlet.multipart)을 넘긴 요청 (#84).
     *
     * @ExceptionHandler를 새로 달지 않고 **재정의**한다 — ResponseEntityExceptionHandler가 이미
     * 이 예외를 맡고 있어 따로 달면 "Ambiguous @ExceptionHandler"로 컨텍스트가 아예 뜨지 않는다.
     *
     * 상위 구현은 ProblemDetail을 내리는데 이 서비스의 모든 응답은 ApiResponse 봉투를 쓰므로
     * 여기 하나만 모양이 달라진다. 상태를 413이 아니라 400으로 맞추는 것은 업무 상한(이관 CSV
     * 5MB 등)을 각 도메인이 400으로 끊기 때문이다 — 같은 "파일이 너무 크다"가 상한을 얼마나
     * 넘겼는지에 따라 다른 상태로 나가면 화면이 두 갈래로 안내해야 한다.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        log.warn("MaxUploadSizeExceededException: {}", ex.getMessage());
        return handleExceptionInternal(CommonErrorCode.BAD_REQUEST, "업로드 가능한 크기를 초과했습니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAll(Exception ex) {
        log.error("Unhandled Exception", ex);
        ErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
        return handleExceptionInternal(errorCode);
    }

    private static boolean isEnumTarget(InvalidFormatException ife) {
        return ife.getTargetType() != null && ife.getTargetType().isEnum();
    }

    private static String getDefaultMessage(MethodArgumentNotValidException ex) {
        StringBuilder message = new StringBuilder();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            message.append(error.getDefaultMessage()).append(" ");
        }
        return message.toString().trim();
    }

    private ResponseEntity<Object> handleExceptionInternal(final ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.fail(errorCode));
    }

    private ResponseEntity<Object> handleExceptionInternal(
            final ErrorCode errorCode, final String message) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode, message));
    }
}
