package org.sscc.ssccopsserver.domain.event.dto;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/*
 * 이미지 업로드 URL 발급 요청 (#161 · POST /v1/events/{eventId}/images).
 *
 * 세 값 모두 **파일 자체가 아니라 파일에 대한 신고**다 — 서버는 바이트를 받지 않으므로(D6)
 * 확인할 수 있는 것이 이것뿐이다. 그래서 이 검증은 방어선이 아니라 안내이며, 실제 강제는
 * 버킷/도메인 정책의 몫이다.
 *
 * **fileName은 오브젝트 키에 쓰이지 않는다** — 확장자를 뽑는 데에만 쓴다(키는
 * `events/{eventId}/{uuid}.{ext}`). 파일명을 키에 실으면 한글·공백·`../`가 그대로 키가 되고
 * 같은 이름을 두 번 올리면 앞의 것을 덮어쓴다.
 */
public record EventImageUploadRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank String contentType,
        @NotNull @Positive Long fileSize) {

    /*
     * 파일명에서 뽑은 소문자 확장자(점 제외). 점이 없거나 점으로 끝나면 빈 문자열이며, 그것은
     * 어떤 허용 형식과도 맞지 않으므로 UNSUPPORTED_IMAGE_TYPE로 끊긴다.
     */
    public String fileExtension() {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).trim().toLowerCase(Locale.ROOT);
    }
}
