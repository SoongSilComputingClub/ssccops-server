package org.sscc.ssccopsserver.domain.file.code;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * 운영 건 첨부의 형식 판정과 키 확장자 (#638).
 *
 * 키 확장자가 받는 확장자 집합(`Set.of`)의 «첫 값»이던 동안 JPEG 첨부의 키가 기동마다
 * `.jpg`·`.jpeg`로 갈렸다 — 한 JVM 안에서는 순서가 고정이라 테스트 한 번으로는 드러나지 않았다.
 * 그래서 여기서는 «키 확장자가 명시 값인가»를 표의 모든 행에 대해 본다.
 */
class AttachmentFileTypeTest {

    @Test
    void jpegAndJpgBothResolveToJpegWhoseKeyExtensionIsJpg() {
        assertThat(AttachmentFileType.ofFileName("photo.jpeg")).contains(AttachmentFileType.JPEG);
        assertThat(AttachmentFileType.ofFileName("photo.jpg")).contains(AttachmentFileType.JPEG);
        assertThat(AttachmentFileType.JPEG.getExtension()).isEqualTo("jpg");
    }

    /* 확장자는 대소문자를 가리지 않는다 — 윈도우에서 온 `보고서.PDF`가 막히면 안 된다 */
    @Test
    void extensionIsCaseInsensitive() {
        assertThat(AttachmentFileType.ofFileName("보고서.PDF")).contains(AttachmentFileType.PDF);
        assertThat(AttachmentFileType.ofFileName("발표.PpTx")).contains(AttachmentFileType.PPTX);
        assertThat(AttachmentFileType.ofFileName("사진.JPEG")).contains(AttachmentFileType.JPEG);
    }

    /* 마지막 점 뒤만 본다 — `archive.tar.gz`류의 앞 확장자에 속지 않는다 */
    @Test
    void usesOnlyTheLastExtension() {
        assertThat(AttachmentFileType.ofFileName("회의록.v2.final.docx"))
                .contains(AttachmentFileType.DOCX);
        assertThat(AttachmentFileType.ofFileName("report.pdf.exe")).isEmpty();
    }

    /* ImageFileType.ofFileExtension과 같게 앞뒤 공백을 다듬는다 — 서비스도 원본 이름을 trim해 저장한다 */
    @Test
    void trimsSurroundingWhitespace() {
        assertThat(AttachmentFileType.ofFileName("  예산안.xlsx  ")).contains(AttachmentFileType.XLSX);
        assertThat(AttachmentFileType.ofFileName("메모.txt\n")).contains(AttachmentFileType.TXT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "   ",
                "확장자없음",
                "점으로끝남.",
                "점으로끝남.  ",
                "실행.exe",
                "그림.svg",
                "스크립트.sh",
            })
    void unknownOrMissingExtensionIsEmpty(String fileName) {
        assertThat(AttachmentFileType.ofFileName(fileName)).isEmpty();
    }

    /*
     * 표의 모든 행에 대해 키 확장자가 받는 확장자 중 하나이고 소문자다. 행을 더하면서 키 확장자를
     * 엉뚱하게 적으면 «받은 파일과 다른 확장자의 키»가 생긴다.
     */
    @ParameterizedTest
    @EnumSource(AttachmentFileType.class)
    void keyExtensionIsOneOfTheAcceptedLowercaseExtensions(AttachmentFileType type) {
        assertThat(type.getExtension()).isLowerCase();
        assertThat(type.getFileExtensions()).contains(type.getExtension());
        assertThat(type.getFileExtensions()).allSatisfy(ext -> assertThat(ext).isLowerCase());
        assertThat(AttachmentFileType.ofFileName("file." + type.getExtension())).contains(type);
    }
}
