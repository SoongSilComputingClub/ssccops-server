package org.sscc.ssccopsserver.domain.form.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseCount;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormServiceImpl implements FormService {

    /** 복제본 제목 접미. 웹 목 스토어(duplicateForm)가 이미 쓰던 표기를 그대로 굳힌다 */
    private static final String COPY_SUFFIX = " (복사본)";

    /*
     * 목록의 responseCount가 세는 상태. 임시저장(DRAFT)은 아직 응답자가 낸 것이 아니라
     * 빠진다 — 세면 운영진이 보는 접수 건수가 부풀어 마감 판단이 어긋난다.
     */
    private static final Collection<ResponseStatus> SUBMITTED_OR_LATER =
            EnumSet.of(ResponseStatus.SUBMITTED, ResponseStatus.ACCEPTED, ResponseStatus.REJECTED);

    private final FormRepository formRepository;
    private final FormLabelRepository formLabelRepository;
    private final FormLabelRelationRepository formLabelRelationRepository;
    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final QuestionCompositionValidator questionCompositionValidator;

    /*
     * 폼 목록. 쿼리는 폼 1 + 라벨 1 + 응답 집계 1로 3회다 — 폼마다 라벨을 조회하거나 응답을
     * 세면 그대로 N+1이 된다 (DB-13).
     *
     * 페이징을 두지 않은 것은 화면이 필터 결과를 카드로 한 번에 그리기 때문이다. 폼은 모집
     * 회차마다 늘어나는 데이터라 언젠가는 필요하지만, 지금 넣으면 프론트가 쓰지 않는 page·size
     * 계약이 먼저 굳는다.
     */
    @Override
    public List<FormSummaryResponse> getForms(FormStatus statusCode, Long labelId) {
        // 상태 미지정은 "전체"다. NULL 비교 대신 전체 상태 집합을 넘긴다 (FormRepository 주석 참고)
        Collection<FormStatus> statuses =
                statusCode == null ? EnumSet.allOf(FormStatus.class) : EnumSet.of(statusCode);

        List<FormEntity> forms = formRepository.findAllForAdminList(statuses, labelId);
        if (forms.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 뒤따르는 두 조회를 아예 보내지 않는다
            return List.of();
        }

        List<Long> formIds = forms.stream().map(FormEntity::getId).toList();
        Map<Long, List<FormLabelSummaryResponse>> labelsByFormId = labelsOf(formIds);
        Map<Long, Long> responseCountByFormId = responseCountsOf(formIds);

        return forms.stream()
                .map(
                        form ->
                                FormSummaryResponse.of(
                                        form,
                                        labelsByFormId.getOrDefault(form.getId(), List.of()),
                                        responseCountByFormId.getOrDefault(form.getId(), 0L)))
                .toList();
    }

    @Override
    public FormDetailResponse getForm(Long formId) {
        FormEntity form = findForm(formId);
        return FormDetailResponse.of(form, labelsOf(form), responseCountOf(form));
    }

    /*
     * 폼 생성. 생성자는 인증 주체이며 요청 본문이 지정할 수 없다 — 지정할 수 있으면 남의 이름으로
     * 폼을 만들 수 있고, form.creatr_mbr_id는 사후 변경이 불가(updatable = false)라 되돌릴 수 없다.
     *
     * 폼과 라벨 연결을 한 트랜잭션에 묶는 것은 둘 중 하나만 남으면 라벨 없는 폼이거나 폼 없는
     * 연결이 되기 때문이다 (AR-11).
     */
    @Override
    @Transactional
    public FormSaveResponse createForm(FormSaveRequest request, MemberEntity creator) {
        QuestionCompositionContent composition =
                questionCompositionValidator.validate(request.qitemCpstCn());
        Instant receiptBeginAt = toInstant(request.rcptBgngDt());
        Instant receiptEndAt = toInstant(request.rcptEndDt());
        validateReceiptPeriod(receiptBeginAt, receiptEndAt);

        /*
         * 상태 미지정은 DRAFT다. 편집 화면의 '바로 접수 시작'이 OPEN을 그대로 보내므로 값을
         * 받아들이되, 어떤 전이가 허용되는지는 여기서 판단하지 않는다 — 상태 전이 규칙은
         * 접수 상태 전이 API(#33)가 한곳에서 갖는다.
         */
        FormStatus status = request.formSttsCd() == null ? FormStatus.DRAFT : request.formSttsCd();

        FormEntity form =
                formRepository.save(
                        FormEntity.create(
                                creator,
                                request.formTtlNm(),
                                composition,
                                receiptBeginAt,
                                receiptEndAt,
                                status));

        return FormSaveResponse.of(form, replaceLabels(form, request.labelIdsOrEmpty()));
    }

    /*
     * 폼 수정. 문항 구성은 부분 갱신이 아니라 전체 교체다 (QuestionCompositionContent 주석).
     * 편집 자동 저장(ssccops #63)도 같은 엔드포인트를 쓰므로 자주 호출된다.
     */
    @Override
    @Transactional
    public FormSaveResponse updateForm(Long formId, FormSaveRequest request) {
        FormEntity form = findForm(formId);

        QuestionCompositionContent composition =
                questionCompositionValidator.validate(request.qitemCpstCn());
        Instant receiptBeginAt = toInstant(request.rcptBgngDt());
        Instant receiptEndAt = toInstant(request.rcptEndDt());
        validateReceiptPeriod(receiptBeginAt, receiptEndAt);
        // 교체 전 구성과 비교해야 하므로 update() 호출보다 먼저 검사한다
        ensureExistingQuestionItemsKept(form, composition);

        /*
         * 상태 미지정은 "그대로 두기"다. 라벨(labelIds)과 해석이 갈리는데, 라벨은 전체 교체가
         * 곧 화면의 동작이라 생략이 "전부 떼기"인 반면 상태는 편집 화면에 입력란이 없어
         * 생략이 기본값이다. 자동 저장이 상태를 지우고 DRAFT로 되돌리면 접수 중인 폼이 닫힌다.
         */
        FormStatus status = request.formSttsCd() == null ? form.getStatus() : request.formSttsCd();

        form.update(request.formTtlNm(), status, composition, receiptBeginAt, receiptEndAt);
        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 — 먼저 흘려보내야 응답의 수정 일시가 실제 값이 된다
        formRepository.flush();

        return FormSaveResponse.of(form, replaceLabels(form, request.labelIdsOrEmpty()));
    }

    /*
     * 폼 복제. 웹 목 스토어(duplicateForm)가 이미 확정해 둔 규칙을 그대로 따른다 —
     * 제목에 '(복사본)', 상태는 DRAFT, 접수 일시는 초기화, 문항 구성은 깊은 복사.
     *
     * 응답과 라벨은 승계하지 않는다. 응답은 원본 폼에 낸 것이라 사본으로 옮기면 응답자가 낸 적
     * 없는 폼에 답이 달리고, 라벨은 '2026 신규모집'처럼 회차를 뜻하는 값이라 새 회차를 만들려고
     * 복제한 폼에 지난 회차의 분류가 따라붙으면 목록 필터가 거짓말을 한다.
     *
     * 생성자는 원본 생성자가 아니라 복제를 수행한 회원이다 — 사본을 만든 사람이 사본의 주인이다.
     */
    @Override
    @Transactional
    public FormDuplicateResponse duplicateForm(Long formId, MemberEntity creator) {
        FormEntity source = findForm(formId);

        FormEntity copy =
                formRepository.save(
                        FormEntity.create(
                                creator,
                                source.getTitle() + COPY_SUFFIX,
                                source.getQuestionComposition().deepCopy(),
                                null,
                                null,
                                FormStatus.DRAFT));

        return FormDuplicateResponse.of(copy, source.getId());
    }

    private FormEntity findForm(Long formId) {
        return formRepository
                .findById(formId)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }

    private void validateReceiptPeriod(Instant receiptBeginAt, Instant receiptEndAt) {
        // 한쪽만 주어진 경우는 검사 대상이 아니다 — 기간 제한 없이 여는 폼이 정상이다
        if (receiptBeginAt != null
                && receiptEndAt != null
                && receiptEndAt.isBefore(receiptBeginAt)) {
            throw new GeneralException(FormErrorCode.INVALID_RECEIPT_PERIOD);
        }
    }

    /*
     * 문항 식별자 보호. 응답이 한 건이라도 있으면 기존 qitemId가 전부 그대로 남아 있어야 한다.
     *
     * rspns_cn의 key가 qitemId라, 삭제하거나 이름을 바꾸면 과거 응답이 어느 문항의 답인지 알 수
     * 없게 된다 — 이름 변경은 "옛 id를 지우고 새 id를 넣는 것"과 구별되지 않으므로 같은 규칙에
     * 걸린다. 문항을 새로 추가하거나 라벨·선택지를 고치는 것은 계속 허용된다.
     *
     * 응답이 없는 폼은 자유롭게 고칠 수 있다 — 끊길 답이 없기 때문이다.
     */
    private void ensureExistingQuestionItemsKept(FormEntity form, QuestionCompositionContent next) {
        if (!formResponseHistoryRepository.existsByForm(form)) {
            return;
        }

        Set<String> nextIds =
                next.qitems().stream().map(QuestionItem::qitemId).collect(Collectors.toSet());
        boolean anyRemoved =
                form.getQuestionComposition().qitems().stream()
                        .map(QuestionItem::qitemId)
                        .anyMatch(qitemId -> !nextIds.contains(qitemId));

        if (anyRemoved) {
            throw new GeneralException(FormErrorCode.QUESTION_ITEM_IN_USE);
        }
    }

    /*
     * 라벨 지정 교체. 통째로 지우고 다시 넣지 않고 차집합만 움직이는 것은, 같은 (form_id,
     * form_lbl_id) 쌍을 지웠다 넣으면 Hibernate가 한 트랜잭션에서 INSERT를 DELETE보다 먼저
     * 흘려보내 UNIQUE 제약에 걸리기 때문이다. 실제로 바뀐 연결만 건드리면 그 순서 문제가 없다.
     *
     * 비활성(use_yn = false) 라벨을 여기서 막지 않는다 — 새로 달 수 없는 라벨인지는 라벨 관리
     * 정책(#34)이 판단할 일이라는 FormLabelRelationEntity의 결정을 그대로 따른다.
     */
    private List<FormLabelSummaryResponse> replaceLabels(FormEntity form, List<Long> labelIds) {
        // 같은 라벨을 두 번 보내도 연결은 하나다 — UNIQUE 제약에 걸리기 전에 걸러 낸다
        List<Long> requestedIds = labelIds.stream().filter(Objects::nonNull).distinct().toList();

        Map<Long, FormLabelRelationEntity> currentByLabelId =
                formLabelRelationRepository.findAllByForm(form).stream()
                        .collect(
                                Collectors.toMap(
                                        relation -> relation.getLabel().getId(),
                                        Function.identity()));

        formLabelRelationRepository.deleteAll(
                currentByLabelId.entrySet().stream()
                        .filter(entry -> !requestedIds.contains(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .toList());

        List<FormLabelSummaryResponse> labels = new ArrayList<>();
        for (Long labelId : requestedIds) {
            FormLabelRelationEntity kept = currentByLabelId.get(labelId);
            if (kept != null) {
                labels.add(FormLabelSummaryResponse.from(kept.getLabel()));
                continue;
            }
            FormLabelEntity label =
                    formLabelRepository
                            .findById(labelId)
                            .orElseThrow(
                                    () -> new GeneralException(FormErrorCode.LABEL_NOT_ASSIGNABLE));
            formLabelRelationRepository.save(FormLabelRelationEntity.create(form, label));
            labels.add(FormLabelSummaryResponse.from(label));
        }
        return labels;
    }

    private Map<Long, List<FormLabelSummaryResponse>> labelsOf(List<Long> formIds) {
        return formLabelRelationRepository.findAllByFormIdIn(formIds).stream()
                .collect(
                        Collectors.groupingBy(
                                relation -> relation.getForm().getId(),
                                Collectors.mapping(
                                        relation ->
                                                FormLabelSummaryResponse.from(relation.getLabel()),
                                        Collectors.toList())));
    }

    private List<FormLabelSummaryResponse> labelsOf(FormEntity form) {
        return formLabelRelationRepository.findAllByForm(form).stream()
                .map(relation -> FormLabelSummaryResponse.from(relation.getLabel()))
                .toList();
    }

    /*
     * 응답이 한 건도 없는 폼은 GROUP BY 결과에 나오지 않는다. 조회되지 않았다는 사실과 0건이라는
     * 사실이 같은 뜻이므로 호출부에서 0으로 채운다 (FormResponseCount 주석).
     */
    private Map<Long, Long> responseCountsOf(List<Long> formIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (FormResponseCount count :
                formResponseHistoryRepository.countByFormIds(formIds, SUBMITTED_OR_LATER)) {
            counts.put(count.getFormId(), count.getResponseCount());
        }
        return counts;
    }

    private long responseCountOf(FormEntity form) {
        return responseCountsOf(List.of(form.getId())).getOrDefault(form.getId(), 0L);
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
