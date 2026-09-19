package org.sscc.ssccopsserver.domain.content.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 콘텐츠 도메인 전용 에러 코드 (ssccops#381). 코드 문자열은 웹과의 계약이라 임의로 바꾸지
 * 않는다 — EventErrorCode·FormErrorCode와 같은 이유다.
 */
@Getter
@AllArgsConstructor
public enum ContentErrorCode implements ErrorCode {

    /*
     * 404 — 없는 페이지. 익명 경로에서는 **초안도 이 코드**다 — 초안이 있다는 사실 자체가
     * 익명에게 나가면 안 되므로(ADR-0038 «절대 실리지 않는 것») 없는 것과 같은 답을 한다.
     * 어드민 경로에서는 id로 찾으므로 초안도 200이다.
     */
    PAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "페이지를 찾을 수 없습니다."),

    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "포스트를 찾을 수 없습니다."),

    /*
     * 404 — 갤러리에 없는 파일. 남의 포스트의 파일 id도 여기다 — 대상 조건을 함께 걸어 조회하므로
     * 코드를 나누면 그 번호의 파일이 어느 포스트에 있는지가 새어 나간다.
     */
    CONTENT_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CONTENT_IMAGE_NOT_FOUND", "갤러리 이미지를 찾을 수 없습니다."),

    /*
     * 409 — 같은 slug가 이미 있을 때. 선조회에 더해 동시 생성은 UNIQUE(uk_cntnt_page_slug ·
     * uk_cntnt_post_slug) 위반으로만 드러나므로 그 경로에서도 같은 코드로 옮긴다.
     * 페이지와 포스트는 slug 공간이 다르다(테이블이 다르다) — 같은 slug가 양쪽에 있어도 충돌이
     * 아니다(공개 주소가 /pages/·/posts/로 갈린다).
     */
    CONTENT_SLUG_DUPLICATED(HttpStatus.CONFLICT, "CONTENT_SLUG_DUPLICATED", "이미 사용 중인 slug입니다."),

    /*
     * 409 — 이미 게시된 것을 다시 게시하거나, 초안을 게시 취소할 때. 400이 아니라 409인 것은
     * 요청 형식이 아니라 대상의 현재 상태가 문제이고, 두 운영자가 같은 버튼을 동시에 누른
     * 경우가 실제 발생 경로이기 때문이다(그때 두 번째 사람은 «이미 됐다»를 알아야 한다).
     */
    CONTENT_ALREADY_PUBLISHED(HttpStatus.CONFLICT, "CONTENT_ALREADY_PUBLISHED", "이미 게시된 콘텐츠입니다."),

    CONTENT_NOT_PUBLISHED(HttpStatus.CONFLICT, "CONTENT_NOT_PUBLISHED", "게시되지 않은 콘텐츠입니다."),

    /*
     * 413 — 본문이 10만 자를 넘을 때. 행사(EVENT_CONTENT_TOO_LARGE)와 같은 판정이다.
     * @Size로 400을 내지 않는 것은 «너무 크다»와 «형식이 틀렸다»를 화면이 다르게 안내하기
     * 때문이다.
     */
    CONTENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "CONTENT_TOO_LARGE", "본문이 너무 깁니다."),

    /*
     * 400 — 표지로 지정한 파일이 이 포스트의 갤러리에 없을 때. 404가 아닌 것은 «포스트를 못
     * 찾았다»와 «요청 본문의 값이 틀렸다»를 가르기 위해서다 — 표지는 갤러리에서 고르는 값이라
     * 화면이 보낼 수 없는 요청이다.
     */
    COVER_NOT_IN_GALLERY(
            HttpStatus.BAD_REQUEST, "COVER_NOT_IN_GALLERY", "표지는 이 포스트의 갤러리에서 골라야 합니다."),

    /*
     * 400 — 잘못된 커서. 첫 페이지로 조용히 되돌리지 않는 것은 다른 도메인의 INVALID_CURSOR와
     * 같은 판단이다 — 목록이 잘렸다는 사실을 클라이언트가 알아야 한다. 코드 문자열은 그쪽과
     * 같은 VALIDATION_FAILED다.
     */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "잘못된 커서입니다."),

    // 400 — 허용 목록(ImageFileType) 밖의 확장자. 행사 이미지와 같은 계약이다 (#210)
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_TYPE", "지원하지 않는 이미지 형식입니다."),

    // 413 — 신고한 크기가 상한(10MB)을 넘을 때. 안내이지 방어선이 아니다 (행사와 같다)
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "이미지가 너무 큽니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
