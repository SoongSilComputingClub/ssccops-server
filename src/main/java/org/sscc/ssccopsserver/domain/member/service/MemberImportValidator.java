package org.sscc.ssccopsserver.domain.member.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.MemberImportField;
import org.sscc.ssccopsserver.domain.member.code.MemberImportRowStatus;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsvRow;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportMapping;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportRowResult;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportRowResult.MemberImportRowIssue;

/*
 * 행 단위 검증 (#84). 근거는 데이터사전(SSoT)의 mbr 컬럼 정의다.
 *
 * **학적 조건부 필수 규칙은 여기에 적지 않는다** — AcademicProfilePolicy를 부른다. 회원가입이
 * 쓰는 것과 같은 규칙이어야 화면으로는 만들 수 없는 회원이 파일로 들어오지 않는다.
 *
 * 한 행에서 사유를 **모아서** 돌려준다(첫 오류에서 멈추지 않는다). 명부를 고치는 사람은 파일을
 * 한 번 열어 다 고치고 싶지, 올릴 때마다 한 칸씩 알게 되고 싶지 않다.
 *
 * 오류와 중복을 나눠 담는 것은 상태 결정 때문이다 — 둘 다 있으면 ERROR 하나로 세고(요약의 세
 * 버킷이 겹치면 안 된다) 사유에는 둘 다 싣는다.
 */
@Component
public class MemberImportValidator {

    private static final String REQUIRED_MESSAGE = "필수값 누락";
    private static final String NO_NAME_TARGET = "(회원명 없음)";

    // 데이터사전의 컬럼 길이. DB가 자르기 전에 걸러야 이관 후에 이름이 잘려 있는 일이 없다
    private static final int NAME_MAX_LENGTH = 50;
    private static final int STUDENT_NUMBER_MAX_LENGTH = 20;
    private static final int DEPARTMENT_NAME_MAX_LENGTH = 100;
    private static final int PHONE_NUMBER_MAX_LENGTH = 20;
    private static final int EMAIL_MAX_LENGTH = 255;

    private static final int ACADEMIC_YEAR_MIN = 1;
    private static final int ACADEMIC_YEAR_MAX = 4;

    public MemberImportRowResult validate(
            MemberImportCsvRow row,
            MemberImportMapping mapping,
            MemberImportReferenceData reference) {

        List<MemberImportRowIssue> errors = new ArrayList<>();
        List<MemberImportRowIssue> duplicates = new ArrayList<>();
        List<MemberImportRowIssue> warnings = new ArrayList<>();

        String name = mapping.valueOf(MemberImportField.MEMBER_NAME, row);
        validateName(name, errors);

        MemberStatusCode statusCode = validateStatus(mapping, row, reference, errors);
        validateGrade(mapping, row, reference, errors);

        String studentNumber = mapping.valueOf(MemberImportField.STUDENT_NUMBER, row);
        String departmentName = mapping.valueOf(MemberImportField.DEPARTMENT_NAME, row);
        Integer academicYear = validateAcademicYear(mapping, row, errors);

        validateLength(
                MemberImportField.STUDENT_NUMBER, studentNumber, STUDENT_NUMBER_MAX_LENGTH, errors);
        validateLength(
                MemberImportField.DEPARTMENT_NAME,
                departmentName,
                DEPARTMENT_NAME_MAX_LENGTH,
                errors);

        validateAcademicProfile(statusCode, studentNumber, departmentName, academicYear, errors);
        validateGenerationNumber(mapping, row, errors);
        validatePhoneNumber(mapping, row, errors, warnings);
        validateLength(
                MemberImportField.EMAIL,
                mapping.valueOf(MemberImportField.EMAIL, row),
                EMAIL_MAX_LENGTH,
                errors);
        validateJoinDate(mapping, row, errors);

        validateDuplicates(studentNumber, reference, duplicates);

        List<MemberImportRowIssue> reasons = new ArrayList<>(errors);
        reasons.addAll(duplicates);

        return new MemberImportRowResult(
                row.rowNo(),
                describeTarget(name, studentNumber),
                statusOf(errors, duplicates),
                List.copyOf(reasons),
                List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ 항목별 규칙

    private static void validateName(String name, List<MemberImportRowIssue> errors) {
        if (name.isBlank()) {
            errors.add(issue(MemberImportField.MEMBER_NAME, REQUIRED_MESSAGE));
            return;
        }
        validateLength(MemberImportField.MEMBER_NAME, name, NAME_MAX_LENGTH, errors);
    }

    /*
     * 상태 명칭을 코드로 되돌린다. 없는 명칭은 오류이며, 그 행의 학적 조건부 필수 검사는 건너뛴다
     * (상태를 모르는 채로는 '재학이면 필수'를 물을 수 없다). null을 돌려주는 것이 그 신호다.
     *
     * 기준 코드 테이블에는 있지만 MemberStatusCode enum에는 없는 코드도 null이다 — 표준코드가
     * 늘어난 것이므로 이관을 막지 않고 학적 검사만 빠진다. enum이 따라오면 그때부터 걸린다.
     */
    private static MemberStatusCode validateStatus(
            MemberImportMapping mapping,
            MemberImportCsvRow row,
            MemberImportReferenceData reference,
            List<MemberImportRowIssue> errors) {

        String statusName = mapping.valueOf(MemberImportField.STATUS_NAME, row);
        if (statusName.isBlank()) {
            errors.add(issue(MemberImportField.STATUS_NAME, REQUIRED_MESSAGE));
            return null;
        }

        Optional<String> code = reference.statusCodeOf(statusName);
        if (code.isEmpty()) {
            errors.add(
                    issue(MemberImportField.STATUS_NAME, "기준 코드에 없는 회원 상태 명칭입니다: " + statusName));
            return null;
        }
        return toStatusCode(code.get());
    }

    private static void validateGrade(
            MemberImportMapping mapping,
            MemberImportCsvRow row,
            MemberImportReferenceData reference,
            List<MemberImportRowIssue> errors) {

        String gradeName = mapping.valueOf(MemberImportField.GRADE_NAME, row);
        if (gradeName.isBlank()) {
            errors.add(issue(MemberImportField.GRADE_NAME, REQUIRED_MESSAGE));
            return;
        }
        if (reference.gradeCodeOf(gradeName).isEmpty()) {
            errors.add(issue(MemberImportField.GRADE_NAME, "기준 코드에 없는 회원 등급 명칭입니다: " + gradeName));
        }
    }

    /*
     * 학년은 값의 형식(숫자·1~4)만 여기서 보고, '있어야 하는가'는 학적 규칙이 본다. 형식이 틀리면
     * null을 돌려주므로 재학 회원이라면 "필수값 누락"이 한 번 더 붙는데, 그게 맞다 — 3학년을
     * '삼'이라 적은 행은 학년이 없는 것과 같다.
     */
    private static Integer validateAcademicYear(
            MemberImportMapping mapping,
            MemberImportCsvRow row,
            List<MemberImportRowIssue> errors) {

        String raw = mapping.valueOf(MemberImportField.ACADEMIC_YEAR, row);
        if (raw.isBlank()) {
            return null;
        }

        Integer parsed = parseInteger(raw);
        if (parsed == null) {
            errors.add(issue(MemberImportField.ACADEMIC_YEAR, "숫자가 아닙니다: " + raw));
            return null;
        }
        if (parsed < ACADEMIC_YEAR_MIN || parsed > ACADEMIC_YEAR_MAX) {
            errors.add(issue(MemberImportField.ACADEMIC_YEAR, "학년은 1~4 사이여야 합니다: " + raw));
            return null;
        }
        return parsed;
    }

    private static void validateAcademicProfile(
            MemberStatusCode statusCode,
            String studentNumber,
            String departmentName,
            Integer academicYear,
            List<MemberImportRowIssue> errors) {

        AcademicProfilePolicy.missingRequiredFields(
                        statusCode, studentNumber, departmentName, academicYear)
                .forEach(field -> errors.add(issue(fieldOf(field), REQUIRED_MESSAGE)));
    }

    /*
     * 기수 미입력은 0(미배정)이라 오류가 아니다. **학번으로 추정하지 않는다** (BR-M43) —
     * 프로토타입 안내의 '학번으로 추정'은 근거가 없고, 추정한 기수는 나중에 사실과 구별되지 않는다.
     */
    private static void validateGenerationNumber(
            MemberImportMapping mapping,
            MemberImportCsvRow row,
            List<MemberImportRowIssue> errors) {

        String raw = mapping.valueOf(MemberImportField.GENERATION_NUMBER, row);
        if (raw.isBlank()) {
            return;
        }

        Integer parsed = parseInteger(raw);
        if (parsed == null) {
            errors.add(issue(MemberImportField.GENERATION_NUMBER, "숫자가 아닙니다: " + raw));
            return;
        }
        if (parsed < 0) {
            errors.add(issue(MemberImportField.GENERATION_NUMBER, "기수는 0 이상이어야 합니다: " + raw));
        }
    }

    /*
     * 연락처 누락은 **오류가 아니라 경고다.** 데이터사전이 telno에 NULL을 허용하므로 이관을 막을
     * 근거가 없다. 다만 계정 연결이 A안(학번+회원명+전화번호 3종 일치, ssccops#78)으로 정해져
     * 연락처가 빈 회원은 나중에 스스로 연결하지 못한다 — 운영자가 이관 전에 채워 넣을 기회를 준다.
     * 경고가 있어도 그 행은 OK이고 okCount에 들어간다.
     */
    private static void validatePhoneNumber(
            MemberImportMapping mapping,
            MemberImportCsvRow row,
            List<MemberImportRowIssue> errors,
            List<MemberImportRowIssue> warnings) {

        String phoneNumber = mapping.valueOf(MemberImportField.PHONE_NUMBER, row);
        if (phoneNumber.isBlank()) {
            warnings.add(
                    issue(
                            MemberImportField.PHONE_NUMBER,
                            "연락처가 없으면 이 회원은 나중에 스스로 계정을 연결할 수 없습니다."));
            return;
        }
        validateLength(
                MemberImportField.PHONE_NUMBER, phoneNumber, PHONE_NUMBER_MAX_LENGTH, errors);
    }

    /** 가입일 미입력은 이관일이 되므로 오류가 아니다. 형식이 어긋난 값만 걸린다 */
    private static void validateJoinDate(
            MemberImportMapping mapping,
            MemberImportCsvRow row,
            List<MemberImportRowIssue> errors) {

        String raw = mapping.valueOf(MemberImportField.JOIN_DATE, row);
        if (raw.isBlank()) {
            return;
        }
        try {
            LocalDate.parse(raw);
        } catch (DateTimeParseException ex) {
            errors.add(issue(MemberImportField.JOIN_DATE, "가입일은 yyyy-MM-dd 형식이어야 합니다: " + raw));
        }
    }

    /*
     * 중복은 두 방향을 모두 본다 (BR-M40 — 자동 병합은 없다. 보고만 하고 판단은 운영자가 한다).
     *  1. mbr에 이미 있는 학번
     *  2. 같은 CSV 안에서 겹치는 학번 → **두 행 모두** 중복이다. 앞의 것을 통과시키면 어느 쪽이
     *     맞는 행인지 서버가 고른 셈이 된다
     * 학번이 비어 있으면(졸업 회원) 볼 것이 없다 — 미입력은 NULL이라 서로 겹치지 않는다.
     */
    private static void validateDuplicates(
            String studentNumber,
            MemberImportReferenceData reference,
            List<MemberImportRowIssue> duplicates) {

        if (studentNumber.isBlank()) {
            return;
        }
        if (reference.existingStudentNumbers().contains(studentNumber)) {
            duplicates.add(issue(MemberImportField.STUDENT_NUMBER, "이미 등록된 학번"));
        }
        if (reference.repeatedStudentNumbers().contains(studentNumber)) {
            duplicates.add(issue(MemberImportField.STUDENT_NUMBER, "CSV 안에서 중복된 학번"));
        }
    }

    // ------------------------------------------------------------------ 헬퍼

    private static MemberImportRowStatus statusOf(
            List<MemberImportRowIssue> errors, List<MemberImportRowIssue> duplicates) {

        if (!errors.isEmpty()) {
            return MemberImportRowStatus.ERROR;
        }
        return duplicates.isEmpty() ? MemberImportRowStatus.OK : MemberImportRowStatus.DUPLICATE;
    }

    /** 목록에서 사람을 알아보기 위한 표시. 이름이 없으면 rowNo만 남으므로 자리 표시자를 둔다 */
    private static String describeTarget(String name, String studentNumber) {
        if (name.isBlank()) {
            return NO_NAME_TARGET;
        }
        return studentNumber.isBlank() ? name : name + " " + studentNumber;
    }

    private static void validateLength(
            MemberImportField field,
            String value,
            int maxLength,
            List<MemberImportRowIssue> errors) {

        if (value.length() > maxLength) {
            errors.add(issue(field, "%d자를 넘을 수 없습니다.".formatted(maxLength)));
        }
    }

    private static MemberImportField fieldOf(AcademicProfilePolicy.AcademicField field) {
        return switch (field) {
            case STUDENT_NUMBER -> MemberImportField.STUDENT_NUMBER;
            case DEPARTMENT_NAME -> MemberImportField.DEPARTMENT_NAME;
            case ACADEMIC_YEAR -> MemberImportField.ACADEMIC_YEAR;
        };
    }

    private static MemberStatusCode toStatusCode(String code) {
        for (MemberStatusCode statusCode : MemberStatusCode.values()) {
            if (statusCode.code().equals(code)) {
                return statusCode;
            }
        }
        return null;
    }

    private static Integer parseInteger(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static MemberImportRowIssue issue(MemberImportField field, String message) {
        return new MemberImportRowIssue(field.key(), message);
    }
}
