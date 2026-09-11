package org.sscc.ssccopsserver.domain.member.service;

import org.sscc.ssccopsserver.domain.member.dto.MemberBulkChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkStatusChangeRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 여러 회원의 등급·상태를 한 번에 바꾸는 서비스 (#338).
 *
 * ══ 왜 MemberChangeService에 메서드를 더하지 않는가 ═════════════════
 * 트랜잭션 경계가 정반대이기 때문이다. MemberChangeServiceImpl의 두 메서드는 @Transactional이고
 * **그래야 한다** — 등급 갱신과 이력 INSERT가 한 건이라 갈라지면 근거 없는 승급이 남는다.
 * 반대로 여기는 **트랜잭션이 없어야 한다** — 한 건의 실패가 앞선 성공을 되돌리면 안 되기 때문이다.
 *
 * 두 규칙이 한 클래스에 살면 언젠가 누군가 클래스에 @Transactional을 걸거나, 일괄 메서드가 같은
 * 클래스의 changeGrade를 자기 호출(this.changeGrade)로 부르게 된다. 자기 호출은 프록시를 거치지
 * 않아 **애노테이션은 그대로 남고 경계만 조용히 사라진다** — CSV 이관이 MemberImportRowExecutor를
 * 별도 빈으로 떼어낸 것과 같은 이유이고, 그쪽 주석에 그 사고가 자세히 적혀 있다.
 *
 * ══ 한 명짜리 로직을 재사용하는 것은 선택이 아니라 조건이다 ═══════════
 * 일괄 수정은 되돌리기 어렵다. 되돌릴 수 있게 하는 것은 mbr_grd_hstry·mbr_stts_hstry에 남는
 * 줄뿐이고, 그 줄을 남기는 코드가 두 벌이 되면 **한쪽만 이력을 남기는 날이 반드시 온다.**
 * 그래서 이 서비스는 검증도 코드 해석도 이력 기록도 직접 하지 않는다 — 대상 목록을 풀어
 * MemberChangeService를 회원마다 한 번씩 부르고, 그 결과와 예외를 행별 결과로 옮길 뿐이다.
 */
public interface MemberBulkChangeService {

    /*
     * 여러 회원의 등급을 같은 값으로 바꾼다 (POST /v1/members/grade-changes).
     *
     * **회원마다 트랜잭션이 따로다.** 한 명이 실패해도 앞서 바뀐 회원은 그대로 남고, 실패한
     * 회원은 등급도 이력도 남기지 않는다(자기 트랜잭션만 되돌아간다).
     *
     * 요청 전체가 거절되는 자리는 요청 자체가 성립하지 않을 때뿐이다 — 대상이 비었거나 상한
     * (100명)을 넘으면 400 VALIDATION_FAILED이고 이때는 한 명도 바뀌지 않는다. 권한은 한 명짜리와
     * 같은 MEMBER_MANAGE다.
     *
     * 회원별 결과는 CHANGED·SKIPPED·FAILED 셋이며 그 구별의 근거는 MemberBulkChangeStatus 주석에 있다.
     */
    MemberBulkChangeResponse changeGrades(
            MemberBulkGradeChangeRequest request, MemberEntity changer);

    /*
     * 여러 회원의 상태를 같은 값으로 바꾼다 (POST /v1/members/status-changes).
     * 트랜잭션 경계와 행별 결과는 changeGrades와 같다.
     *
     * 탈퇴·제명으로 바꿔도 역할·담당 업무를 정리하지 않는 것은 한 명짜리와 같으며, 남아 있는
     * 것들은 **회원별 행의 warnings**로 실린다 (MemberBulkChangeRow 주석 — 합치지 않는 이유).
     */
    MemberBulkChangeResponse changeStatuses(
            MemberBulkStatusChangeRequest request, MemberEntity changer);
}
