package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistHistoryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemSaveRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistMutationResponse;
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
    SubWorkSearchResponse searchSubWorks(SubWorkSearchCondition condition, MemberEntity viewer);

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
     * 완료 점검 항목을 새로 더한다 (#307). 순서는 지금 가장 큰 sort_seq + 1이라 목록 끝에
     * 붙는다. 새 항목은 언제나 미완료로 시작하므로, 완료 직전이던 하위 업무에 항목을 더하면
     * 완료 조건이 다시 미충족이 된다 — 그것이 이 API의 뜻이다.
     *
     * 기획·진행 단계에서만 가능하다(SubWorkEntity.requireChecklistItemEditable). 유형
     * (sub_work_type)의 원본 목록은 건드리지 않는다 — 여기서 더한 항목은 이 하위 업무의 것이고
     * 다음에 같은 유형으로 등록되는 건에는 나타나지 않는다 (POL-005 · #43 소급 금지).
     *
     * 더한 사실은 sub_work_chck_list_hstry에 남는다.
     */
    SubWorkChecklistMutationResponse addChecklistItem(
            Long subWorkId, SubWorkChecklistItemSaveRequest request, MemberEntity performer);

    /*
     * 완료 점검 항목의 문구를 고친다 (#307). 체크 상태는 그대로 둔다 — 문구를 다듬는 것과
     * 그 항목을 해낸 것은 다른 사실이다. 기획·진행 단계에서만 가능하며, 바뀐 사실은
     * 이전·이후 문구와 함께 sub_work_chck_list_hstry에 남는다.
     */
    SubWorkChecklistMutationResponse updateChecklistItemArticle(
            Long subWorkId,
            Long checklistItemId,
            SubWorkChecklistItemSaveRequest request,
            MemberEntity performer);

    /*
     * 완료 점검 항목을 지운다 (#307). 두 겹으로 막는다 — 기획·진행 단계여야 하고
     * (requireChecklistItemEditable), **체크되지 않은 항목이어야 한다.** 체크된 항목은
     * CHECKLIST_ITEM_COMPLETED(409)로 거절한다.
     *
     * 체크된 항목을 막는 것은 "해당 없음으로 지우기"와 "안 하고 지우기"가 화면에서 구별되지
     * 않기 때문이다 — 체크 안 된 것만 지울 수 있으면 지운다는 행위 자체가 "이번 건엔 해당
     * 없다"는 선언이 된다. 체크된 항목은 이미 한 일이라 지울 이유가 없다 (ssccops#255 결정).
     *
     * **행은 하드로 지운다.** 지워진 항목의 문구·시점·수행자는 sub_work_chck_list_hstry에
     * 남으므로 흔적이 사라지지 않는다. 남은 항목의 sort_seq는 다시 매기지 않는다.
     */
    SubWorkChecklistMutationResponse deleteChecklistItem(
            Long subWorkId, Long checklistItemId, MemberEntity performer);

    /*
     * 완료 점검 항목의 변경 이력 (#307). 더하고 고치고 지운 것이 일어난 순서대로 나온다.
     * 체크·해제는 여기 없다 — 진척 기록이라 완료 조건이 달라진 자리를 묻어 버린다.
     *
     * 조회이므로 담당자 여부를 보지 않는다 — 하위 업무를 볼 수 있으면(WORK_READ) 그 완료
     * 조건이 어떻게 달라졌는지도 볼 수 있다. 소프트 삭제된 건은 404다.
     */
    List<SubWorkChecklistHistoryResponse> getChecklistHistory(Long subWorkId);

    /*
     * 운영 대시보드(OPS-038) '내 업무 목록'. owner가 담당자인 하위 업무 전량을 마감 오름차순
     * (AGG-04)으로 돌려준다. 완료 건도 포함한다 — 전체/마감임박/지연 필터는 화면이 이 목록
     * 위에서 다시 나눈다.
     */
    List<SubWorkSummaryResponse> findMyTasks(MemberEntity owner);

    /*
     * 운영 대시보드(OPS-038) '다가오는 마감'. 조회 시점 기준 ±5일 범위에 마감이 있는 하위
     * 업무를 마감 오름차순으로 돌려준다(이슈#60). 완료 건은 빠진다.
     *
     * ownerId가 null이면 전체(WORK_MANAGE 보유자용), 값이 있으면 그 담당자의 것만 본다
     * (국원 등 WORK_MANAGE 없는 조회자용, #101) — 호출부(DashboardServiceImpl)가 조회자의
     * 권한을 보고 어느 쪽을 줄지 정한다.
     */
    List<SubWorkSummaryResponse> findUpcomingDeadlines(Long ownerId);

    /*
     * 운영 통합(OPS-001)의 하위 업무 전량 목록. 목록 조회(OPS-008)와 같은 행 요약이지만
     * 좌측 목록과 우측 트리를 한 화면이 함께 그리므로 커서 페이징 없이 전량을 돌려준다.
     * 정렬은 OPS-008 기본값과 같다(마감 오름차순, 마감 없는 건은 뒤).
     */
    List<SubWorkSummaryResponse> listSubWorks();

    /*
     * 담당 중인 하위 업무 건수(#78)는 **이 인터페이스에 없다** (ssccops#242).
     *
     * 그 값을 묻는 곳은 회원 도메인 하나이고(탈퇴·제명 경고), 여기 두면 회원이 운영을 import
     * 해야 해서 member → operation → member 순환이 된다. 그래서 선언은 묻는 쪽에 있고
     * (MemberSubWorkLoadProvider) 운영 도메인의 SubWorkOwnerLoadProvider가 그것을 구현한다 —
     * 운영 도메인 안에서 쓰는 코드는 없으므로 이 인터페이스가 들고 있을 이유도 없다.
     */

    /*
     * 하위 업무를 소프트 삭제한다 (#125). 자기 operation만 del_dt를 채운다 — 상위 업무·다른
     * 하위 업무는 건드리지 않는다. 상태와 무관하게 항상 허용하며, SUB_WORK_DELETE 보유
     * 여부만으로 게이트가 걸린다 — 담당자 본인 여부는 보지 않는다.
     *
     * 대상이 아예 없으면 SUB_WORK_NOT_FOUND(404), 있지만 이미 삭제됐으면 ALREADY_DELETED(409)다.
     */
    void deleteSubWork(Long subWorkId);
}
