package org.sscc.ssccopsserver.domain.event.dto;

/*
 * 이미지 업로드 URL 발급 응답 (#161 · wave2 D6).
 *
 * 네 값의 쓰임이 각각 다르다:
 * - uploadUrl : 웹이 **한 번** PUT 할 주소(서명 포함). 만료가 짧으므로 저장하지 않는다.
 * - publicUrl : 본문 마크다운에 박아 넣을 공개 읽기 주소. 이 문자열이 곧 영구 참조다.
 * - objectKey : 버킷 안의 키. 나중에 고아 정리·이관 같은 운영 작업의 유일한 지목 수단이다.
 * - expiresInSeconds : 화면이 "다시 시도" 시점을 판단하는 근거. 서버 상수를 그대로 내린다.
 *
 * **publicUrl을 웹이 조립하지 않는 것이 요점이다.** 계정 ID·버킷 이름으로 프론트가 URL을
 * 만들면 버킷이나 공개 도메인을 바꾸는 날 이미 저장된 본문의 링크와 새 링크가 갈린다.
 */
public record EventImageUploadResponse(
        String uploadUrl, String publicUrl, String objectKey, long expiresInSeconds) {}
