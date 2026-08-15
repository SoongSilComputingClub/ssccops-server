package org.sscc.ssccopsserver.domain.member.code;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/*
 * CSV 이관에서 매핑할 수 있는 대상 필드 (#84 · ssccops#75).
 *
 * key는 요청의 mapping JSON이 값으로 쓰는 문자열이며(`{"이름":"mbrNm"}`) 데이터사전의 mbr 컬럼명을
 * 카멜케이스로 옮긴 것이다. 웹이 이 목록 밖의 값을 보내면 CSV_MAPPING_INVALID로 끊는다 —
 * 조용히 무시하면 화면에서 매핑한 컬럼이 서버에서 사라진 채 "정상" 응답이 돌아간다.
 *
 * **졸업연도·역할은 여기 없다** (BR-M42 · BR-M44). mbr에 대응 컬럼이 없어 매핑해도 담을 곳이 없다.
 * 자리를 만들어 두면 운영자가 매핑한 뒤 값이 사라지는 것을 이관이 끝난 뒤에야 알게 된다.
 *
 * mbrGrdCd·mbrSttsCd의 CSV 값은 코드가 아니라 한글 명칭('정회원'·'재학')이다. 코드로 되돌리는 것은
 * MemberImportValidator가 기준 코드 테이블(mbr_grd·mbr_stts)을 조회해서 한다 — 명칭을 자바 코드에
 * 적어 두면 기준정보에서 이름을 바꿨을 때 이관만 옛 이름을 요구하게 된다.
 *
 * aliases는 preview의 **추천** 매핑에만 쓰인다. 여기 있는 한글 낱말은 위의 '명칭 하드코딩 금지'에
 * 걸리지 않는다 — 기준정보가 아니라 남의 엑셀 파일 머리글을 짐작하는 값이고, 운영자가 화면에서
 * 확인·수정한 매핑만 검증에 쓰이기 때문이다. 짐작이 빗나가도 매핑이 비어 나올 뿐 검증은 틀리지 않는다.
 */
public enum MemberImportField {

    /** 회원명. 이름 없는 행은 사람을 가리키지 못하므로 매핑도 값도 필수다 */
    MEMBER_NAME("mbrNm", true, List.of("이름", "회원명", "성명", "회원이름", "name")),

    /** 학번. 재학 회원은 필수이고 졸업 회원은 선택이다(가입 규칙과 같다) */
    STUDENT_NUMBER("stdntNo", false, List.of("학번", "학생번호", "studentnumber")),

    /** 기수. 미입력은 0(미배정)이며 **학번으로 추정하지 않는다** (BR-M43) */
    GENERATION_NUMBER("genNo", false, List.of("기수", "generation")),

    DEPARTMENT_NAME("scsbjtNm", false, List.of("학과", "전공", "학부", "department")),

    /*
     * 영문 별칭에 grade를 두지 않는다 — 학년과 등급 양쪽을 가리키는 낱말이라, 어느 쪽으로 추천해도
     * 절반은 틀린다. 틀린 추천을 그대로 확인 버튼으로 넘기면 등급 컬럼이 학년으로 들어간다.
     */
    ACADEMIC_YEAR("scyrNo", false, List.of("학년", "year", "schoolyear")),

    /** 연락처. 비어 있어도 오류가 아니라 경고다 (ssccops#78 A안 — 계정 연결에 필요하다) */
    PHONE_NUMBER("telno", false, List.of("전화번호", "연락처", "휴대폰", "핸드폰", "전화", "phone")),

    EMAIL("eml", false, List.of("이메일", "메일", "email", "mail")),

    JOIN_DATE("joinYmd", false, List.of("가입일", "가입일자", "가입년월일", "joindate")),

    /*
     * 등급·상태는 mbr에서 NOT NULL이고 서버가 정할 근거가 없어 **매핑이 필수**다.
     * 기수(0)·가입일(이관일)처럼 기본값을 둘 수 없다 — 어느 등급으로 넣을지는 명부가 아는 사실이지
     * 서버가 고를 값이 아니다. 가입 API가 TEMP로 고정하는 것과는 상황이 다르다(그쪽은 본인 신청이다).
     */
    GRADE_NAME("mbrGrdCd", true, List.of("등급", "회원등급", "membergrade")),

    STATUS_NAME("mbrSttsCd", true, List.of("상태", "회원상태", "학적", "학적상태", "status"));

    private final String key;
    private final boolean mappingRequired;
    private final List<String> aliases;

    MemberImportField(String key, boolean mappingRequired, List<String> aliases) {
        this.key = key;
        this.mappingRequired = mappingRequired;
        this.aliases = aliases;
    }

    /** mapping JSON의 값이자 행별 결과의 field로 내려가는 문자열 */
    public String key() {
        return key;
    }

    /** 매핑되지 않으면 400 CSV_MAPPING_INVALID로 끊는 필드인지 */
    public boolean isMappingRequired() {
        return mappingRequired;
    }

    public static Optional<MemberImportField> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String trimmed = key.trim();
        for (MemberImportField field : values()) {
            if (field.key.equals(trimmed)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    /*
     * CSV 헤더가 이 필드로 추천되는지. 공백을 지우고 소문자로 맞춰 비교하므로 '전화 번호'·'Name'도
     * 걸린다. 부분 일치를 쓰지 않는 것은 '학번'이 '학번(구)'에 걸리는 것보다 '학년'이 '학년도'에
     * 걸리는 쪽의 손해가 크기 때문이다 — 추천이 빗나가면 운영자가 고르면 되지만, 잘못 추천된
     * 매핑을 그대로 확인 버튼으로 넘기면 엉뚱한 컬럼이 들어간다.
     */
    public boolean matchesHeader(String header) {
        String normalized = normalize(header);
        return !normalized.isEmpty()
                && aliases.stream().anyMatch(a -> normalize(a).equals(normalized));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
