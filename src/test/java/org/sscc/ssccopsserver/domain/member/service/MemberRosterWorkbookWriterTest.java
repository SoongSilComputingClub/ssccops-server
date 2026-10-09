package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterRow;

/*
 * 회원명부 양식 채우기 (#674). 스프링 없이 원본 양식 리소스를 실제로 열고 쓴다.
 *
 * 확인의 중심은 **«값만 바뀌고 양식은 그대로인가»**다 — 제목 병합 · 둘째 시트 · 이름 범위 · 두
 * 드롭다운이 살아 있어야 연합회 원본과 같은 파일이고, 그것이 코드로 그리지 않고 원본을 채우는 이유다.
 */
class MemberRosterWorkbookWriterTest {

    private final MemberRosterWorkbookWriter writer = new MemberRosterWorkbookWriter();

    @Test
    void namesTitleAndFileLikeTheFederationForm() {
        assertThat(MemberRosterWorkbookWriter.titleOf(2026, 2, "재학생"))
                .isEqualTo("2026년도 2학기 SSCC 회원명부(재학생)");
        // 괄호에 적을 말이 없으면(상태를 전부 골랐다) 괄호째 뺀다
        assertThat(MemberRosterWorkbookWriter.titleOf(2026, 1, null))
                .isEqualTo("2026년도 1학기 SSCC 회원명부");
        assertThat(MemberRosterWorkbookWriter.fileNameOf(2026, 2))
                .isEqualTo("2026년도_학술분과_SSCC_2학기_동아리회원명부.xlsx");
    }

    @Test
    void fillsRowsAndKeepsTheFormIntact() throws IOException {
        List<MemberRosterRow> rows =
                List.of(
                        new MemberRosterRow("회장", "김회장", "컴퓨터학부", "20230001", 3, "01012345678"),
                        new MemberRosterRow(
                                "부회장", "이부회", "  경영학부 ", "20230002", 2, "010-2222-3333"),
                        new MemberRosterRow("정회원", "박정회", "소프트웨어학과", "20240003", 1, null));

        try (XSSFWorkbook workbook = open(writer.write("2026년도 2학기 SSCC 회원명부(재학생)", rows))) {
            XSSFSheet sheet = workbook.getSheetAt(0);

            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("2026년도 2학기 SSCC 회원명부(재학생)");
            assertThat(sheet.getMergedRegions()).contains(CellRangeAddress.valueOf("A1:G1"));
            assertThat(texts(sheet.getRow(1)))
                    .containsExactly("직책", "성명", "단대", "학과(부)", "학번", "학년", "연락처");

            // 단대는 양식의 학과 목록에서 온다. 앞뒤 공백은 무시하고, 목록에 없는 학과는 단대를 비운다
            assertThat(texts(sheet.getRow(2)))
                    .containsExactly(
                            "회장", "김회장", "IT대학", "컴퓨터학부", "20230001", "3", "010-1234-5678");
            assertThat(texts(sheet.getRow(3)))
                    .containsExactly(
                            "부회장", "이부회", "경영대학", "경영학부", "20230002", "2", "010-2222-3333");
            assertThat(texts(sheet.getRow(4)))
                    .containsExactly("정회원", "박정회", "", "소프트웨어학과", "20240003", "1", "");

            // 학번·학년은 양식 예시처럼 숫자 셀이다
            assertThat(sheet.getRow(2).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(2).getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);

            // 양식에 미리 적혀 있던 남는 행(예시·«정회원»)은 비고, 서식은 남는다
            assertThat(texts(sheet.getRow(5))).containsOnly("");
            assertThat(texts(sheet.getRow(249))).containsOnly("");
            assertThat(sheet.getRow(249).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(sheet.getRow(2).getCell(0).getCellStyle().getFillForegroundColor());

            // 둘째 시트·이름 범위·두 드롭다운이 그대로다
            assertThat(workbook.getSheet("단과대,학과부")).isNotNull();
            assertThat(workbook.getName("단과대")).isNotNull();
            assertThat(workbook.getName("IT대학")).isNotNull();
            assertThat(validationFormulas(sheet)).contains("단과대", "INDIRECT(C3)");
        }
    }

    /* 0으로 시작하거나 숫자가 아닌 학번은 문자열 그대로다 — 숫자로 바꾸면 앞자리가 사라진다 */
    @Test
    void keepsNonNumericStudentNumbersAsText() throws IOException {
        List<MemberRosterRow> rows =
                List.of(
                        new MemberRosterRow("회장", "김회장", null, "0123", null, null),
                        new MemberRosterRow("정회원", "박정회", null, "A2026", null, null),
                        new MemberRosterRow("정회원", "최졸업", null, null, null, null));

        try (XSSFWorkbook workbook = open(writer.write("제목", rows))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(4).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("0123");
            assertThat(sheet.getRow(3).getCell(4).getStringCellValue()).isEqualTo("A2026");
            assertThat(sheet.getRow(4).getCell(4).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(sheet.getRow(4).getCell(5).getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    /* 직책이 null이면 빈칸이다 — SSCC 표기법에서 대표 역할이 없는 회원 */
    @Test
    void leavesPositionBlankWhenAbsent() throws IOException {
        List<MemberRosterRow> rows =
                List.of(new MemberRosterRow(null, "박정회", null, "20240003", 1, null));

        try (XSSFWorkbook workbook = open(writer.write("제목", rows))) {
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(0).getCellType())
                    .isEqualTo(CellType.BLANK);
        }
    }

    /*
     * 양식이 깔아 둔 248명을 넘으면 마지막 양식 행의 서식으로 행을 늘리고, 늘어난 범위에도 같은 두
     * 드롭다운을 단다. 학과 드롭다운의 INDIRECT는 늘어난 범위의 첫 행을 가리켜야 한다.
     */
    @Test
    void growsBeyondTheFormWithSameStyleAndDropdowns() throws IOException {
        List<MemberRosterRow> rows = new ArrayList<>();
        IntStream.rangeClosed(1, 250)
                .forEach(
                        i ->
                                rows.add(
                                        new MemberRosterRow(
                                                "정회원",
                                                "회원" + i,
                                                "컴퓨터학부",
                                                String.valueOf(20260000 + i),
                                                1,
                                                null)));

        try (XSSFWorkbook workbook = open(writer.write("제목", rows))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            Row last = sheet.getRow(251); // 252행 = 250번째 회원
            assertThat(last.getCell(1).getStringCellValue()).isEqualTo("회원250");
            assertThat(last.getCell(2).getStringCellValue()).isEqualTo("IT대학");
            assertThat(last.getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(sheet.getRow(2).getCell(0).getCellStyle().getFillForegroundColor());
            assertThat(validationFormulas(sheet)).contains("INDIRECT(C251)");
            assertThat(validationRanges(sheet)).contains("C251:C252", "D251:D252");
        }
    }

    @Test
    void formatsOnlyElevenDigitMobileNumbers() {
        assertThat(MemberRosterWorkbookWriter.phoneOf("01012345678")).isEqualTo("010-1234-5678");
        assertThat(MemberRosterWorkbookWriter.phoneOf("010 1234 5678")).isEqualTo("010-1234-5678");
        assertThat(MemberRosterWorkbookWriter.phoneOf("010-1234-5678")).isEqualTo("010-1234-5678");
        // 모르는 모양은 추측해 고치지 않는다
        assertThat(MemberRosterWorkbookWriter.phoneOf("02-820-0114")).isEqualTo("02-820-0114");
        assertThat(MemberRosterWorkbookWriter.phoneOf("  ")).isNull();
        assertThat(MemberRosterWorkbookWriter.phoneOf(null)).isNull();
    }

    private static XSSFWorkbook open(byte[] content) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(content));
    }

    /* 셀을 화면에 보이는 문자열로 — 숫자 셀은 소수점 없이 */
    private static List<String> texts(Row row) {
        List<String> texts = new ArrayList<>();
        for (int column = 0; column < 7; column++) {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                texts.add("");
            } else if (cell.getCellType() == CellType.NUMERIC) {
                texts.add(String.valueOf((long) cell.getNumericCellValue()));
            } else {
                texts.add(cell.getStringCellValue());
            }
        }
        return texts;
    }

    private static List<String> validationFormulas(XSSFSheet sheet) {
        return sheet.getDataValidations().stream()
                .map(DataValidation::getValidationConstraint)
                .map(constraint -> constraint.getFormula1())
                .toList();
    }

    private static List<String> validationRanges(XSSFSheet sheet) {
        return sheet.getDataValidations().stream()
                .flatMap(
                        validation ->
                                Arrays.stream(validation.getRegions().getCellRangeAddresses()))
                .map(CellRangeAddress::formatAsString)
                .toList();
    }
}
