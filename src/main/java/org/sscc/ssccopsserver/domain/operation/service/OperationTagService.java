package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.operation.dto.OperationTagAssignmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationTagResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationTagSaveRequest;

/*
 * 운영 태그 관리·지정 (#637 · ssccops#576). 폼 라벨(FormLabelService)과 같은 모양이다.
 *
 * 업무·하위 업무·회의 서비스와 나눈 것은 태그가 운영 건과 수명이 다른 자원이기 때문이다 — 태그는
 * 운영 건이 하나도 없어도 만들어지고 관리된다. 운영 건에 태그를 다는 규칙은 replaceOperationTags
 * 한 곳뿐이고, 업무·하위 업무·회의가 그 하나를 함께 쓴다.
 */
public interface OperationTagService {

    /** 태그 전체를 이름 오름차순으로. usageCount는 살아 있는 운영 건 수다 */
    List<OperationTagResponse> getTags();

    /** 태그 생성. 같은 이름이 이미 있으면 409 OPERATION_TAG_NAME_DUPLICATED */
    OperationTagResponse createTag(OperationTagSaveRequest request);

    /** 이름 변경. 없으면 404 OPERATION_TAG_NOT_FOUND, 다른 태그와 이름이 겹치면 409 OPERATION_TAG_NAME_DUPLICATED */
    OperationTagResponse renameTag(Long operationTagId, OperationTagSaveRequest request);

    /** 태그 삭제. 그 태그의 지정(oper_tag_rel)도 함께 지운다 — 운영 건은 그대로다 */
    void deleteTag(Long operationTagId);

    /*
     * 운영 건의 태그 지정 전체 교체. 요청에 없는 연결은 지우고, 새로 생긴 것만 넣고, 유지되는 것은
     * 손대지 않아 crt_dt(지정 시각)가 보존된다. 결과는 태그 이름 오름차순이다.
     */
    List<OperationTagAssignmentResponse> replaceOperationTags(Long operationId, List<Long> tagIds);
}
