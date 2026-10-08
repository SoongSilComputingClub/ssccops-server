package org.sscc.ssccopsserver.domain.member.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFName;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterRow;

/*
 * 회원명부를 동아리연합회 원본 양식에 채워 넣는다 (#674 · ssccops#598).
 *
 * ── 왜 원본 파일을 채우는가 ─────────────────────────────────────
 * 양식에는 값보다 많은 것이 들어 있다 — 병합된 제목 · 서식 · 단대(C열) 목록 드롭다운 · 고른 단대에
 * 따라 바뀌는 학과(D열) 드롭다운(INDIRECT) · 그 목록이 사는 둘째 시트와 이름 범위. 코드로 처음부터
 * 그리면 그 하나하나가 코드가 되고 연합회 원본과 대조할 길이 없다. 원본을 리소스로 두고 값만
 * 채우면 연합회가 양식을 바꿨을 때 파일을 바꿔 끼우는 것으로 끝난다.
 *
 * ── 이 클래스가 정하는 것 ───────────────────────────────────────
 * 누가 들어가고 직책이 무엇인지는 MemberRosterExportServiceImpl이 정한다. 여기는 «양식의 칸에 어떤
 * 모양으로 적는가»뿐이다 — 단대 추정 · 학번 숫자 셀 · 연락처 표기 · 남는 행 정리 · 행 늘리기.
 */
@Component
public class MemberRosterWorkbookWriter {

    static final String TEMPLATE = "export/club-roster-template.xlsx";

    /*
     * 분과·동아리 표기는 연합회에 이미 낸 회칙 제출본(«2026년도_학술분과_SSCC_동아리회칙»)과 같다.
     * 양식의 «00분과»·«동아리명»·«00동아리» 자리에 들어간다.
     */
    private static final String DIVISION = "학술분과";
    private static final String CLUB = "SSCC";

    /* 양식 좌표(0부터). 제목은 A1(A1:G1 병합), 머리글은 2행, 데이터는 3행부터 7열(직책~연락처)이다 */
    private static final int TITLE_ROW = 0;
    private static final int FIRST_DATA_ROW = 2;
    private static final int COLUMN_COUNT = 7;
    private static final int COLLEGE_COLUMN = 2;
    private static final int DEPARTMENT_COLUMN = 3;

    /*
     * 양식이 서식과 드롭다운을 깔아 둔 마지막 행(250행)이다. 3행부터 248명분이고, 넘치면 이 행의
     * 서식을 복제하고 드롭다운을 그만큼 더한다.
     */
    private static final int TEMPLATE_LAST_ROW = 249;

    /* 단대 목록의 이름 범위. 단대 이름마다 같은 이름의 범위가 그 단대의 학과 목록이다 — 드롭다운이 쓰는 그 표다 */
    private static final String COLLEGE_LIST_NAME = "단과대";

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    public static String titleOf(int year, int semester) {
        return year + "년도 " + semester + "학기 " + CLUB + " 회원명부(재학생)";
    }

    public static String fileNameOf(int year, int semester) {
        return year + "년도_" + DIVISION + "_" + CLUB + "_" + semester + "학기_동아리회원명부.xlsx";
    }

    public byte[] write(String title, List<MemberRosterRow> rows) {
        try (InputStream template = new ClassPathResource(TEMPLATE).getInputStream();
                XSSFWorkbook workbook = new XSSFWorkbook(template);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet sheet = workbook.getSheetAt(0);
            Map<String, String> collegeByDepartment = collegeByDepartment(workbook);

            sheet.getRow(TITLE_ROW).getCell(0).setCellValue(title);
            for (int i = 0; i < rows.size(); i++) {
                fill(dataRow(sheet, FIRST_DATA_ROW + i), rows.get(i), collegeByDepartment);
            }
            /*
             * 양식에 미리 적혀 있던 남는 행(예시 «홍길동»·«부회장»·«정회원»)을 비운다. 행을 지우지 않고
             * 값만 지우는 것은 서식·드롭다운이 깔린 빈 행이 원본의 모양이기 때문이다.
             */
            for (int r = FIRST_DATA_ROW + rows.size(); r <= TEMPLATE_LAST_ROW; r++) {
                clear(sheet.getRow(r));
            }
            int lastDataRow = FIRST_DATA_ROW + rows.size() - 1;
            if (lastDataRow > TEMPLATE_LAST_ROW) {
                addDropdowns(sheet, TEMPLATE_LAST_ROW + 1, lastDataRow);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("회원명부 양식을 읽거나 쓰지 못했다: " + TEMPLATE, e);
        }
    }

    private static void fill(
            XSSFRow row, MemberRosterRow member, Map<String, String> collegeByDepartment) {
        String department = trimToNull(member.departmentName());
        text(row, 0, member.position());
        text(row, 1, member.name());
        text(row, COLLEGE_COLUMN, department == null ? null : collegeByDepartment.get(department));
        text(row, DEPARTMENT_COLUMN, department);
        studentNumber(row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), member);
        Cell academicYear = row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if (member.academicYear() == null) {
            academicYear.setBlank();
        } else {
            academicYear.setCellValue(member.academicYear());
        }
        text(row, 6, phoneOf(member.phoneNumber()));
    }

    /*
     * 학번은 숫자 셀이다 — 양식 예시(20260000)가 그렇고, 문자열로 두면 Excel이 칸마다 «숫자가 텍스트로
     * 저장됨» 표시를 단다. 숫자가 아닌 것이 섞였거나 0으로 시작하면(숫자로 바꾸면 앞자리가 사라진다)
     * 저장된 그대로 문자열로 둔다. stdnt_no가 VARCHAR라 숫자라는 보장이 없다(MemberLinkPolicy).
     */
    private static void studentNumber(Cell cell, MemberRosterRow member) {
        String value = trimToNull(member.studentNumber());
        if (value == null) {
            cell.setBlank();
        } else if (DIGITS.matcher(value).matches()
                && value.charAt(0) != '0'
                && value.length() <= 15) {
            cell.setCellValue(Double.parseDouble(value));
        } else {
            cell.setCellValue(value);
        }
    }

    /*
     * 연락처는 양식 예시와 같은 010-0000-0000으로 적는다. telno는 가입 화면의 자유 입력이라 하이픈이
     * 있기도 없기도 한데, 명부에서 줄마다 모양이 다르면 손으로 고치게 된다. 숫자 11자리 휴대전화
     * 번호만 바꾸고 그 밖의 값은 저장된 그대로 둔다 — 모르는 모양을 추측해 고치면 틀린 번호가 나간다.
     * 저장값은 건드리지 않는다.
     */
    static String phoneOf(String phoneNumber) {
        String value = trimToNull(phoneNumber);
        if (value == null) {
            return null;
        }
        String digits = NON_DIGIT.matcher(value).replaceAll("");
        if (digits.length() == 11 && digits.startsWith("01")) {
            return digits.substring(0, 3)
                    + "-"
                    + digits.substring(3, 7)
                    + "-"
                    + digits.substring(7);
        }
        return value;
    }

    /*
     * 학과(부) → 단대. 목록을 코드에 두지 않고 양식에서 읽는다 — 연합회가 학과 목록을 바꾸면 양식을
     * 바꿔 끼우는 것으로 이 매핑도 함께 바뀌고, 화면의 드롭다운과 서버의 추정이 같은 표를 본다.
     *
     * 학과는 가입 화면의 자유 입력이라 이 목록과 정확히 같을 때만 단대를 채운다(앞뒤 공백만 무시).
     * 비슷해 보이는 이름을 맞춰 주면 틀린 단대가 제출본에 실린다 — 비어 있으면 사람이 채운다.
     */
    private static Map<String, String> collegeByDepartment(XSSFWorkbook workbook) {
        Map<String, String> colleges = new HashMap<>();
        XSSFName collegeList = workbook.getName(COLLEGE_LIST_NAME);
        if (collegeList == null) {
            return colleges;
        }
        for (String college : textsOf(workbook, collegeList)) {
            XSSFName departments = workbook.getName(college);
            if (departments == null) {
                continue;
            }
            for (String department : textsOf(workbook, departments)) {
                colleges.putIfAbsent(department, college);
            }
        }
        return colleges;
    }

    private static List<String> textsOf(XSSFWorkbook workbook, XSSFName name) {
        AreaReference area =
                new AreaReference(name.getRefersToFormula(), SpreadsheetVersion.EXCEL2007);
        XSSFSheet sheet = workbook.getSheet(area.getFirstCell().getSheetName());
        if (sheet == null) {
            return List.of();
        }
        return Arrays.stream(area.getAllReferencedCells())
                .map(reference -> textAt(sheet, reference))
                .filter(Objects::nonNull)
                .toList();
    }

    private static String textAt(XSSFSheet sheet, CellReference reference) {
        Row row = sheet.getRow(reference.getRow());
        Cell cell = row == null ? null : row.getCell(reference.getCol());
        return cell == null ? null : trimToNull(cell.toString());
    }

    /*
     * 데이터 행. 양식 안(250행까지)은 원본 행을 그대로 쓰고, 넘치면 마지막 양식 행의 서식을 복제해
     * 새로 만든다 — 249번째 회원부터 서식이 끊긴 명부를 제출하지 않게.
     */
    private static XSSFRow dataRow(XSSFSheet sheet, int index) {
        XSSFRow row = sheet.getRow(index);
        if (row != null) {
            return row;
        }
        XSSFRow styleSource = sheet.getRow(TEMPLATE_LAST_ROW);
        row = sheet.createRow(index);
        row.setHeight(styleSource.getHeight());
        for (int column = 0; column < COLUMN_COUNT; column++) {
            Cell source = styleSource.getCell(column);
            Cell cell = row.createCell(column);
            if (source != null) {
                cell.setCellStyle(source.getCellStyle());
            }
        }
        return row;
    }

    /*
     * 양식 밖으로 늘어난 행에도 원본과 같은 두 드롭다운을 단다. 학과 목록의 INDIRECT는 범위의 첫 칸
     * 기준 상대 참조라(원본은 D3:D250에 INDIRECT(C3)) 늘어난 범위의 첫 행을 가리키게 다시 적는다.
     */
    private static void addDropdowns(XSSFSheet sheet, int firstRow, int lastRow) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        addDropdown(
                sheet,
                helper.createValidation(
                        helper.createFormulaListConstraint(COLLEGE_LIST_NAME),
                        new CellRangeAddressList(
                                firstRow, lastRow, COLLEGE_COLUMN, COLLEGE_COLUMN)));
        String firstCollegeCell =
                new CellReference(firstRow, COLLEGE_COLUMN, false, false).formatAsString();
        addDropdown(
                sheet,
                helper.createValidation(
                        helper.createFormulaListConstraint("INDIRECT(" + firstCollegeCell + ")"),
                        new CellRangeAddressList(
                                firstRow, lastRow, DEPARTMENT_COLUMN, DEPARTMENT_COLUMN)));
    }

    private static void addDropdown(XSSFSheet sheet, DataValidation validation) {
        validation.setEmptyCellAllowed(true);
        validation.setShowErrorBox(true);
        validation.setShowPromptBox(true);
        // XSSF에서는 true가 화살표를 «보인다»다(이름과 반대 — POI의 XSSFDataValidation 주석). 원본처럼 보이게 둔다
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    private static void text(XSSFRow row, int column, String value) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if (value == null || value.isEmpty()) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
    }

    private static void clear(XSSFRow row) {
        if (row == null) {
            return;
        }
        for (int column = 0; column < COLUMN_COUNT; column++) {
            Cell cell = row.getCell(column);
            if (cell != null) {
                cell.setBlank();
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
