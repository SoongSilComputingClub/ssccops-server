package org.sscc.ssccopsserver.domain.event.code;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * 행사 본문에 올릴 수 있는 이미지 형식 (#161 · wave2 D6).
 *
 * **허용 목록이며 넷으로 시작한다.** SVG를 넣지 않는 것은 그것이 이미지이면서 동시에 스크립트를
 * 담을 수 있는 문서라서다 — 공개 도메인(publicUrl)에서 그대로 열리므로 허용하는 순간 XSS
 * 경로가 된다. 형식을 늘리려면 이 표에 한 줄을 더하는 것이 전부이고, 검증도 키 생성도
 * 여기만 본다.
 *
 * **오브젝트 키의 확장자는 원본 파일명이 아니라 이 표의 값(extension)으로 붙인다.** 파일명을
 * 그대로 쓰면 한글·공백·`../`가 오브젝트 키가 되고, `photo.JPEG`와 `photo.jpeg`가 다른 키가
 * 되어 같은 형식이 두 벌로 쌓인다. 그래서 jpg·jpeg처럼 통용되는 확장자를 함께 받되
 * (fileExtensions) 키에 쓰는 값은 하나로 굳힌다.
 *
 * contentType과 확장자를 **둘 다** 보고 서로 맞아야 통과시킨다 — 한쪽만 보면 `evil.html`을
 * `image/png`라고 주장하거나 그 반대로 통과시킬 수 있다.
 */
@Getter
@RequiredArgsConstructor
public enum EventImageType {
    PNG("image/png", "png", Set.of("png")),
    JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
    WEBP("image/webp", "webp", Set.of("webp")),
    GIF("image/gif", "gif", Set.of("gif"));

    private final String contentType;

    /** 오브젝트 키에 붙일 확장자. 형식당 하나로 굳힌다 */
    private final String extension;

    /** 요청 파일명에서 받아 주는 확장자들 (전부 소문자) */
    private final Set<String> fileExtensions;

    /** contentType으로 형식을 찾는다. 대소문자와 앞뒤 공백은 무시한다 */
    public static Optional<EventImageType> ofContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.contentType.equals(normalized))
                .findFirst();
    }

    /** 이 형식이 그 확장자를 인정하는가. 판단만 하고 거절은 서비스가 한다 */
    public boolean matchesExtension(String fileExtension) {
        return fileExtension != null
                && fileExtensions.contains(fileExtension.trim().toLowerCase(Locale.ROOT));
    }
}
