package org.sscc.ssccopsserver.domain.content.dto;

/*
 * 갤러리 한 장 — 파일 id와 영구 읽기 주소 (ssccops#381). 주소는 서명 URL이 아니라 우리
 * 도메인의 리다이렉트 주소(/public/v1/posts/{postId}/images/{fileId})라 만료되지 않는다 —
 * 행사 이미지(#208)와 같은 구조다. 어드민·익명 응답이 같은 모양을 쓰는 것은 담는 값이
 * «파일 id·주소» 둘뿐이라 새어 나갈 것이 없기 때문이다.
 */
public record ContentImageResponse(Long fileId, String imageUrl) {}
