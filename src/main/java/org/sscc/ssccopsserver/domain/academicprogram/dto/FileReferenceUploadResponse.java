package org.sscc.ssccopsserver.domain.academicprogram.dto;

/*
 * 출석 인증사진 업로드 URL 발급 응답 (#137 · POST .../file-reference).
 *
 * - fileReferenceId : 회차에 붙은 참조의 식별자. 재업로드해도 바뀌지 않는다(UPSERT).
 * - uploadUrl       : 웹이 **한 번** PUT 할 서명된 주소. 만료가 짧으므로 저장하지 않는다.
 * - publicUrl       : 저장된 file_url_addr 그대로. 화면이 <img src>에 넣는 문자열이다.
 * - contentType     : 그 PUT에 **반드시 실어야 하는** Content-Type 헤더 값.
 *
 * **contentType은 §3.5의 세 필드에 없지만 계약에 더했다.** 서명에 contentType을 넣지 않으면
 * 허가받은 URL로 아무 형식이나 올릴 수 있어 확장자 검사가 무의미해지는데(#161이 같은 이유로
 * 서명에 넣는다), 넣은 다음 웹이 그 값을 스스로 짐작하게 두면 확장자 → MIME 사전이 서버와
 * 웹에 한 벌씩 생기고 어긋나는 날 브라우저의 PUT만 조용히 거절당한다(서버 로그에는 아무것도
 * 남지 않는다). 그래서 서명에 쓴 값을 그대로 돌려준다.
 *
 * expiresInSeconds는 두지 않았다 — #161과 달리 이 화면은 발급 직후 곧바로 한 장을 올리고,
 * 실패하면 같은 요청을 다시 부르면 된다(재업로드가 UPSERT라 그 재시도가 안전하다).
 */
public record FileReferenceUploadResponse(
        Long fileReferenceId, String uploadUrl, String publicUrl, String contentType) {}
