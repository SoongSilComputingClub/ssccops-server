package org.sscc.ssccopsserver.domain.member.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface MemberRepository extends JpaRepository<MemberEntity, Long> {

    Optional<MemberEntity> findByAuthUserId(UUID authUserId);

    boolean existsByAuthUserId(UUID authUserId);

    /*
     * 학번 선점 여부. 이관 회원(auth_user_id가 NULL인 행)도 포함해서 본다 — 학번이 일치한다는
     * 것만으로 그 행에 연결해 주면 학번만 아는 사람이 남의 계정을 가로챌 수 있어, 지금은
     * 자동 연결 대신 거절한다 (#21).
     */
    boolean existsByStudentNumber(String studentNumber);

    /*
     * 제외할 상태 코드를 파라미터로 받는다. 어떤 상태를 배정에서 뺄지는 조회 조건이 아니라
     * 회원 도메인의 정책이라, 저장소에 박아 두지 않고 서비스가 넘기게 했다.
     * 파생 쿼리로 쓰면 메서드명에 상태 프로퍼티 경로가 그대로 드러나 길어져 JPQL로 적는다.
     */
    @Query(
            "select m from MemberEntity m where m.id = :memberId and m.membershipStatus.code not in"
                    + " :excludedStatusCodes")
    Optional<MemberEntity> findAssignableById(
            @Param("memberId") Long memberId,
            @Param("excludedStatusCodes") Collection<String> excludedStatusCodes);
}
