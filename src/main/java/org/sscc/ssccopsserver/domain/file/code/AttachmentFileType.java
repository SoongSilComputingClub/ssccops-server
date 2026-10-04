package org.sscc.ssccopsserver.domain.file.code;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * 운영 건 첨부로 받는 파일 형식 (#493 · ssccops#410). 확장자 → Content-Type 한 표.
 *
 * ImageFileType과 따로 두는 것은 두 목록의 이유가 다르기 때문이다 — 그쪽은 «브라우저가 그릴 수 있는가»,
 * 이쪽은 «운영 결과물로 흔한가». 문서·표·발표·압축·이미지가 전부이고 실행 파일·스크립트는 없다.
 * Content-Type은 서명에 들어가므로(FilePresigner.presignPut) 웹은 이 값을 그대로 PUT 헤더에 쓴다.
 *
 * **오브젝트 키의 확장자는 명시 필드(extension)다** (#638). 처음에는 받는 확장자 집합의 «첫 값»을
 * 썼는데 `Set.of`는 순회 순서를 정하지 않아 — JVM이 기동마다 섞는다(JDK 17.0.12로 12회 기동해
 * jpg 8 · jpeg 4) — JPEG 첨부의 키가 배포마다 `.jpg`·`.jpeg`로 갈렸다. 원소 순서만 고정하는 길
 * (List.of·LinkedHashSet)은 «키에 붙일 확장자»라는 뜻을 순서에 숨기므로 ImageFileType과 같은
 * 모양을 택했다. 이미 `.jpeg`로 발급된 키는 그대로 읽힌다 — 저장 값이 키 자체라 옮길 것이 없다.
 *
 * 목록을 늘릴 때는 여기 한 줄이다 — 웹은 목록을 복제하지 않고 발급 응답 코드로 안내한다(이미지와 같은 규칙).
 */
@Getter
@RequiredArgsConstructor
public enum AttachmentFileType {
    PDF("application/pdf", "pdf", Set.of("pdf")),
    DOC("application/msword", "doc", Set.of("doc")),
    DOCX(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "docx",
            Set.of("docx")),
    XLS("application/vnd.ms-excel", "xls", Set.of("xls")),
    XLSX(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlsx",
            Set.of("xlsx")),
    PPT("application/vnd.ms-powerpoint", "ppt", Set.of("ppt")),
    PPTX(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "pptx",
            Set.of("pptx")),
    HWP("application/x-hwp", "hwp", Set.of("hwp")),
    HWPX("application/hwp+zip", "hwpx", Set.of("hwpx")),
    TXT("text/plain", "txt", Set.of("txt")),
    MD("text/markdown", "md", Set.of("md")),
    CSV("text/csv", "csv", Set.of("csv")),
    ZIP("application/zip", "zip", Set.of("zip")),
    PNG("image/png", "png", Set.of("png")),
    JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
    WEBP("image/webp", "webp", Set.of("webp")),
    GIF("image/gif", "gif", Set.of("gif"));

    private final String contentType;

    /** 오브젝트 키에 붙일 확장자. 형식당 하나로 굳힌다 */
    private final String extension;

    /** 파일 이름에서 받아 주는 확장자들 (전부 소문자) */
    private final Set<String> fileExtensions;

    /** 파일 이름의 확장자(앞뒤 공백을 다듬은 뒤 마지막 점 뒤 · 소문자)로 찾는다. 없거나 모르는 것은 empty */
    public static Optional<AttachmentFileType> ofFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String trimmed = fileName.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot < 0 || dot == trimmed.length() - 1) {
            return Optional.empty();
        }
        String ext = trimmed.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.fileExtensions.contains(ext)).findFirst();
    }
}
