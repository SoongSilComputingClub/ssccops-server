package org.sscc.ssccopsserver.domain.form.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelAssignmentResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelCreateRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelUpdateRequest;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelUsageCount;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormLabelServiceImpl implements FormLabelService {

    private final FormLabelRepository formLabelRepository;
    private final FormLabelRelationRepository formLabelRelationRepository;
    private final FormRepository formRepository;

    /*
     * 목록 조회는 쿼리 2회다 — 라벨 목록 1 + 사용 건수 집계 1. 라벨마다 폼 수를 세면 그대로
     * N+1이 된다 (DB-13).
     */
    @Override
    public List<FormLabelResponse> getLabels(Boolean useYn) {
        List<FormLabelEntity> labels =
                useYn == null
                        ? formLabelRepository.findAllByOrderByNameAsc()
                        : formLabelRepository.findAllByActiveOrderByNameAsc(useYn);

        Map<Long, Long> usageCountByLabelId = usageCountsOf(labels);

        return labels.stream()
                .map(
                        label ->
                                FormLabelResponse.of(
                                        label, usageCountByLabelId.getOrDefault(label.getId(), 0L)))
                .toList();
    }

    /*
     * 생성 직후의 사용 건수는 셀 것도 없이 0이다 — 방금 만든 라벨을 쓰는 폼은 있을 수 없다.
     */
    @Override
    @Transactional
    public FormLabelResponse createLabel(FormLabelCreateRequest request) {
        String name = request.lblNm().trim();
        if (formLabelRepository.existsByName(name)) {
            throw new GeneralException(FormErrorCode.FORM_LABEL_NAME_DUPLICATED);
        }

        return FormLabelResponse.of(saveOrTranslateConflict(FormLabelEntity.create(name)), 0L);
    }

    /*
     * 사용 여부만 바꾼다. form_lbl_rel은 한 행도 건드리지 않는 것이 이 API의 요점이다 —
     * 비활성 라벨이 걸린 과거 폼의 분류는 그대로 남아야 한다.
     *
     * 같은 값을 다시 넣어도 결과가 같다(멱등). 화면의 토글이 두 번 눌려도 오류가 아니다.
     */
    @Override
    @Transactional
    public FormLabelResponse updateLabelUsage(Long formLblId, FormLabelUpdateRequest request) {
        FormLabelEntity label =
                formLabelRepository
                        .findById(formLblId)
                        .orElseThrow(
                                () -> new GeneralException(FormErrorCode.FORM_LABEL_NOT_FOUND));

        label.changeActive(request.useYn());

        // 관리 화면이 토글 직후에도 "사용 중인 폼 N건"을 그대로 보여주므로 건수를 다시 실어 준다
        return FormLabelResponse.of(label, usageCountOf(label.getId()));
    }

    /*
     * 지정 전체 교체. 삭제·삽입·유지를 한 트랜잭션에서 끝낸다 — 중간에 끊기면 폼이 라벨을
     * 절반만 가진 상태로 남는다.
     *
     * 유지되는 연결은 다시 만들지 않고 그대로 둔다. delete-all 후 insert-all이 훨씬 짧지만
     * 그러면 지정 시각(crt_dt)이 매 저장마다 갱신돼 "언제 이 분류가 붙었는가"를 잃는다.
     *
     * use_yn 검사는 '새로 추가되는 것'에만 건다. 편집 화면은 기존 선택을 그대로 실어 보내므로
     * 이미 지정된 비활성 라벨이 요청에 다시 들어오는 것이 정상 경로이고, 그것까지 막으면
     * 비활성 라벨이 붙은 폼은 제목 한 글자도 못 고치게 된다.
     */
    @Override
    @Transactional
    public List<FormLabelAssignmentResponse> replaceFormLabels(Long formId, List<Long> labelIds) {
        FormEntity form =
                formRepository
                        .findById(formId)
                        .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));

        // 같은 라벨이 두 번 실려 와도 한 번으로 본다 — 화면 실수가 UNIQUE 위반으로 번지지 않게 한다
        Set<Long> requestedLabelIds = labelIds == null ? Set.of() : new LinkedHashSet<>(labelIds);

        List<FormLabelRelationEntity> existing = formLabelRelationRepository.findAllByForm(form);
        Map<Long, FormLabelRelationEntity> existingByLabelId =
                existing.stream()
                        .collect(
                                Collectors.toMap(
                                        relation -> relation.getLabel().getId(),
                                        relation -> relation));

        List<FormLabelRelationEntity> removed =
                existing.stream()
                        .filter(
                                relation ->
                                        !requestedLabelIds.contains(relation.getLabel().getId()))
                        .toList();
        if (!removed.isEmpty()) {
            formLabelRelationRepository.deleteAllInBatch(removed);
        }

        List<Long> addedLabelIds =
                requestedLabelIds.stream()
                        .filter(labelId -> !existingByLabelId.containsKey(labelId))
                        .toList();

        List<FormLabelRelationEntity> kept =
                existing.stream()
                        .filter(relation -> requestedLabelIds.contains(relation.getLabel().getId()))
                        .toList();

        /*
         * 추가할 것이 없으면 같은 요청을 두 번 보낸 경우다. 지울 것도 없으니 쿼리 한 번 없이
         * 같은 결과가 나온다 — 멱등성이 재시도 로직이 아니라 이 비교에서 나온다.
         */
        List<FormLabelRelationEntity> added = attachLabels(form, addedLabelIds);

        List<FormLabelRelationEntity> result = new ArrayList<>(kept);
        result.addAll(added);
        result.sort(Comparator.comparing(relation -> relation.getLabel().getName()));

        return result.stream().map(FormLabelAssignmentResponse::from).toList();
    }

    private List<FormLabelRelationEntity> attachLabels(FormEntity form, List<Long> labelIds) {
        if (labelIds.isEmpty()) {
            return List.of();
        }

        List<FormLabelEntity> labels = formLabelRepository.findAllById(labelIds);
        // findAllById는 없는 식별자를 조용히 건너뛴다 — 개수 차이가 곧 '존재하지 않는 라벨'이다
        if (labels.size() != labelIds.size()) {
            throw new GeneralException(FormErrorCode.FORM_LABEL_NOT_FOUND);
        }
        if (labels.stream().anyMatch(label -> !label.isActive())) {
            throw new GeneralException(FormErrorCode.FORM_LABEL_NOT_USABLE);
        }

        /*
         * saveAll이 아니라 saveAllAndFlush인 것은 응답에 form_lbl_rel_id와 crt_dt가 필요하기
         * 때문이다. 동시 요청이 같은 (form_id, form_lbl_id)를 나란히 넣어 uk_form_lbl_rel_form_label에
         * 걸리는 경우는 진 쪽의 트랜잭션이 통째로 롤백되고, 이긴 쪽이 이미 같은 상태를 만들어 둔다 —
         * 두 요청의 본문이 같으므로 최종 상태는 어느 쪽이 이겨도 같다. 그래서 이 충돌을 409로
         * 옮기지 않는다(라벨명 중복과 다른 점이다). 순차 재요청은 위의 비교에서 이미 걸러진다.
         */
        return formLabelRelationRepository.saveAllAndFlush(
                labels.stream().map(label -> FormLabelRelationEntity.create(form, label)).toList());
    }

    /*
     * 선조회만으로는 같은 이름의 동시 생성을 막지 못한다 — 두 요청이 나란히 조회를 통과한 뒤
     * 둘 다 INSERT 하면 한쪽이 uk_form_lbl_name에 걸린다. 그 경우도 선조회와 같은 409로 내려야
     * 프론트가 두 경로를 다르게 다루지 않아도 된다 (#21 학번 중복과 같은 패턴).
     *
     * 제약 위반은 flush 시점에야 드러나므로 saveAndFlush로 이 메서드 안에서 잡는다.
     */
    private FormLabelEntity saveOrTranslateConflict(FormLabelEntity label) {
        try {
            return formLabelRepository.saveAndFlush(label);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(FormErrorCode.FORM_LABEL_NAME_DUPLICATED);
        }
    }

    private Map<Long, Long> usageCountsOf(Collection<FormLabelEntity> labels) {
        if (labels.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 애초에 쿼리를 보내지 않는다
            return Map.of();
        }
        List<Long> labelIds = labels.stream().map(FormLabelEntity::getId).toList();
        return formLabelRelationRepository.findUsageCountsByLabelIds(labelIds).stream()
                .collect(
                        Collectors.toMap(
                                FormLabelUsageCount::getLabelId,
                                FormLabelUsageCount::getUsageCount));
    }

    private long usageCountOf(Long labelId) {
        return formLabelRelationRepository.findUsageCountsByLabelIds(List.of(labelId)).stream()
                .findFirst()
                .map(FormLabelUsageCount::getUsageCount)
                .orElse(0L);
    }
}
