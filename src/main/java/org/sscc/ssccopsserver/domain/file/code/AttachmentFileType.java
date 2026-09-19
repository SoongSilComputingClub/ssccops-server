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
 * 목록을 늘릴 때는 여기 한 줄이다 — 웹은 목록을 복제하지 않고 발급 응답 코드로 안내한다(이미지와 같은 규칙).
 */
@Getter
@RequiredArgsConstructor
public enum AttachmentFileType {
    PDF("application/pdf", Set.of("pdf")),
    DOC("application/msword", Set.of("doc")),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx")),
    XLS("application/vnd.ms-excel", Set.of("xls")),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx")),
    PPT("application/vnd.ms-powerpoint", Set.of("ppt")),
    PPTX(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            Set.of("pptx")),
    HWP("application/x-hwp", Set.of("hwp")),
    HWPX("application/hwp+zip", Set.of("hwpx")),
    TXT("text/plain", Set.of("txt")),
    MD("text/markdown", Set.of("md")),
    CSV("text/csv", Set.of("csv")),
    ZIP("application/zip", Set.of("zip")),
    PNG("image/png", Set.of("png")),
    JPEG("image/jpeg", Set.of("jpg", "jpeg")),
    WEBP("image/webp", Set.of("webp")),
    GIF("image/gif", Set.of("gif"));

    private final String contentType;
    private final Set<String> extensions;

    /** 파일 이름의 확장자(마지막 점 뒤 · 소문자)로 찾는다. 없거나 모르는 것은 empty */
    public static Optional<AttachmentFileType> ofFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.extensions.contains(ext)).findFirst();
    }

    /** 오브젝트 키에 붙일 확장자 — 첫 값(jpeg는 jpg) */
    public String primaryExtension() {
        return extensions.iterator().next();
    }
}
