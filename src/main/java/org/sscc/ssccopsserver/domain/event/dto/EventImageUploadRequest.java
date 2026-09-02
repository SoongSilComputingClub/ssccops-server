package org.sscc.ssccopsserver.domain.event.dto;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/*
 * 이미지 업로드 URL 발급 요청 (#161 · POST /v1/events/{eventId}/images).
 *
 * 두 값 모두 **파일 자체가 아니라 파일에 대한 신고**다 — 서버는 바이트를 받지 않으므로(D6)
 * 확인할 수 있는 것이 이것뿐이다. 그래서 이 검증은 방어선이 아니라 안내이며, 실제 강제는
 * 버킷 정책의 몫이다.
 *
 * **fileName·contentType을 받지 않는다** (#210 · ssccops#157). 원래는 셋을 받아 contentType과
 * 확장자가 서로 맞는지까지 봤는데, 그 contentType은 브라우저가 `File.type`으로 채워 보내는
 * 값이라 **브라우저·OS·파일에 따라 비거나 비표준이다** — 매핑을 모르는 파일은 `""`로 오고,
 * JPEG를 `image/jpg`(표준은 `image/jpeg`)로 싣는 환경이 있다. 게다가 두 값을 교차 검증하므로
 * 둘 중 하나만 어긋나도 400이 났고, 그것이 행사 이미지 업로드가 통째로 실패한 원인이다.
 *
 * 그래서 **받는 것은 확장자 하나뿐이다** — 학술 인증사진(#137 · FileReferenceUploadRequest)이
 * 애초에 이 문제를 만들지 않은 계약이며, 정규화 방식까지 그쪽과 같다. 어긋날 값이 하나뿐이면
 * 어긋날 수 없고, 형식(contentType)은 서버가 그 확장자로 정해 응답에 실어 준다.
 *
 * 파일명을 받지 않게 된 것은 곁가지 이득이 아니다 — 애초에 오브젝트 키에 쓰이지도 않았고
 * (키는 `events/{eventId}/{uuid}.{ext}`) 확장자를 뽑는 용도뿐이었는데, 파일명을 키에 실으면
 * 한글·공백·`../`가 그대로 키가 되고 같은 이름을 두 번 올리면 앞의 것을 덮어쓴다.
 *
 * **fileSize는 남긴다.** 10MB 안내는 형식 판정과 무관하고, 업로드를 시작하기 전에 화면이
 * 안내할 수 있게 하는 값이다(학술에 이 필드가 없는 것은 그쪽에 상한이 없어서지 계약이
 * 달라서가 아니다).
 */
public record EventImageUploadRequest(
        @NotBlank @Size(max = 10) String fileExt, @NotNull @Positive Long fileSize) {

    /*
     * 비교·조회에 쓸 확장자. 앞뒤 공백과 대소문자를 지우고 앞의 점도 뗀다 — 화면이 `jpg`로
     * 보내든 `.JPG`로 보내든 같은 형식이며, 정규화를 웹에 맡기면 규칙이 두 벌이 된다
     * (FileReferenceUploadRequest.normalizedFileExt와 같은 방식이다).
     *
     * 정규화하고도 빈 문자열이면(`.` 하나만 보낸 경우처럼) 어떤 허용 형식과도 맞지 않으므로
     * UNSUPPORTED_IMAGE_TYPE로 끊긴다.
     */
    public String normalizedFileExt() {
        if (fileExt == null) {
            return "";
        }
        String trimmed = fileExt.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}
