package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;
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

    /*
     * 운영 대시보드(OPS-038) '내 업무 목록'. owner가 담당자인 하위 업무 전량을 마감 오름차순
     * (AGG-04)으로 돌려준다. 완료 건도 포함한다 — 전체/마감임박/지연 필터는 화면이 이 목록
     * 위에서 다시 나눈다.
     */
    List<SubWorkSummaryResponse> findMyTasks(MemberEntity owner);

    /*
     * 운영 대시보드(OPS-038) '다가오는 마감'. 조회 시점 기준 ±5일 범위에 마감이 있는 하위
     * 업무를 마감 오름차순으로 돌려준다(이슈#60). 완료 건은 빠진다.
     */
    List<SubWorkSummaryResponse> findUpcomingDeadlines();

    /*
     * 운영 통합(OPS-001)의 하위 업무 전량 목록. 목록 조회(OPS-008)와 같은 행 요약이지만
     * 좌측 목록과 우측 트리를 한 화면이 함께 그리므로 커서 페이징 없이 전량을 돌려준다.
     * 정렬은 OPS-008 기본값과 같다(마감 오름차순, 마감 없는 건은 뒤).
     */
    List<SubWorkSummaryResponse> listSubWorks();

    /*
     * 이 회원이 담당 중인(완료되지 않은) 하위 업무의 건수 (#78).
     *
     * 회원 도메인이 탈퇴·제명 전이의 경고를 만들 때 쓴다. 회원 도메인은 운영 Repository를
     * 직접 호출할 수 없으므로(AR-07·LY-10) 진입점을 여기 하나로 둔다 — 운영 도메인이 회원
     * 정보를 MemberService로만 얻는 것과 같은 규칙을 반대 방향으로 지킨다.
     *
     * **아무것도 바꾸지 않는다.** 담당 업무를 자동으로 회수하거나 재배정하는 동작은 운영 규칙이
     * 필요한 판단이라 범위 밖이고, 이 메서드는 화면이 사람에게 알릴 숫자만 돌려준다.
     */
    long countOngoingByOwner(Long ownerId);
}
