package org.sscc.ssccopsserver.domain.member.code;

/*
 * 회원 변경 이력의 **출처** (#82). 통합 이력 조회의 type 필터가 쓰는 어휘다.
 *
 * 데이터사전(SSoT)에서 회원의 변화를 남기는 테이블은 셋뿐이다 — mbr_grd_hstry · mbr_stts_hstry ·
 * mbr_role_rel. audit_log 같은 통합 감사 테이블은 **사전에 없으므로** 회원 정보(이름·연락처·학과)
 * 수정은 어디에도 쌓이지 않고, 따라서 이 열거형에도 자리가 없다. 그것까지 답하려면 테이블
 * 등재가 먼저다(#8).
 *
 * 표시용 종류(MemberChangeType)와 값이 하나 어긋난다. 역할은 한 행(mbr_role_rel)이 '부여'와
 * '종료'라는 **두 사건**을 담으므로 타임라인에는 ROLE_ASSIGNED·ROLE_ENDED 두 줄로 나오지만,
 * 화면의 필터는 "역할 이력을 볼 것인가"를 체크박스 하나로 묻는다. 둘을 한 열거형으로 합치면
 * type=ROLE_ASSIGNED만 걸었을 때 임기 시작만 보이고 종료는 사라지는 목록이 되는데, 그것은
 * 이력을 보는 사람이 원한 것이 아니다. MemberChangeType.source()가 두 어휘를 잇는다.
 */
public enum MemberHistorySource {

    /** 등급 변경 (mbr_grd_hstry) */
    GRADE,

    /** 상태 변경 (mbr_stts_hstry) */
    STATUS,

    /** 역할 부여·종료 (mbr_role_rel) */
    ROLE
}
