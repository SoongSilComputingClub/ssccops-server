package org.sscc.ssccopsserver.domain.member.code;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 회원 변경 이력의 종류 (#76 · #82에서 역할 두 종을 더함).
 *
 * mbr_grd_hstry·mbr_stts_hstry·mbr_role_rel은 테이블이 셋이지만 화면에서는 '이 회원에게 무슨 일이
 * 있었는가' 하나의 목록이다. 세 출처를 합쳐 시간 역순으로 내릴 때 각 줄이 어느 쪽에서 왔는지
 * 알려주는 값이며, 프론트는 이 값으로 배지 색과 문구를 고른다.
 *
 * 이력 테이블을 하나로 합치지 않는 것은 데이터사전이 따로 정의하고 있기 때문이다 —
 * 상태 이력에만 있는 종료 예정일(stts_end_prnmnt_ymd)처럼 한쪽에만 있는 컬럼도 있다.
 *
 * **역할은 한 행이 두 사건이다.** mbr_role_rel 한 행에는 시작일(role_bgng_ymd)과 종료일
 * (role_end_ymd)이 함께 있고, 종료는 삭제가 아니라 종료일을 채우는 것이므로(#81) 임기가 끝난
 * 행에는 '부여'와 '종료'라는 서로 다른 시각의 사건이 둘 들어 있다. 타임라인은 사건 단위라
 * 두 줄로 펼친다.
 *
 * **상수 순서를 바꾸지 말 것.** 같은 시각에 기록된 이력의 순서를 종류로 끊는데
 * (MemberChangeHistoryAssembler), 그 비교가 enum 선언 순서를 쓴다. 새 종류는 뒤에 붙인다.
 */
@Getter
@AllArgsConstructor
public enum MemberChangeType {

    /** 등급 변경 (mbr_grd_hstry) */
    GRADE(MemberHistorySource.GRADE),

    /** 상태 변경 (mbr_stts_hstry) */
    STATUS(MemberHistorySource.STATUS),

    /** 역할 부여 (mbr_role_rel · role_bgng_ymd) */
    ROLE_ASSIGNED(MemberHistorySource.ROLE),

    /** 역할 종료 (mbr_role_rel · role_end_ymd) */
    ROLE_ENDED(MemberHistorySource.ROLE);

    /** 이 종류가 어느 출처에서 왔는가. type 필터(MemberHistorySource)와 표시 종류를 잇는다 */
    private final MemberHistorySource source;
}
