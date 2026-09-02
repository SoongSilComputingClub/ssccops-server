package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 출석 인증사진 업로드 URL 발급 요청 (#137 · POST .../file-reference).
 *
 * 받는 것이 확장자 하나뿐인 것은 계약이 그렇기 때문이다(학술관리_API설계.md §3.5). 행사
 * 이미지(#161)가 파일명·contentType·크기 셋을 받아 서로 맞는지까지 보는 것과 갈리는데,
 * 그쪽은 운영자가 본문에 붙이는 임의의 삽화이고 이쪽은 회차당 한 장뿐인 인증사진이라
 * 화면이 신고할 것이 형식 말고는 없다.
 *
 * **파일명을 받지 않는다** — 오브젝트 키는 UUID로 만들며(키 규칙은 서비스 주석) 파일명을
 * 키에 실으면 한글·공백·`../`가 그대로 키가 된다.
 */
public record FileReferenceUploadRequest(
        @NotBlank(message = "fileExt는 필수입니다.") @Size(max = 10) String fileExt) {

    /*
     * 비교·조회에 쓸 확장자. 앞뒤 공백과 대소문자를 지우고 앞의 점도 뗀다 — 화면이 `jpg`로
     * 보내든 `.JPG`로 보내든 같은 형식이며, 정규화를 웹에 맡기면 규칙이 두 벌이 된다.
     */
    public String normalizedFileExt() {
        if (fileExt == null) {
            return "";
        }
        String trimmed = fileExt.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}
