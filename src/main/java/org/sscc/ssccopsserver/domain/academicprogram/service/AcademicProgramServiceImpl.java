package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramServiceImpl implements AcademicProgramService {

    private final AcademicProgramRepository academicProgramRepository;
    private final CurriculumItemRepository curriculumItemRepository;

    /*
     * 단건 조회(#131). AcademicProgram 행은 이제 폼 응답 승인 이관(#148)이 만든다 — 이 서비스는
     * 그 결과를 읽기만 한다(readOnly 트랜잭션, 어떤 상태도 바꾸지 않는다).
     */
    @Override
    public AcademicProgramDetailResponse getAcademicProgram(
            Long academicProgramId, MemberEntity viewer) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        long curriculumItemCount =
                curriculumItemRepository.countByAcademicProgramId(academicProgramId);
        return AcademicProgramDetailResponse.of(academicProgram, (int) curriculumItemCount, viewer);
    }

    private AcademicProgramEntity findAcademicProgram(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }

    /*
     * 목록 조회. 쿼리는 목록 · 필터 건수 · 전체 건수 셋으로 work 도메인의 목록 조회(OPS-020)와
     * 같은 수다(설계 결정 #3).
     */
    @Override
    public AcademicProgramSearchResponse searchAcademicPrograms(
            AcademicProgramCondition condition, MemberEntity viewer) {
        AcademicProgramSearchQuery query = condition.toQuery(viewer);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<AcademicProgramEntity> fetched = academicProgramRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<AcademicProgramEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        List<AcademicProgramSummaryResponse> academicPrograms =
                rows.stream()
                        .map(program -> AcademicProgramSummaryResponse.of(program, viewer))
                        .toList();

        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        nextCursorOf(query, rows, hasNext),
                        hasNext,
                        academicProgramRepository.countMatching(query),
                        academicProgramRepository.count());
        return new AcademicProgramSearchResponse(academicPrograms, page);
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(
            AcademicProgramSearchQuery query, List<AcademicProgramEntity> rows, boolean hasNext) {
        return hasNext
                ? AcademicProgramCursor.of(query.sort(), rows.get(rows.size() - 1)).encode()
                : null;
    }
}
