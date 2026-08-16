package org.sscc.ssccopsserver.domain.member.code;

/*
 * CSV 이관 **실행**의 행별 결과 (#85).
 *
 * 검증의 MemberImportRowStatus(OK·ERROR·DUPLICATE)와 값이 다른 것은 답하는 물음이 다르기 때문이다 —
 * 그쪽은 "넣을 수 있는가"이고 이쪽은 "넣었는가"다. 검증에서 OK였던 행도 동시 요청 때문에 실행에서
 * 건너뛰어질 수 있으므로 두 어휘를 한 enum으로 합치면 어느 단계의 판정인지 화면이 구별하지 못한다.
 *
 * 세 값은 서로 겹치지 않으며 합이 totalCount다 (요약의 세 버킷에 그대로 대응한다).
 */
public enum MemberImportExecutionStatus {

    /** mbr에 새로 들어간 행. mbrId가 채워진다 */
    CREATED,

    /** 중복이라 건너뛴 행. **덮어쓰지 않는다** (BR-M40) */
    SKIPPED,

    /** 값이 규칙을 어겼거나 저장 도중 실패한 행. 이 행의 실패는 다른 행을 되돌리지 않는다 */
    FAILED
}
