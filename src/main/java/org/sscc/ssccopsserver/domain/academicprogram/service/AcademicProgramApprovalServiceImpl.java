package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramApprovalRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 승인 이력 조회의 구현 (#139).
 *
 * 이 클래스가 새로 만드는 규칙은 없다 — 열람 자격은 AcademicProgramOwnershipPolicy
 * .requireLeaderOrManager(#138)가, 커서·페이지 봉투 조립은 SessionReviewServiceImpl과 같은
 * 골격이 갖는다. 소유권과 관리권한의 OR을 여기서 다시 적지 않는 것이 중요한데, 복제하면 신청자
 * 조회(#138)와 이 조회가 같은 문장에 대해 다른 답을 낼 여지가 생긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramApprovalServiceImpl implements AcademicProgramApprovalService {

    private final AcademicProgramRepository academicProgramRepository;
    private final AcademicProgramApprovalRepository academicProgramApprovalRepository;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;

    /*
     * 검사 순서는 넓은 것부터다 — 활동(404) → 자격(403) → 쿼리 해석(400). 자격을 쿼리 해석보다
     * 먼저 보는 것은, 파라미터를 바꿔 가며 부르는 것만으로 남의 활동에 대해 무언가를 알아낼 수
     * 있는 자리를 만들지 않기 위해서다(AcademicProgramRecruitmentServiceImpl과 같은 태도).
     */
    @Override
    public AcademicProgramApprovalSearchResponse getApprovals(
            Long academicProgramId,
            AcademicProgramApprovalCondition condition,
            MemberEntity requester) {

        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeaderOrManager(academicProgram, requester);

        AcademicProgramApprovalSearchQuery query = condition.toQuery(academicProgramId);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<AcademicProgramApprovalEntity> fetched =
                academicProgramApprovalRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<AcademicProgramApprovalEntity> rows =
                hasNext ? fetched.subList(0, query.size()) : fetched;

        PageResponse page =
                new PageResponse(
                        query.size(),
                        AcademicProgramApprovalCondition.SORT,
                        nextCursorOf(rows, hasNext),
                        hasNext,
                        academicProgramApprovalRepository.countMatching(query),
                        academicProgramApprovalRepository.countByAcademicProgramId(
                                academicProgramId));

        return new AcademicProgramApprovalSearchResponse(
                rows.stream().map(AcademicProgramApprovalResponse::of).toList(), page);
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(List<AcademicProgramApprovalEntity> rows, boolean hasNext) {
        return hasNext
                ? AcademicProgramApprovalCursor.of(rows.get(rows.size() - 1)).encode()
                : null;
    }

    private AcademicProgramEntity findAcademicProgram(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }
}
