package org.sscc.ssccopsserver.domain.member.code;

/*
 * 회원 변경 이력의 **출처** (#82). 통합 이력 조회의 type 필터가 쓰는 어휘다.
 *
 * 데이터사전(SSoT)에서 회원의 변화를 남기는 테이블은 넷이다 — mbr_grd_hstry · mbr_stts_hstry ·
 * mbr_role_rel · mbr_chg_hstry. 마지막 하나가 #226에서 늘었다: 회원 정보(학번·이름·연락처·학과
 * 등 아홉 항목) 수정이 그전에는 어디에도 쌓이지 않았고, 그래서 학번은 아예 바꿀 수 없게
 * 잠겨 있었다. 잠금을 푸는 대가로 범용 변경 이력 표를 세우면서(ssccops#161 → ssccops#162)
 * 이 열거형에도 자리가 생겼다. 통합 감사 로그(#8)는 여전히 사전에 없다.
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
    ROLE,

    /*
     * 회원 정보 변경 (mbr_chg_hstry · #226). 아홉 항목이 한 값으로 묶여 있는 것은 화면의
     * 필터가 "회원 정보 수정 이력을 볼 것인가"를 체크박스 하나로 묻기 때문이다 — 항목별로
     * 가르면 필터가 아홉 칸이 되고, 그것은 이력을 보는 사람이 원한 것이 아니다. 어느 항목이
     * 바뀌었는지는 각 행의 changeField가 답한다.
     */
    PROFILE
}
