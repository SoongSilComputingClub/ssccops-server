package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.MemberHistorySource;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeHistoryResponse;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberHistoryServiceImpl implements MemberHistoryService {

    private final MemberRepository memberRepository;
    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final MemberStatusHistoryRepository memberStatusHistoryRepository;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    // 역할 사건의 발생 시각을 날짜에서 만들 때 쓰는 시간대 (AP-12 — 서비스 표준 시간대)
    private final Clock clock;

    /*
     * 세 출처를 합쳐 발생 시각 역순으로 내린다 (#82).
     *
     * **페이징을 두지 않는다.** 세 테이블을 합치므로 단일 컬럼 커서가 성립하지 않고,
     * (occurredAt, historyType, id) 복합 커서를 인코딩하려면 세 출처의 식별자 공간이 서로
     * 다르다는 사실까지 커서에 담아야 한다 — 그렇게 만든 커서는 출처를 하나 더 늘리는 순간
     * 형식이 깨진다. 한 회원의 이력은 가입 이력 두 건에 등급·상태 변경 몇 건, 역할 임기
     * 몇 건이 전부라 많아야 수십 건이고, 화면은 그것을 한 번 받아 그 자리에서 종류별로 걸러
     * 본다. 폼 응답 목록(#37)도 같은 이유로 페이징을 미뤘다. 그래서 응답은 배열이고
     * PageResponse 봉투를 붙이지 않는다 — 실제로 느려지면 그때 커서를 넣되 화면과 함께 바꾼다.
     *
     * **없는 회원은 404다.** 이력이 비었는지와 회원이 없는지는 다른 사실이라, 존재 검사 없이
     * 빈 배열을 내리면 오타 난 식별자가 "이력이 하나도 없는 회원"으로 보인다. 반대로 회원은
     * 있는데 이력이 없으면 그때는 빈 배열이 정답이다 (#78 이전에 가입한 회원이 그렇다).
     *
     * 쿼리는 요청한 출처의 수만큼이다 — 읽지 않기로 한 출처에는 질의를 보내지 않는다.
     * 변경자(chnrg_mbr_id)는 이력 질의의 EntityGraph가 함께 끌어오므로 이력 건수와 무관하게
     * 늘지 않는다(N+1 없음).
     */
    @Override
    @Transactional(readOnly = true)
    public List<MemberChangeHistoryResponse> getHistories(
            Long memberId, Set<MemberHistorySource> sources) {

        if (!memberRepository.existsById(memberId)) {
            throw new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND);
        }

        return MemberChangeHistoryAssembler.merge(
                sources.contains(MemberHistorySource.GRADE)
                        ? memberGradeHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                                memberId)
                        : List.of(),
                sources.contains(MemberHistorySource.STATUS)
                        ? memberStatusHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                                memberId)
                        : List.of(),
                sources.contains(MemberHistorySource.ROLE)
                        ? memberRoleAssignmentRepository.findAllByMemberId(memberId)
                        : List.of(),
                clock.getZone());
    }
}
