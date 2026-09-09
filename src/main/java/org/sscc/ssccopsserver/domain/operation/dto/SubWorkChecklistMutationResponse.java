package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

/*
 * 완료 체크리스트 항목 추가·수정·삭제 응답 (#307). 세 엔드포인트가 같은 형태를 쓴다 —
 * 셋 다 목록을 바꾸므로 화면이 다시 그려야 하는 것도 같다.
 *
 * 체크·해제(SubWorkChecklistItemUpdateResponse)와 달리 **목록 전체를 함께 싣는다.** 체크는
 * 항목 하나의 값만 뒤집지만 추가·삭제는 목록의 구성 자체가 달라져, 갱신된 항목만 내려주면
 * 화면이 상세 조회를 한 번 더 불러야 한다. 순서도 서버가 정하므로(추가는 끝에) 클라이언트가
 * 배열에 끼워 넣을 자리를 스스로 계산하게 두지 않는다.
 *
 * item은 방금 다룬 항목이다. 삭제 응답에서는 **이미 없는 항목의 마지막 모습**이며, 화면이
 * "'{문구}' 항목을 삭제했습니다" 같은 안내를 그릴 때 쓴다 — 지운 뒤 다시 물을 곳이 없다.
 *
 * isChecklistItemEditable은 이 응답 시점에 항목을 더 고칠 수 있는지다. 판정은 도메인 하나에만
 * 있고(SubWorkEntity.isChecklistItemEditable) 화면은 상태로 다시 추론하지 않는다 —
 * 상세 조회(SubWorkDetailResponse)가 내려주는 같은 이름의 필드와 같은 값이다. 항목마다의
 * isDeletable은 여기에 체크 여부까지 합친 값이다.
 *
 * 업무 상태·승인 상태는 담지 않는다. 항목 편집은 상태 전이가 아니라 스테퍼를 움직이지 않으며,
 * 상태가 바뀌는 것처럼 보이는 응답을 내려 화면이 오해하게 만들지 않는다 (OPS-013과 같은 판단).
 */
public record SubWorkChecklistMutationResponse(
        Long subWorkId,
        SubWorkChecklistItemResponse item,
        List<SubWorkChecklistItemResponse> checklist,
        SubWorkChecklistSummaryResponse checklistSummary,
        boolean isChecklistItemEditable) {

    public static SubWorkChecklistMutationResponse of(
            SubWorkEntity subWork,
            SubWorkChecklistItemEntity item,
            List<SubWorkChecklistItemEntity> checklist) {
        boolean itemEditable = subWork.isChecklistItemEditable();
        List<SubWorkChecklistItemResponse> items =
                checklist.stream()
                        .map(row -> SubWorkChecklistItemResponse.from(row, itemEditable))
                        .toList();
        return new SubWorkChecklistMutationResponse(
                subWork.getId(),
                SubWorkChecklistItemResponse.from(item, itemEditable),
                items,
                SubWorkChecklistSummaryResponse.from(items),
                itemEditable);
    }
}
