package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;

public interface SubWorkService {

    /*
     * 하위 업무를 등록한다 (OPS-007). oper·sub_work·완료 체크리스트가 한 트랜잭션에서
     * 생성되고, 상위 업무의 진행률도 같은 트랜잭션에서 다시 집계된다.
     * registrant는 인증 주체(등록자)이며 요청 본문이 아니라 토큰에서 온다 (LY-05).
     */
    SubWorkCreateResponse createSubWork(SubWorkCreateRequest request, MemberEntity registrant);

    /*
     * 하위 업무 1건을 완료 체크리스트와 함께 조회한다 (OPS-009). 소프트 삭제된 건은
     * 존재하지 않는 것으로 보고 SUB_WORK_NOT_FOUND(404)를 던진다.
     */
    SubWorkDetailResponse getSubWork(Long subWorkId);

    /*
     * 하위 업무의 상태를 전이시킨다 (OPS-010). 전이표(TR-01~TR-04)에 있는 조합만 통과하며,
     * 업무 상태·승인 상태 변경과 이력·승인/반려 기록이 한 트랜잭션에서 일어난다.
     * performer는 인증 주체이며 요청 본문이 아니라 토큰에서 온다 (LY-05).
     */
    SubWorkTransitionResponse transitionSubWork(
            Long subWorkId, SubWorkTransitionRequest request, MemberEntity performer);
}
