package org.sscc.ssccopsserver.domain.member.code;

/*
 * 회원 변경 이력의 종류 (#76).
 *
 * mbr_grd_hstry·mbr_stts_hstry는 테이블이 둘이지만 화면에서는 '이 회원에게 무슨 일이
 * 있었는가' 하나의 목록이다. 두 테이블을 합쳐 시간 역순으로 내릴 때 각 줄이 어느 쪽에서
 * 왔는지 알려주는 값이며, 프론트는 이 값으로 배지 색과 문구를 고른다.
 *
 * 이력 테이블을 하나로 합치지 않는 것은 데이터사전이 둘로 정의하고 있기 때문이다 —
 * 상태 이력에만 있는 종료 예정일(stts_end_prnmnt_ymd)처럼 한쪽에만 있는 컬럼도 있다.
 */
public enum MemberChangeType {

    /** 등급 변경 (mbr_grd_hstry) */
    GRADE,

    /** 상태 변경 (mbr_stts_hstry) */
    STATUS
}
