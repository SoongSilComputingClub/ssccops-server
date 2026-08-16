package org.sscc.ssccopsserver.domain.member.service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.dto.MemberChangeHistoryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;

/*
 * 세 출처의 이력을 하나의 타임라인으로 합치는 **유일한 구현** (#82).
 *
 * 회원 상세의 '최근 3건'(#76)과 통합 이력 조회(#82)가 이 클래스를 함께 쓴다. 두 벌이 되면
 * 상세 카드와 이력 화면이 같은 데이터를 다르게 표시하게 되는데, 그 어긋남은 터지지 않고
 * 조용히 틀리는 종류라 발견이 늦다. 상세는 여기서 받은 목록을 앞에서 세 건 자를 뿐이고
 * 자르는 개수만 그쪽이 정한다.
 *
 * **빈으로 두지 않고 정적 유틸로 두는 것은 의존이 없기 때문이다.** 필요한 것은 인자로 받는
 * 엔티티 목록과 시간대뿐이라 주입할 협력자가 없고, 빈으로 만들면 MemberServiceImpl의
 * 생성자에 인자가 하나 더 붙어 이 서비스를 직접 조립하는 테스트 아홉 곳이 함께 바뀐다
 * (AcademicProfilePolicy와 같은 배치).
 *
 * 조회는 하지 않는다 — 어느 출처를 읽을지(type 필터)와 몇 건을 읽을지는 부르는 쪽이 정하고,
 * 여기는 손에 든 행을 모양과 순서로 굳히는 일만 한다.
 */
public final class MemberChangeHistoryAssembler {

    /*
     * 기록 시각 역순. 같은 시각의 두 이력(한 트랜잭션에서 등급과 상태를 함께 바꾼 경우)은
     * 순서를 못 박을 근거가 없으므로 종류로 끊는다 — 근거 없는 흔들림보다 임의라도 고정된
     * 순서가 낫다. 종류 비교는 MemberChangeType의 선언 순서를 쓴다.
     *
     * 이 비교로도 동률인 행(같은 날 부여된 역할 둘 등)은 정렬이 안정 정렬이라 입력 순서를
     * 지킨다. 세 출처를 각각 식별자 내림차순으로 받아 넣으므로 그 순서도 결정적이다.
     */
    private static final Comparator<MemberChangeHistoryResponse> NEWEST_FIRST =
            Comparator.comparing(MemberChangeHistoryResponse::createdAt, Comparator.reverseOrder())
                    .thenComparing(MemberChangeHistoryResponse::changeType);

    private MemberChangeHistoryAssembler() {}

    /*
     * 세 출처를 합쳐 발생 시각 역순으로 내린다. 읽지 않기로 한 출처는 빈 목록으로 넘긴다.
     *
     * **하나의 SQL로 UNION 하지 않는다.** 컬럼 이름도 개수도 다른 세 테이블을 질의 하나로
     * 맞추려면 상수 컬럼과 캐스팅이 늘어서고, 그렇게 만든 질의는 어느 출처가 무엇을 담는지
     * 읽어 낼 수 없게 된다. 세 리포지토리에서 각각 받아 여기서 합치는 편이 읽기도 테스트하기도
     * 쉽다(이슈 #82의 권고).
     *
     * 역할 배정 한 행은 **두 줄**이 될 수 있다 — 부여(role_bgng_ymd)와, 종료일이 채워져 있다면
     * 종료(role_end_ymd). 종료는 삭제가 아니라 종료일을 채우는 조작이므로(#81) 끝난 임기의
     * 행에는 서로 다른 시각의 사건이 둘 들어 있다.
     */
    public static List<MemberChangeHistoryResponse> merge(
            List<MemberGradeHistoryEntity> gradeHistories,
            List<MemberStatusHistoryEntity> statusHistories,
            List<MemberRoleAssignmentEntity> roleAssignments,
            ZoneId zone) {

        List<MemberChangeHistoryResponse> merged = new ArrayList<>();
        for (MemberGradeHistoryEntity history : gradeHistories) {
            merged.add(MemberChangeHistoryResponse.from(history));
        }
        for (MemberStatusHistoryEntity history : statusHistories) {
            merged.add(MemberChangeHistoryResponse.from(history));
        }
        for (MemberRoleAssignmentEntity assignment : roleAssignments) {
            merged.add(MemberChangeHistoryResponse.roleAssigned(assignment, zone));
            if (assignment.getRoleEndDate() != null) {
                merged.add(MemberChangeHistoryResponse.roleEnded(assignment, zone));
            }
        }

        merged.sort(NEWEST_FIRST);
        return List.copyOf(merged);
    }
}
