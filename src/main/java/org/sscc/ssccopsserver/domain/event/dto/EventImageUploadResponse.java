package org.sscc.ssccopsserver.domain.event.dto;

/*
 * 이미지 업로드 URL 발급 응답 (#161 · wave2 D6 · #208).
 *
 * 네 값의 쓰임이 각각 다르다:
 * - uploadUrl : 웹이 **한 번** PUT 할 주소(서명 포함). 만료가 짧으므로 저장하지 않는다.
 * - imageUrl  : 본문 마크다운에 박아 넣을 읽기 주소. 이 문자열이 곧 영구 참조다.
 * - objectKey : 버킷 안의 키. 나중에 고아 정리·이관 같은 운영 작업의 유일한 지목 수단이다.
 * - expiresInSeconds : 화면이 "다시 시도" 시점을 판단하는 근거. 서버 상수를 그대로 내린다.
 *
 * **publicUrl에서 imageUrl로 이름이 바뀌었다** (#208). 그 값은 공개 버킷의 주소였는데, 버킷이
 * 행사 이미지와 학술 인증사진을 함께 담고 있어 공개할 수 없다(ssccops#156 — 공개 접근은 버킷
 * 단위라 접두사로 가를 수 없고, 공개하면 얼굴이 찍힌 인증사진이 함께 열린다). 지금 이 값은
 * **우리 API의 리다이렉트 주소**이며 열릴 때마다 서명된 R2 GET으로 302를 받는다. 이름에서
 * "public"이 빠진 것은 그 주소가 더는 오브젝트 스토리지의 공개 주소가 아니기 때문이다.
 *
 * **서명 URL을 여기에 싣지 않는 것이 요점 하나다.** 서명은 15분이면 만료되는데 이 값은 본문
 * 마크다운에 문자열로 굳으므로, 서명 URL을 저장하면 시간이 지난 본문이 통째로 깨진다.
 *
 * **웹이 이 주소를 조립하지 않는 것이 요점 둘이다.** 프론트가 규칙을 알고 URL을 만들면 경로나
 * 도메인을 바꾸는 날 이미 저장된 본문의 링크와 새 링크가 갈린다.
 */
public record EventImageUploadResponse(
        String uploadUrl, String imageUrl, String objectKey, long expiresInSeconds) {}
