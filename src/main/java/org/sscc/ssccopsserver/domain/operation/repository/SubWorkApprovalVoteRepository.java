package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkApprovalVoteEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

public interface SubWorkApprovalVoteRepository
        extends JpaRepository<SubWorkApprovalVoteEntity, Long> {

    /*
     * 이번 회차에 이 회원이 이미 던진 표. 있으면 새 행을 만들지 않고 값을 바꾼다 (1인 1표).
     * 회차가 키에 들어가므로 반려 후 다시 올라온 건에서는 비어 있는 것이 정상이다.
     */
    Optional<SubWorkApprovalVoteEntity> findBySubWorkAndApprovalSequenceAndVoter(
            SubWorkEntity subWork, int approvalSequence, MemberEntity voter);

    /*
     * 이번 회차의 찬성 수. 정족수 판정(TR-03)과 승인함 진행바가 함께 쓰는 값이다.
     * 반대·기권은 세지 않는다 — 정족수는 찬성 수로만 정의된다 (POL-007 O-03).
     */
    long countBySubWorkAndApprovalSequenceAndAgreedIsTrue(
            SubWorkEntity subWork, int approvalSequence);

    /*
     * 승인함(OPS-017)의 정족수 진행바용. 카드마다 세면 N+1이라 목록 전체를 한 번에 집계한다.
     * 회차별로 나눠 담아 호출부가 이번 회차의 값만 고른다.
     */
    @Query(
            "select s.id as subWorkId,"
                    + " v.approvalSequence as approvalSequence,"
                    + " count(v) as agreedCount"
                    + " from SubWorkApprovalVoteEntity v"
                    + " join v.subWork s"
                    + " where s.id in :subWorkIds and v.agreed = true"
                    + " group by s.id, v.approvalSequence")
    List<SubWorkAgreedVoteCount> findAgreedCountsBySubWorkIds(
            @Param("subWorkIds") Collection<Long> subWorkIds);

    /*
     * 승인함을 보고 있는 회원이 이미 던진 표. 카드의 찬성·반대 버튼 선택 상태를 그리는 데 쓴다.
     * 회차 구분 없이 가져와 호출부가 이번 회차의 것만 고른다 — 회차가 하위 업무마다 다르다.
     */
    List<SubWorkApprovalVoteEntity> findBySubWorkIdInAndVoterId(
            Collection<Long> subWorkIds, Long voterId);
}
