package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.operation.dto.WorkTagAssignmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagSaveRequest;

/*
 * 업무 태그 관리·지정 (#624 · ssccops#565). 폼 라벨(FormLabelService)과 같은 모양이다.
 *
 * 업무 CRUD(WorkService)와 서비스를 나눈 것은 태그가 업무와 수명이 다른 자원이기 때문이다 — 태그는
 * 업무가 하나도 없어도 만들어지고 관리된다. 업무에 태그를 다는 규칙은 replaceWorkTags 한 곳뿐이다.
 */
public interface WorkTagService {

    /** 태그 전체를 이름 오름차순으로. usageCount는 살아 있는 업무 수다 */
    List<WorkTagResponse> getTags();

    /** 태그 생성. 같은 이름이 이미 있으면 409 WORK_TAG_NAME_DUPLICATED */
    WorkTagResponse createTag(WorkTagSaveRequest request);

    /** 이름 변경. 없으면 404 WORK_TAG_NOT_FOUND, 다른 태그와 이름이 겹치면 409 WORK_TAG_NAME_DUPLICATED */
    WorkTagResponse renameTag(Long workTagId, WorkTagSaveRequest request);

    /** 태그 삭제. 그 태그의 지정(work_tag_rel)도 함께 지운다 — 업무는 그대로다 */
    void deleteTag(Long workTagId);

    /*
     * 업무의 태그 지정 전체 교체. 요청에 없는 연결은 지우고, 새로 생긴 것만 넣고, 유지되는 것은
     * 손대지 않아 crt_dt(지정 시각)가 보존된다. 결과는 태그 이름 오름차순이다.
     */
    List<WorkTagAssignmentResponse> replaceWorkTags(Long workId, List<Long> tagIds);
}
