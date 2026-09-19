package org.sscc.ssccopsserver.domain.content.code;

/*
 * slug 규칙 (ssccops#381). 공개 주소(/pages/{slug} · /posts/{slug})의 마지막 조각이며 페이지·
 * 포스트가 같은 규칙을 쓴다.
 *
 * 소문자 영숫자와 하이픈만, 하이픈으로 시작·끝나지 않고 연속되지 않는다. 한글 slug를 받지
 * 않은 것은 주소가 퍼센트 인코딩되어 카카오톡·에브리타임에 붙여 넣은 링크가 읽히지 않기
 * 때문이다. 숫자 id로 공개 주소를 만들지 않는 것은 폼(ADR-0036)과 같은 이유다 — 1부터 훑으면
 * 초안이 있는지가 새어 나간다(초안은 404지만 «있다/없다»의 차이는 응답 시간에 남는다).
 *
 * 검증은 요청 DTO의 @Pattern이 한다 — 이 클래스는 그 정규식의 정본이다.
 */
public final class ContentSlug {

    public static final int MAX_LENGTH = 80;

    public static final String PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    private ContentSlug() {}
}
