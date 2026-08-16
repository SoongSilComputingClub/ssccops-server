package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkUpdateRequest;

public interface WorkService {

    /*
     * 업무를 등록한다 (OPS-002). oper와 work 두 행이 한 트랜잭션에서 생성된다.
     * registrant는 인증 주체(등록자)이며 요청 본문이 아니라 토큰에서 온다 (LY-05).
     */
    WorkCreateResponse createWork(WorkCreateRequest request, MemberEntity registrant);

    /*
     * 업무 한 건과 그 하위 업무 목록을 함께 조회한다 (OPS-003).
     * 소프트 삭제된 업무는 없는 것으로 보고 WORK_NOT_FOUND를 던진다.
     */
    WorkDetailResponse getWork(Long workId);

    /*
     * 업무 기본 정보를 수정한다 (OPS-004). workStatus는 이 경로로 바꿀 수 없다(POL-003) —
     * 요청 DTO 자체에 그 필드가 없다. 소프트 삭제된 업무는 없는 것으로 보고 WORK_NOT_FOUND를
     * 던진다. 응답은 조회(getWork)와 같은 WorkDetailResponse다.
     */
    WorkDetailResponse updateWork(Long workId, WorkUpdateRequest request);

    /*
     * 상위 업무 목록을 조건에 따라 조회한다 (OPS-020). '운영 통합 › 업무' 화면의 카드 그리드가
     * 이 결과로 채워지며, 소프트 삭제된 업무는 목록에도 건수에도 없다 (AGG-03).
     */
    WorkSearchResponse searchWorks(WorkSearchCondition condition);

    /*
     * 운영 통합(OPS-001)의 업무 전량 목록. 목록 조회(OPS-020)와 같은 카드 요약이지만
     * 화면이 목록과 트리를 한 번에 그리므로 커서 페이징 없이 전량을 돌려준다.
     * 정렬은 OPS-020의 기본값과 같은 등록 최신순이다.
     */
    List<WorkListItemResponse> listWorks();
}
