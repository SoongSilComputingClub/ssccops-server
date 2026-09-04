package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/*
 * 출석 인증사진 업로드 URL 발급 요청 (#137 · POST .../file-reference).
 *
 * 받는 것은 확장자와 크기 둘이다. 예전에는 확장자 하나뿐이었는데(학술관리_API설계.md §3.5)
 * 그래서 **인증사진에는 크기 상한이 안내조차 없었다** — 행사 이미지가 10MB에서 끊는 동안
 * 이쪽은 아무 크기나 올라갔다(ssccops#188). contentType을 여전히 받지 않는 것은 그대로다:
 * 서버가 바이트를 보지 않는 이상 그 값은 요청이 한 신고일 뿐이고, 형식은 확장자 하나로
 * 정한다(#210이 행사 쪽에서 내린 것과 같은 판단이다).
 *
 * **크기는 신고이지 사실이 아니다.** 그래도 받는 이유는 두 가지다 — 업로드를 시작하기 전에
 * 화면이 안내할 수 있고, 그 값이 그대로 서명에 들어가 **실제 강제가 된다**(신고와 실제가
 * 다르면 R2가 PUT을 거절한다).
 *
 * **파일명을 받지 않는다** — 오브젝트 키는 UUID로 만들며(키 규칙은 서비스 주석) 파일명을
 * 키에 실으면 한글·공백·`../`가 그대로 키가 된다.
 */
public record FileReferenceUploadRequest(
        @NotBlank(message = "fileExt는 필수입니다.") @Size(max = 10) String fileExt,
        @NotNull(message = "fileSize는 필수입니다.") @Positive Long fileSize) {

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
