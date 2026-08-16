package org.sscc.ssccopsserver.domain.member.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;

/*
 * 한 번의 검증 요청이 쓰는 기준 데이터 묶음 (#84).
 *
 * 행마다 조회하지 않기 위해 미리 모아 둔다 — 등급·상태는 기준 코드 테이블 전량 두 번, 학번 중복은
 * 파일에 나온 학번 전체를 한 번에 모아 한 번이다. 128행이면 행별 조회로는 384번이 된다.
 *
 * **등급·상태 명칭을 자바 코드에 적지 않는 것이 이 클래스의 존재 이유다.** '정회원'·'재학'은
 * mbr_grd_nm·mbr_stts_nm의 값이고 기준정보 화면에서 바뀔 수 있다 — 코드에 박아 두면 이름을 바꾼
 * 다음 날부터 명부의 '정회원'이 "기준 코드에 없는 명칭"으로 거절된다.
 */
public record MemberImportReferenceData(
        Map<String, String> gradeCodeByName,
        Map<String, String> statusCodeByName,
        Set<String> existingStudentNumbers,
        Set<String> repeatedStudentNumbers) {

    public static MemberImportReferenceData of(
            List<MemberGradeEntity> grades,
            List<MemberStatusEntity> statuses,
            Set<String> existingStudentNumbers,
            Set<String> repeatedStudentNumbers) {

        return new MemberImportReferenceData(
                index(
                        grades,
                        MemberGradeEntity::getName,
                        MemberGradeEntity::getCode,
                        MemberGradeEntity::getDisplayOrder),
                index(
                        statuses,
                        MemberStatusEntity::getName,
                        MemberStatusEntity::getCode,
                        MemberStatusEntity::getDisplayOrder),
                existingStudentNumbers,
                repeatedStudentNumbers);
    }

    public Optional<String> gradeCodeOf(String name) {
        return Optional.ofNullable(gradeCodeByName.get(normalize(name)));
    }

    public Optional<String> statusCodeOf(String name) {
        return Optional.ofNullable(statusCodeByName.get(normalize(name)));
    }

    /*
     * 명칭 → 코드 역매핑.
     *
     * **mbr_grd_nm·mbr_stts_nm에는 UNIQUE 제약이 없다.** 같은 명칭이 둘 있으면 표시 순번이 앞선
     * 쪽(동률이면 코드가 앞선 쪽)을 고른다 — 화면의 선택 상자가 같은 순서로 그려지므로 운영자가
     * 보는 첫 번째 항목과 서버가 고르는 항목이 같아진다. 거절하지 않는 것은 명부 전체가 기준정보
     * 한 줄의 실수 때문에 멈추는 것보다, 같은 이름 중 하나로 이관되고 나중에 등급을 고치는 편이
     * 낫기 때문이다(등급·상태는 변경 API가 있는 값이다).
     *
     * 비교 키는 공백을 지우고 소문자로 맞춘 값이다 — '정 회원'처럼 띄어 쓴 명부가 실제로 있다.
     */
    private static <T> Map<String, String> index(
            List<T> entities,
            Function<T, String> nameOf,
            Function<T, String> codeOf,
            Function<T, Integer> displayOrderOf) {

        return entities.stream()
                .sorted(
                        Comparator.comparing(displayOrderOf, Comparator.nullsLast(Integer::compare))
                                .thenComparing(codeOf))
                .collect(
                        Collectors.toMap(
                                entity -> normalize(nameOf.apply(entity)),
                                codeOf,
                                (first, second) -> first));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
