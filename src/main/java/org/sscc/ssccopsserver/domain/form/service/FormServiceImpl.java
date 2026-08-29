package org.sscc.ssccopsserver.domain.form.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseStatusSummary;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormQuestionHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormQuestionHistoryRepository;
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
     * 빈 DRAFT 폼(createEmptyDraft)이 갖는 기본 페이지 제목. 페이지를 아예 두지 않으면
     * qitemCpstCn.pages가 NULL이 되고, @JsonInclude(NON_NULL)이 응답에서 키를 통째로 빼
     * 편집기가 pages.map(...)에서 죽는다. QuestionCompositionValidator도 "빈 폼이라도 페이지
     * 한 장은 있어야 한다"고 못 박으므로, 그 규칙과 같은 모양(페이지 1장 · 문항 0개)으로 만든다.
     */
    private static final String DEFAULT_PAGE_TITLE = "페이지 1";

    private final FormRepository formRepository;
    private final FormLabelRepository formLabelRepository;
    private final FormLabelRelationRepository formLabelRelationRepository;
    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final FormQuestionHistoryRepository formQuestionHistoryRepository;
    private final QuestionCompositionValidator questionCompositionValidator;
    private final FormLabelService formLabelService;

    /*
     * 코드가 시스템 폼에 요구하는 qitemId 선언 (#140). 여기서 요구 목록을 들고 있지 않는 것은,
     * 그 목록의 주인이 폼 도메인이 아니라 그 폼을 읽는 코드이기 때문이다.
     */
    private final SystemFormContract systemFormContract;

    /*
     * 접수 가능 판정·표시용 접수 상태의 유일한 구현 (#33). 여기서 직접 상태와 기간을 비교하지
     * 않는 것은 #35·#36이 같은 판정을 다시 옮겨 적는 것을 막기 위해서다.
     */
    private final FormReceiptPolicy formReceiptPolicy;

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
        Map<Long, FormResponseStatusSummary> summaryByFormId = responseSummariesOf(formIds);

        return forms.stream()
                .map(
                        form ->
                                FormSummaryResponse.of(
                                        form,
                                        formReceiptPolicy.receiptStatusOf(form),
                                        labelsByFormId.getOrDefault(form.getId(), List.of()),
                                        summaryByFormId
                                                .getOrDefault(
                                                        form.getId(),
                                                        FormResponseStatusSummary.empty())
                                                .total()))
                .toList();
    }

    /*
     * 폼 상세. 응답 요약(#37)이 목록의 responseCount와 같은 집계에서 나온다 — 상세 화면은
     * '전체 · 제출 · 수정요청 · 승인 · 반려' 숫자를 보여주고 목록은 그중 전체만 쓴다.
     *
     * 상태별 집계를 위해 질의를 하나 더 두지 않는다. 두 벌이 되면 폼 목록이 폼마다 두 번씩
     * 집계하게 되고, 무엇보다 총합과 상태별 합이 어긋날 여지가 생긴다 (FormResponseCount 주석).
     *
     * 계약 문항 목록(systemRequiredQitemIds)을 함께 싣는다 (#155). 저장 경로가 거절 근거로 쓰는 것과
     * 정확히 같은 호출(systemFormContract.requiredQitemIdsOf)이며, 그것이 요점이다 — 조회 쪽이 계약을
     * 따로 읽거나 폼 구성에서 역산하면 화면이 잠그는 문항과 서버가 거절하는 문항이 갈라질 수 있다.
     *
     * 시스템 폼인지를 여기서 따로 묻지 않는다. 평범한 폼은 sys_form_cd가 NULL이라 계약이 이미 빈
     * 집합을 돌려주며, 여기서 isSystemForm()을 한 번 더 보면 "언제 비는가"라는 판단이 두 벌이 된다.
     *
     * 목록(FormSummaryResponse)에는 싣지 않는다 — 문항 편집은 상세·편집 화면에서만 하므로 목록 카드는
     * 쓸 일이 없고, qitemCpstCn을 목록에서 빼는 규칙과 같은 줄기다.
     */
    @Override
    public FormDetailResponse getForm(Long formId) {
        FormEntity form = findForm(formId);
        return FormDetailResponse.of(
                form,
                formReceiptPolicy.receiptStatusOf(form),
                labelsOf(form),
                responseSummaryOf(form),
                systemFormContract.requiredQitemIdsOf(form.getSystemFormCode()));
    }

    /*
     * 폼 생성. 생성자는 인증 주체이며 요청 본문이 지정할 수 없다 — 지정할 수 있으면 남의 이름으로
     * 폼을 만들 수 있고, form.creatr_mbr_id는 사후 변경이 불가(updatable = false)라 되돌릴 수 없다.
     *
     * 폼과 라벨 연결을 한 트랜잭션에 묶는 것은 둘 중 하나만 남으면 라벨 없는 폼이거나 폼 없는
     * 연결이 되기 때문이다 (AR-11).
     *
     * 생성 시점의 구성도 qitem_ver = 1로 이력에 한 행 남긴다 (#140). 수정에서만 남기면 1번
     * 버전의 내용만 어디에도 없어, 이력을 처음부터 되짚으면 2번으로 바뀌기 전이 무엇이었는지
     * 폼의 현재 값에서 역산해야 한다.
     */
    @Override
    @Transactional
    public FormSaveResponse createForm(FormSaveRequest request, MemberEntity creator) {
        QuestionCompositionContent composition =
                questionCompositionValidator.validate(request.qitemCpstCn());
        Instant receiptBeginAt = toInstant(request.rcptBgngDt());
        Instant receiptEndAt = toInstant(request.rcptEndDt());
        FormEntity.requireValidReceiptPeriod(receiptBeginAt, receiptEndAt);

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
                                status,
                                request.multipleResponseAllowed()));
        recordQuestionComposition(form, creator);

        return FormSaveResponse.of(form, replaceLabels(form, request.labelIdsOrEmpty()));
    }

    /*
     * 폼 수정. 문항 구성은 부분 갱신이 아니라 전체 교체다 (QuestionCompositionContent 주석).
     * 편집 자동 저장(ssccops #63)도 같은 엔드포인트를 쓰므로 자주 호출된다.
     *
     * 수행자(actor)를 받는 것은 #140부터다. 그전까지는 수정 이력을 남기지 않아 주체를 요구하지
     * 않았는데, 문항 구성 이력(form_qitem_hstry)이 생기면서 변경자를 적을 자리가 생겼다.
     * 요청 본문으로 받지 않는 것은 #78이 세운 규칙과 같다 — 받아 주면 "누가 바꿨는가"를 스스로
     * 적어 넣을 수 있어 이력이 증거가 되지 못한다.
     */
    @Override
    @Transactional
    public FormSaveResponse updateForm(Long formId, FormSaveRequest request, MemberEntity actor) {
        FormEntity form = findForm(formId);

        QuestionCompositionContent composition =
                questionCompositionValidator.validate(request.qitemCpstCn());
        Instant receiptBeginAt = toInstant(request.rcptBgngDt());
        Instant receiptEndAt = toInstant(request.rcptEndDt());
        FormEntity.requireValidReceiptPeriod(receiptBeginAt, receiptEndAt);
        // 교체 전 구성과 비교해야 하므로 update() 호출보다 먼저 검사한다
        ensureExistingQuestionItemsKept(form, composition);

        /*
         * 시스템 폼의 코드 계약 검사 (#140). 응답 유무를 보는 위 검사와 나란히 두지만 기준이
         * 다르다 — 이쪽은 응답이 한 건도 없어도 코드가 요구하는 qitemId를 지울 수 없다.
         * 요구 목록은 폼이 아니라 그 폼을 읽는 코드가 선언한다(SystemFormContract).
         */
        form.requireSystemContractKept(
                composition, systemFormContract.requiredQitemIdsOf(form.getSystemFormCode()));

        /*
         * 본문에 formSttsCd가 실려 와도 무시한다 (#33). 라벨(labelIds)과 해석이 갈리는데, 라벨은
         * 전체 교체가 곧 화면의 동작이라 생략이 "전부 떼기"인 반면 상태는 이 엔드포인트가 아예
         * 건드리지 않는 값이다.
         *
         * 값을 무시하는 대신 400으로 거절하지 않는 것은 편집 자동 저장(ssccops #63)이 상세
         * 조회 응답을 초안으로 받아 그대로 되돌려 보내기 때문이다 — 그 본문에는 formSttsCd가
         * 늘 실려 있어, 거절하면 자동 저장이 통째로 멈춘다. 반대로 그 값을 받아 쓰면 타이핑
         * 한 번이 접수 상태를 덮어쓴다. 상태를 바꾸는 길은 POST /v1/forms/{formId}/status뿐이다.
         */
        /*
         * 버전이 올랐을 때만 이력을 남긴다 (#140). 올랐는지는 엔티티가 판단해 돌려준다 —
         * 여기서 구성을 한 번 더 비교하면 "구성이 바뀌었는가"라는 같은 규칙이 두 벌이 되고,
         * 그때부터 버전과 이력이 갈릴 수 있다.
         */
        if (form.update(
                request.formTtlNm(),
                composition,
                receiptBeginAt,
                receiptEndAt,
                request.multipleResponseAllowed())) {
            recordQuestionComposition(form, actor);
        }

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
     *
     * **시스템 폼의 사본은 시스템 폼이 아니다** (#140). sys_form_cd에 UNIQUE가 걸려 있어 승계하면
     * 저장 자체가 실패하고, 실패하지 않더라도 코드가 두 폼 중 어느 쪽을 가리키는지 알 수 없게
     * 된다. 문항 구성 버전도 승계하지 않고 1에서 다시 시작한다 — 사본의 이력은 여기서 시작하므로
     * 원본의 버전을 물려받으면 그 앞 버전의 이력이 없는 채로 번호만 큰 폼이 된다. 해제 코드를
     * 여기 적지 않고 FormEntity.create가 언제나 그 상태로 만들게 둔 것은, 새 폼을 만드는 경로가
     * 늘 때마다 해제를 다시 적어야 하는 것을 피하기 위해서다.
     *
     * **다중 응답 허용 여부(#143)는 반대로 승계한다.** 시스템 폼 코드와 갈리는 것은 그쪽이
     * "환경에 하나뿐인 이름"이라 사본이 가지면 UNIQUE에 걸리는 값인 반면, 이쪽은 문항 구성·제목과
     * 같은 폼의 설정이기 때문이다 — "이 폼과 똑같은 것 하나 더"라고 했는데 응답 접수 규칙만
     * 조용히 달라지면 다음 회차 폼이 지난 회차와 다르게 동작한다. 접수 기간을 초기화하는 것과도
     * 갈린다: 기간은 회차마다 반드시 새로 정하는 값이지만 이 설정은 그대로 두는 것이 기본이다.
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
                                FormStatus.DRAFT,
                                source.isMultipleResponseAllowed()));
        recordQuestionComposition(copy, creator);

        return FormDuplicateResponse.of(copy, source.getId());
    }

    /*
     * 접수 상태 전이 (#33). 전이표·사전 검증은 FormEntity.changeStatus가 갖고 여기서는 조회와
     * 응답 조립만 한다 — 서비스에 if 분기로 옮겨 적으면 폼을 OPEN으로 만드는 생성 경로와
     * 규칙이 갈린다 (LY-02·AR-10).
     *
     * 상태를 PUT /v1/forms/{formId}가 아니라 전용 액션 경로로 분리한 이유는 편집 자동 저장이
     * 매 타이핑마다 PUT을 쏘기 때문이다 (updateForm 주석 · SubWorkController 선례).
     */
    @Override
    @Transactional
    public FormStatusChangeResponse changeStatus(Long formId, FormStatusChangeRequest request) {
        FormEntity form = findForm(formId);
        form.changeStatus(request.action());

        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 — 먼저 흘려보내야 응답의 수정 일시가 실제 값이 된다
        formRepository.flush();

        return FormStatusChangeResponse.of(form, formReceiptPolicy.receiptStatusOf(form));
    }

    /*
     * 문항 0개인 DRAFT 폼 생성 (#133). requireOpenable()은 DRAFT를 만들 때는 돌지 않으므로
     * 빈 qitems가 그대로 통과한다 — 문항 0개 금지는 여는(OPEN) 쪽에만 걸린다(FormEntity 주석).
     *
     * pages는 비우지 않고 기본 페이지 한 장을 넣는다 (#186). NULL로 두면 응답의 qitemCpstCn에
     * pages 키가 통째로 빠져(@JsonInclude(NON_NULL)) 편집기가 pages.map(...)에서 죽고,
     * 무엇보다 QuestionCompositionValidator가 요구하는 최소 구조(페이지 1장)와도 어긋난다.
     */
    @Override
    @Transactional
    public FormEntity createEmptyDraft(String title, MemberEntity creator) {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new QuestionCompositionContent.Page(DEFAULT_PAGE_TITLE, null)),
                        List.of());
        return formRepository.save(FormEntity.create(creator, title, composition, null, null));
    }

    /*
     * 접수 기간만 갱신 (#133). changeStatus(OPEN)보다 먼저 불러야 requireOpenable()의 접수 기간
     * 정합성 검사가 갱신된 기간을 본다 — 호출 순서는 이 메서드가 아니라 호출부의 책임이다.
     */
    @Override
    @Transactional
    public void changeReceiptPeriod(Long formId, Instant receiptBeginAt, Instant receiptEndAt) {
        FormEntity form = findForm(formId);
        form.changeReceiptPeriod(receiptBeginAt, receiptEndAt);
    }

    private FormEntity findForm(Long formId) {
        return formRepository
                .findById(formId)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
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

        Set<String> nextIds = QuestionCompositionContent.qitemIdsOf(next);
        Set<String> currentIds =
                QuestionCompositionContent.qitemIdsOf(form.getQuestionComposition());

        if (!nextIds.containsAll(currentIds)) {
            throw new GeneralException(FormErrorCode.QUESTION_ITEM_IN_USE);
        }
    }

    /*
     * 문항 구성 이력 한 행 (#140). 생성·복제·버전이 오른 수정이 모두 이 자리를 지난다 —
     * 세 경로가 각자 이력을 만들면 어느 한 곳만 빠져도 이력에 구멍이 생기고, 구멍이 있는
     * 이력은 "그때 무엇이었는가"에 답하지 못해 없는 것과 같아진다.
     *
     * 버전과 구성을 넘기지 않는 것은 엔티티 팩토리가 폼에서 직접 읽기 때문이다
     * (FormQuestionHistoryEntity.of 주석).
     */
    private void recordQuestionComposition(FormEntity form, MemberEntity changedBy) {
        formQuestionHistoryRepository.save(FormQuestionHistoryEntity.of(form, changedBy));
    }

    /*
     * 폼 등록·수정 본문의 labelIds를 라벨 지정 규칙에 태운다.
     *
     * 예전에는 이 클래스가 지정 교체를 직접 구현했는데, #34가 같은 규칙을
     * FormLabelService.replaceFormLabels로 세우면서 규칙이 두 벌이 됐다. 두 벌이 되자마자
     * 실제로 갈렸다 — 여기서는 비활성(use_yn = false) 라벨을 검사하지 않아 폼 저장 경로로는
     * 비활성 라벨이 그대로 지정됐고, 지정 API 경로로는 400으로 막혔다. 없는 라벨의 응답도
     * 한쪽은 400, 다른 쪽은 404였다.
     *
     * 폼 편집 화면은 labelIds를 폼 저장에 실어 보내므로 사용자가 마주치는 것은 이 경로다.
     * 규칙의 유일한 주인을 FormLabelService로 두고 여기서는 호출만 한다.
     */
    private List<FormLabelSummaryResponse> replaceLabels(FormEntity form, List<Long> labelIds) {
        return formLabelService.replaceFormLabels(form.getId(), labelIds).stream()
                .map(
                        assignment ->
                                new FormLabelSummaryResponse(
                                        assignment.formLblId(), assignment.lblNm()))
                .toList();
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
     * 폼별 응답 요약을 한 번의 집계 질의로 모은다. 폼이 몇 건이든 질의는 하나다 — 폼마다 세면
     * 그대로 N+1이고(DB-13), 상태별로 나뉘었다고 해서 그 수가 늘어나서는 안 된다.
     *
     * 응답이 한 건도 없는 폼은 GROUP BY 결과에 나오지 않는다. 조회되지 않았다는 사실과 0건이라는
     * 사실이 같은 뜻이므로 호출부에서 빈 요약으로 채운다 (FormResponseCount 주석).
     *
     * 세는 상태는 제출 이상뿐이다 — 작성 중(DRAFT)은 접수 건수에도, 상세의 요약 상자에도 들어가지
     * 않는다 (#36에서 정한 규칙, ResponseStatus.submittedOrLater 주석).
     */
    private Map<Long, FormResponseStatusSummary> responseSummariesOf(List<Long> formIds) {
        Map<Long, Map<ResponseStatus, Long>> countsByFormId = new HashMap<>();
        for (FormResponseCount count :
                formResponseHistoryRepository.countByFormIds(
                        formIds, ResponseStatus.submittedOrLater())) {
            countsByFormId
                    .computeIfAbsent(count.getFormId(), key -> new EnumMap<>(ResponseStatus.class))
                    .put(count.getStatus(), count.getResponseCount());
        }

        Map<Long, FormResponseStatusSummary> summaries = new HashMap<>();
        countsByFormId.forEach(
                (formId, counts) -> summaries.put(formId, FormResponseStatusSummary.from(counts)));
        return summaries;
    }

    private FormResponseStatusSummary responseSummaryOf(FormEntity form) {
        return responseSummariesOf(List.of(form.getId()))
                .getOrDefault(form.getId(), FormResponseStatusSummary.empty());
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
