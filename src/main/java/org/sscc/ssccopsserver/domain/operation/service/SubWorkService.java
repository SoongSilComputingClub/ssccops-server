package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteResponse;

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
     *
     * viewer는 인증 주체이며 응답을 좁히는 데 쓰지 않는다 — 상세는 누가 보든 같은 하위 업무를
     * 돌려준다. 이 회원에 따라 달라지는 것은 '나'가 들어간 값뿐이다: 승인·반려 버튼을 그릴지
     * (canApprove·canReject)와 내가 이번 회차에 던진 표(myVote) (#58).
     */
    SubWorkDetailResponse getSubWork(Long subWorkId, MemberEntity viewer);

    /*
     * 하위 업무 기본 정보를 수정한다 (OPS-030). oper(제목·기간·우선순위·담당자)와 sub_work
     * (제목·업무 내용·완료 기준 내용·외부 링크·마감 일시)를 한 트랜잭션에서 함께 바꾼다.
     *
     * workId(상위 업무)·subWorkTypeId(유형)·workStatus·approvalStatus는 바꾸지 않는다 —
     * 요청 DTO에 그 필드들이 아예 없다(SubWorkUpdateRequest 주석). viewer는 응답을 좁히는 데
     * 쓴다 — 조회(getSubWork)와 같은 SubWorkDetailResponse를 돌려주므로 canApprove·canReject·
     * myVote가 함께 실리고, 그 값들은 '누가 보는가'에 따라 갈린다.
     */
    SubWorkDetailResponse updateSubWork(
            Long subWorkId, SubWorkUpdateRequest request, MemberEntity viewer);

    /*
     * 조건에 맞는 하위 업무를 상위 업무를 가로질러 조회한다 (OPS-008 · REQ-025).
     * 소프트 삭제된 건은 목록에도 건수에도 들어가지 않으며, 결과가 없으면 빈 목록이다(404가 아니다).
     *
     * 지연·마감임박 판정은 조회 시점을 기준으로 하며 어떤 상태도 바꾸지 않는다 (AP-07).
     */
    SubWorkSearchResponse searchSubWorks(SubWorkSearchCondition condition);

    /*
     * 하위 업무의 상태를 전이시킨다 (OPS-010). 전이표(TR-01~TR-04)에 있는 조합만 통과하며,
     * 업무 상태·승인 상태 변경과 이력·승인/반려 기록이 한 트랜잭션에서 일어난다.
     * performer는 인증 주체이며 요청 본문이 아니라 토큰에서 온다 (LY-05).
     */
    SubWorkTransitionResponse transitionSubWork(
            Long subWorkId, SubWorkTransitionRequest request, MemberEntity performer);

    /*
     * 정족수 승인 투표 (OPS-015 · REQ-014 · #47). 사전에 운영진 권한을 가진 회원이면 누구나
     * 찬성·반대를 던질 수 있고, 승인자만의 권한이 아니다.
     *
     * 업무 상태·승인 상태를 바꾸지 않는다 — 정족수를 채워도 승인자가 최종 승인(TR-03)을
     * 하지 않으면 완료되지 않는다(POL-007 O-03). 같은 회원이 다시 던지면 표가 늘지 않고 바뀐다.
     * voter는 인증 주체이며 요청 본문이 아니라 토큰에서 온다 (LY-05).
     */
    SubWorkVoteResponse voteOnSubWork(
            Long subWorkId, SubWorkVoteRequest request, MemberEntity voter);

    /*
     * 완료 체크리스트 항목 하나를 체크·해제한다 (OPS-013 · REQ-021). 완료 승인 전이(TR-03)의
     * 판정 근거를 바꾸는 유일한 경로다.
     *
     * 업무 상태·승인 상태를 바꾸지 않고 상위 업무 진행률도 재집계하지 않는다 — 그 값은
     * 하위 업무 완료 건수에서 나오므로 체크로 변하지 않는다. 소프트 삭제된 하위 업무와
     * 경로의 하위 업무에 속하지 않는 항목은 모두 404다.
     */
    SubWorkChecklistItemUpdateResponse updateChecklistItem(
            Long subWorkId,
            Long checklistItemId,
            SubWorkChecklistItemUpdateRequest request,
            MemberEntity performer);
}
