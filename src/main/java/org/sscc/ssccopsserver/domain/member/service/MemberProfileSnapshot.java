package org.sscc.ssccopsserver.domain.member.service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.sscc.ssccopsserver.domain.member.code.MemberChangeField;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 이력이 지켜보는 회원 정보 아홉 항목의 한 시점 사본 (#226).
 *
 * 수정 전후를 **같은 모양으로 두 번 뜨기 위해** 있다. 엔티티에서 직접 비교하려 하면 이미 값이
 * 바뀐 뒤라 이전 값을 알 수 없고(JPA는 더티 체킹 결과를 도메인에 돌려주지 않는다), 필드마다
 * 지역 변수로 붙들어 두면 그 아홉 줄이 두 수정 경로에 각각 생겨 한쪽만 항목이 빠지는 날이 온다.
 *
 * **MemberChangeField의 아홉 항목과 일대일이다.** 항목이 늘면 여기 필드 하나와 아래 switch의
 * 가지 하나가 함께 늘고, switch가 enum을 전부 덮지 않으면 컴파일이 막는다 — 항목만 늘리고
 * 값 꺼내는 자리를 잊는 경로를 언어가 닫아 준다.
 *
 * 레코드로 두는 것은 이 값이 '그때 이랬다'는 사실이라 만들어진 뒤 바뀌면 안 되기 때문이다.
 */
public record MemberProfileSnapshot(
        String studentNumber,
        Integer generationNumber,
        Integer clubJoinYear,
        Integer clubJoinMonth,
        String name,
        String departmentName,
        Integer academicYear,
        String phoneNumber,
        String email) {

    public static MemberProfileSnapshot of(MemberEntity member) {
        return new MemberProfileSnapshot(
                member.getStudentNumber(),
                member.getGenerationNumber(),
                member.getClubJoinYear(),
                member.getClubJoinMonth(),
                member.getName(),
                member.getDepartmentName(),
                member.getAcademicYear(),
                member.getPhoneNumber(),
                member.getEmail());
    }

    /*
     * 항목 하나의 값을 이력에 담을 문자열로 꺼낸다.
     *
     * 숫자를 문자열로 바꾸는 자리가 여기 하나뿐인 것이 중요하다 — bfr_cn·aftr_cn이 문자열
     * 한 쌍이라(ssccops#162 설계 노트) 어딘가에서 다른 방식으로 찍으면 같은 기수가 "31"과
     * "31.0"으로 갈려 바뀌지 않은 값이 바뀐 것으로 기록된다.
     *
     * **비어 있는 값은 null이다.** 빈 문자열로 통일하지 않는 것은 "지웠다"와 "빈 문자열로
     * 바꿨다"를 이력이 같은 행으로 보이게 하지 않기 위해서이며, 서비스가 이미 trimToNull로
     * 빈 문자열을 NULL로 다듬어 저장하므로 여기 도달하는 값도 그 규칙을 따른다.
     */
    public String valueOf(MemberChangeField field) {
        return switch (field) {
            case STUDENT_NUMBER -> studentNumber;
            case GENERATION_NUMBER -> text(generationNumber);
            case CLUB_JOIN_YEAR -> text(clubJoinYear);
            case CLUB_JOIN_MONTH -> text(clubJoinMonth);
            case MEMBER_NAME -> name;
            case DEPARTMENT_NAME -> departmentName;
            case ACADEMIC_YEAR -> text(academicYear);
            case PHONE_NUMBER -> phoneNumber;
            case EMAIL -> email;
        };
    }

    /*
     * 이 사본에서 `after`로 가며 바뀐 항목의 **이름**. 감사 로그(ADR-0024)가 값 대신 싣는 것이다 —
     * 값은 mbr_chg_hstry에 있고 로그에는 전화·이메일·이름이 들어가면 안 된다. 같은 비교를
     * MemberProfileChangeRecorder가 하지만 그쪽은 행을 만드는 자리라 여기서 이름만 다시 센다.
     */
    public List<String> changedFieldNames(MemberProfileSnapshot after) {
        return Arrays.stream(MemberChangeField.values())
                .filter(field -> !Objects.equals(valueOf(field), after.valueOf(field)))
                .map(Enum::name)
                .toList();
    }

    private static String text(Integer value) {
        return value == null ? null : String.valueOf(value);
    }
}
