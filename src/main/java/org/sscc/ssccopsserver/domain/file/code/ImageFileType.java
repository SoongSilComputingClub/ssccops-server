package org.sscc.ssccopsserver.domain.file.code;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * 업로드를 허용하는 이미지 형식 (#161 · wave2 D6 · #220에서 event 도메인에서 옮겨 왔다).
 *
 * **행사 본문 이미지와 학술 출석 인증사진이 이 표 하나를 함께 쓴다.** 원래 event 도메인에
 * 있었고 학술이 그것을 import 하고 있었는데, 두 도메인이 대등하게 쓰는 값이 한쪽 도메인에
 * 살면 그 방향이 우연히 정해진 것으로 굳는다 — 파일 도메인이 생겼으므로 여기로 옮긴다.
 *
 * **허용 목록이며 넷으로 시작한다.** SVG를 넣지 않는 것은 그것이 이미지이면서 동시에 스크립트를
 * 담을 수 있는 문서라서다 — 읽기 주소(imageUrl)를 브라우저가 그대로 열므로 허용하는 순간 XSS
 * 경로가 된다. 버킷이 비공개가 되고 읽기가 리다이렉트로 바뀌어도(#208) 최종적으로 바이트를
 * 받는 것은 여전히 브라우저라 이 판단은 그대로다. 형식을 늘리려면 이 표에 한 줄을 더하는 것이 전부이고, 검증도 키 생성도
 * 여기만 본다.
 *
 * **오브젝트 키의 확장자는 원본 파일명이 아니라 이 표의 값(extension)으로 붙인다.** 파일명을
 * 그대로 쓰면 한글·공백·`../`가 오브젝트 키가 되고, `photo.JPEG`와 `photo.jpeg`가 다른 키가
 * 되어 같은 형식이 두 벌로 쌓인다. 그래서 jpg·jpeg처럼 통용되는 확장자를 함께 받되
 * (fileExtensions) 키에 쓰는 값은 하나로 굳힌다.
 *
 * **찾는 길은 확장자 하나뿐이다** (#210 · ssccops#157). 예전에는 contentType으로도 찾을 수
 * 있었고 행사 이미지가 두 값을 교차 검증했는데, 서버가 바이트를 보지 않는 이상 어느 쪽도 파일의
 * 진짜 정체가 아니라 요청이 한 신고라 그 검증은 지킬 것을 지키지 못했다 — 대신 브라우저가
 * `File.type`을 비우거나 비표준으로 채우는 흔한 경우에 멀쩡한 업로드만 막았다. 지금은 두
 * 사용처(행사 #161 · 학술 #137)가 모두 확장자만 넘기고, contentType은 이 표의 값을 서명과
 * 응답에 함께 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum ImageFileType {
    PNG("image/png", "png", Set.of("png")),
    JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
    WEBP("image/webp", "webp", Set.of("webp")),
    GIF("image/gif", "gif", Set.of("gif"));

    private final String contentType;

    /** 오브젝트 키에 붙일 확장자. 형식당 하나로 굳힌다 */
    private final String extension;

    /** 요청 파일명에서 받아 주는 확장자들 (전부 소문자) */
    private final Set<String> fileExtensions;

    /*
     * 확장자만으로 형식을 찾는다 (#137 출석 인증사진 · #210부터 행사 이미지도 같다). 요청이
     * 신고하는 값이 확장자 하나뿐이므로 서명에 실을 contentType은 이 표에서 끌어오고, 그 값을
     * 응답으로 돌려주어 웹이 그대로 PUT 헤더에 쓰게 한다(SessionFileReferenceServiceImpl ·
     * EventImageServiceImpl 주석).
     *
     * 허용 목록을 학술 도메인에 한 벌 더 적지 않고 이 표를 함께 쓰는 것은, 두 벌이 되면
     * SVG를 뺀 이유 같은 판단이 한쪽에만 반영되기 때문이다(위 주석 — 형식을 늘리는 자리는
     * 한 곳이다).
     */
    public static Optional<ImageFileType> ofFileExtension(String fileExtension) {
        if (fileExtension == null) {
            return Optional.empty();
        }
        String normalized = fileExtension.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.fileExtensions.contains(normalized))
                .findFirst();
    }
}
