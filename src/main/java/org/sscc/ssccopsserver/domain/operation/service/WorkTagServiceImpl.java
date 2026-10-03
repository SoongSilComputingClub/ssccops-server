package org.sscc.ssccopsserver.domain.operation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagAssignmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkTagSaveRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkTagEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkTagRelationEntity;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkTagRelationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkTagRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkTagUsageCount;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 업무 태그 (#624). 판단은 대부분 FormLabelServiceImpl과 같고, 갈리는 자리만 주석으로 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkTagServiceImpl implements WorkTagService {

    private final WorkTagRepository workTagRepository;
    private final WorkTagRelationRepository workTagRelationRepository;
    private final WorkRepository workRepository;

    // 쿼리 2회 — 태그 목록 1 + 사용 건수 집계 1. 태그마다 세면 N+1이다 (DB-13)
    @Override
    public List<WorkTagResponse> getTags() {
        List<WorkTagEntity> tags = workTagRepository.findAllByOrderByNameAsc();
        if (tags.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 애초에 쿼리를 보내지 않는다
            return List.of();
        }
        Map<Long, Long> usageByTagId =
                workTagRelationRepository
                        .findUsageCountsByTagIds(tags.stream().map(WorkTagEntity::getId).toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        WorkTagUsageCount::getTagId,
                                        WorkTagUsageCount::getUsageCount));
        return tags.stream()
                .map(tag -> WorkTagResponse.of(tag, usageByTagId.getOrDefault(tag.getId(), 0L)))
                .toList();
    }

    // 방금 만든 태그를 쓰는 업무는 있을 수 없으니 사용 건수는 세지 않고 0이다
    @Override
    @Transactional
    public WorkTagResponse createTag(WorkTagSaveRequest request) {
        String name = request.tagNm().strip();
        if (workTagRepository.existsByName(name)) {
            throw new GeneralException(OperationErrorCode.WORK_TAG_NAME_DUPLICATED);
        }
        return WorkTagResponse.of(saveOrTranslateConflict(WorkTagEntity.create(name)), 0L);
    }

    /*
     * 이름 변경. 관계는 식별자를 들고 있으므로 이미 달린 업무의 칩도 함께 새 이름이 된다 — 국 이름이
     * 바뀌면 그 국의 업무 전부가 따라가야 한다는 것이 이 API의 목적이다. 같은 이름으로 다시 저장하는
     * 것은 중복이 아니다(멱등).
     */
    @Override
    @Transactional
    public WorkTagResponse renameTag(Long workTagId, WorkTagSaveRequest request) {
        WorkTagEntity tag = findTag(workTagId);
        String name = request.tagNm().strip();
        if (workTagRepository.existsByNameAndIdNot(name, workTagId)) {
            throw new GeneralException(OperationErrorCode.WORK_TAG_NAME_DUPLICATED);
        }
        tag.rename(name);
        // mdfcn_dt를 응답에 실으려면 flush로 감사 필드를 채워야 하고, 그 자리에서 UNIQUE 위반도 드러난다
        saveOrTranslateConflict(tag);

        long usage =
                workTagRelationRepository.findUsageCountsByTagIds(List.of(workTagId)).stream()
                        .findFirst()
                        .map(WorkTagUsageCount::getUsageCount)
                        .orElse(0L);
        return WorkTagResponse.of(tag, usage);
    }

    /*
     * 태그 삭제 — **폼 라벨과 갈리는 자리다.** 폼 라벨은 과거 폼의 분류를 지키려 지우지 않고 use_yn을
     * 내리지만, 업무 태그는 지우면 지정도 함께 지운다(ssccops#565 수용 기준 · 업무는 그대로).
     * DB에도 ON DELETE CASCADE가 있지만(V29) 관계를 먼저 지우는 것은 영속성 컨텍스트가 그 규칙을
     * 모르기 때문이다(WorkTagRelationRepository.deleteAllByTag 주석).
     *
     * deleteAllByTag가 영속성 컨텍스트를 비우므로(clearAutomatically) 태그는 식별자로 다시 집어 지운다.
     */
    @Override
    @Transactional
    public void deleteTag(Long workTagId) {
        WorkTagEntity tag = findTag(workTagId);
        workTagRelationRepository.deleteAllByTag(tag);
        workTagRepository.deleteById(workTagId);
    }

    /*
     * 지정 전체 교체 — FormLabelServiceImpl.replaceFormLabels와 같은 비교식이다. delete-all 후
     * insert-all이 훨씬 짧지만 그러면 지정 시각이 매 저장마다 갱신된다. 같은 요청을 두 번 보내면
     * 지울 것도 넣을 것도 없어 결과가 같다(멱등).
     *
     * 지운 업무(oper.del_dt)는 404다 — 목록·상세에서 보이지 않는 업무에 태그를 달 길을 열어 두지 않는다.
     */
    @Override
    @Transactional
    public List<WorkTagAssignmentResponse> replaceWorkTags(Long workId, List<Long> tagIds) {
        WorkEntity work =
                workRepository
                        .findByIdAndOperationDeletedAtIsNull(workId)
                        .orElseThrow(() -> new GeneralException(OperationErrorCode.WORK_NOT_FOUND));

        // 같은 태그가 두 번 실려 와도 한 번으로 본다 — 화면 실수가 UNIQUE 위반으로 번지지 않게 한다
        Set<Long> requested = tagIds == null ? Set.of() : new LinkedHashSet<>(tagIds);

        List<WorkTagRelationEntity> existing = workTagRelationRepository.findAllByWork(work);
        Set<Long> existingTagIds =
                existing.stream()
                        .map(relation -> relation.getTag().getId())
                        .collect(Collectors.toSet());

        List<WorkTagRelationEntity> removed =
                existing.stream()
                        .filter(relation -> !requested.contains(relation.getTag().getId()))
                        .toList();
        if (!removed.isEmpty()) {
            workTagRelationRepository.deleteAllInBatch(removed);
        }

        List<Long> addedTagIds =
                requested.stream().filter(tagId -> !existingTagIds.contains(tagId)).toList();

        List<WorkTagRelationEntity> result =
                new ArrayList<>(
                        existing.stream()
                                .filter(relation -> requested.contains(relation.getTag().getId()))
                                .toList());
        result.addAll(attachTags(work, addedTagIds));
        result.sort(Comparator.comparing(relation -> relation.getTag().getName()));

        return result.stream().map(WorkTagAssignmentResponse::from).toList();
    }

    private List<WorkTagRelationEntity> attachTags(WorkEntity work, List<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        List<WorkTagEntity> tags = workTagRepository.findAllById(tagIds);
        // findAllById는 없는 식별자를 조용히 건너뛴다 — 개수 차이가 곧 없는 태그다
        if (tags.size() != tagIds.size()) {
            throw new GeneralException(OperationErrorCode.WORK_TAG_NOT_FOUND);
        }
        /*
         * saveAllAndFlush인 것은 응답에 work_tag_rel_id와 crt_dt가 필요해서다. 같은 본문의 동시 요청이
         * uk_work_tag_rel_work_tag에 걸리면 진 쪽이 롤백되고 이긴 쪽이 같은 상태를 만들어 둔다 —
         * 그래서 409로 옮기지 않는다(FormLabelServiceImpl.attachLabels와 같은 판단).
         */
        return workTagRelationRepository.saveAllAndFlush(
                tags.stream().map(tag -> WorkTagRelationEntity.create(work, tag)).toList());
    }

    private WorkTagEntity findTag(Long workTagId) {
        return workTagRepository
                .findById(workTagId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.WORK_TAG_NOT_FOUND));
    }

    /*
     * 선조회만으로는 같은 이름의 동시 생성·변경을 막지 못한다 — 둘 다 조회를 통과하면 한쪽이
     * uk_work_tag_name에 걸린다. 그 경우도 선조회와 같은 409로 내려야 화면이 두 경로를 다르게 다루지
     * 않는다. 제약 위반은 flush 시점에야 드러나므로 saveAndFlush로 이 안에서 잡는다.
     */
    private WorkTagEntity saveOrTranslateConflict(WorkTagEntity tag) {
        try {
            return workTagRepository.saveAndFlush(tag);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(OperationErrorCode.WORK_TAG_NAME_DUPLICATED);
        }
    }
}
