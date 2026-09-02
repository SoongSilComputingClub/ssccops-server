package org.sscc.ssccopsserver.domain.member.code;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 회원 변경 이력의 **변경 항목** (mbr_chg_hstry.chg_artcl_cd · ssccops#162).
 *
 * 코드값·표시명은 데이터사전 표준코드 시트(chg_artcl_cd)와 글자 하나까지 맞춘 것이다. 여기
 * 표시명을 자바에 두는 것이 mbr_grd·mbr_stts 같은 기준 코드 테이블과 갈리는 지점인데, 근거는
 * **운영 중에 값이 늘지 않는다**는 사실이다. 항목은 mbr의 컬럼이므로 새 항목이 생긴다는 것은
 * 곧 컬럼이 하나 느는 일이고, 그때는 어차피 배포가 따라온다 — FormStatus·EventStatus가
 * 코드테이블 없이 enum으로만 서 있는 것과 같은 성격이다.
 *
 * **등급·상태는 여기 없다.** 전용 이력(mbr_grd_hstry · mbr_stts_hstry)이 이미 그 사건을
 * 적용일·사유와 함께 담고 있어, 여기 넣으면 같은 변경이 두 표에 남는다(ssccops#162 설계 노트).
 * 전산 가입일·계정 식별자도 없다 — 사람이 고쳐 쓰는 값이 아니라 애초에 수정 경로가 없다.
 *
 * **선언 순서가 이력이 쌓이는 순서다.** 한 번의 저장이 여러 항목을 바꾸면 이 순서대로 행이
 * 만들어지고(MemberProfileChangeRecorder), 같은 시각에 기록된 이력의 표시 순서도 그것을 따른다.
 * mbr의 컬럼 순서 그대로라 화면의 수정 폼과 이력의 줄 순서가 어긋나지 않는다.
 */
@Getter
@AllArgsConstructor
public enum MemberChangeField {

    /** 학번 (mbr.stdnt_no). ssccops#161이 잠금을 풀면서 이력 항목이 됐다 */
    STUDENT_NUMBER("학번"),

    /** 기수 (mbr.gen_no). 0은 미배정 센티널이라 이력에도 그대로 "0"으로 남는다 */
    GENERATION_NUMBER("기수"),

    /** 동아리 가입 연도 (mbr.clb_join_yr_no). 기수의 근거값이다 */
    CLUB_JOIN_YEAR("동아리 가입 연도"),

    /** 동아리 가입 월 (mbr.clb_join_mm_no) */
    CLUB_JOIN_MONTH("동아리 가입 월"),

    /** 회원명 (mbr.mbr_nm) */
    MEMBER_NAME("회원명"),

    /** 학과 (mbr.scsbjt_nm) */
    DEPARTMENT_NAME("학과"),

    /** 학년 (mbr.scyr_no) */
    ACADEMIC_YEAR("학년"),

    /** 연락처 (mbr.telno) */
    PHONE_NUMBER("연락처"),

    /** 이메일 (mbr.eml). 본인 경로에는 없는 항목이라 운영진 수정으로만 쌓인다 */
    EMAIL("이메일");

    /*
     * 화면에 그대로 찍히는 한글 이름. 응답의 changeFieldName으로 함께 내려간다 — 코드값만
     * 주면 화면이 아홉 개짜리 대응표를 따로 갖게 되고, 그 표는 항목이 늘 때 조용히 낡는다
     * (등급·상태 이력이 previousName·newName을 함께 싣는 것과 같은 판단).
     */
    private final String displayName;

    public String code() {
        return name();
    }
}
