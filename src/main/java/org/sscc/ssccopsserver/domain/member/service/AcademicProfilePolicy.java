package org.sscc.ssccopsserver.domain.member.service;

import java.util.ArrayList;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;

/*
 * "이 회원 상태에서 학번·학과·학년이 필수인가"의 **유일한 구현** (#21 · #84).
 *
 * 원래 MemberSignupRequest.isAcademicProfileComplete()에만 있던 규칙인데, CSV 이관 검증(#84)과
 * 회원 정보 수정(#77)이 같은 판단을 해야 해서 꺼냈다. 복제하면 가입에서 막히는 값이 이관에서
 * 통과해, 화면으로는 만들 수 없는 회원이 파일로는 들어온다.
 *
 * '어떤 상태가 요구하는가'는 MemberStatusCode.requiresAcademicProfile()이 갖고, 여기는
 * **어떤 필드가 요구되는가**를 갖는다. 두 물음이 갈라져 있어야 상태가 늘 때(휴학을 재학과 같게
 * 볼지 등) 고칠 자리와 필드가 늘 때 고칠 자리가 섞이지 않는다.
 *
 * 판정에 저장소가 필요 없어 스프링 빈이 아니라 정적 메서드다 — 값만 보고 답할 수 있는 규칙에
 * 주입을 요구하면 요청 DTO(MemberSignupRequest)가 이 규칙을 부를 수 없다.
 */
public final class AcademicProfilePolicy {

    private AcademicProfilePolicy() {}

    /** 재학 회원에게 필수인 학적 필드. 이름은 mbr 컬럼이 아니라 개념을 가리킨다 */
    public enum AcademicField {
        STUDENT_NUMBER,
        DEPARTMENT_NAME,
        ACADEMIC_YEAR
    }

    /*
     * 채워지지 않은 필수 학적 필드. 재학이 아니면 언제나 빈 목록이다.
     *
     * boolean 하나가 아니라 목록인 것은 CSV 이관이 행별로 **어느 필드가** 비었는지 알려 줘야 하기
     * 때문이다. 가입 API는 화면이 세 칸을 한 덩어리로 다뤄 하나로 뭉뚱그려도 되지만, 명부 128건은
     * 그 안내로는 고칠 수 없다.
     */
    public static List<AcademicField> missingRequiredFields(
            MemberStatusCode statusCode,
            String studentNumber,
            String departmentName,
            Integer academicYear) {

        if (!requiresAcademicProfile(statusCode)) {
            return List.of();
        }

        List<AcademicField> missing = new ArrayList<>(AcademicField.values().length);
        if (!hasText(studentNumber)) {
            missing.add(AcademicField.STUDENT_NUMBER);
        }
        if (!hasText(departmentName)) {
            missing.add(AcademicField.DEPARTMENT_NAME);
        }
        if (academicYear == null) {
            missing.add(AcademicField.ACADEMIC_YEAR);
        }
        return missing;
    }

    /*
     * 상태를 모르면(null) 요구하지 않는다. 상태 자체가 비었거나 기준 코드 밖이라는 것은 다른
     * 검증이 이미 알려주므로, 여기서 한 번 더 실패시키면 같은 원인이 사유 네 개로 불어난다.
     */
    public static boolean requiresAcademicProfile(MemberStatusCode statusCode) {
        return statusCode != null && statusCode.requiresAcademicProfile();
    }

    public static boolean isComplete(
            MemberStatusCode statusCode,
            String studentNumber,
            String departmentName,
            Integer academicYear) {

        return missingRequiredFields(statusCode, studentNumber, departmentName, academicYear)
                .isEmpty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
