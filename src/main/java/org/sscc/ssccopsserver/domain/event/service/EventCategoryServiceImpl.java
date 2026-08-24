package org.sscc.ssccopsserver.domain.event.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryCreateRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryUpdateRequest;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationUsageCount;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 행사 분류 관리의 구현 (ssccops#140 · RoleClassificationServiceImpl 선례).
 *
 * 지키는 것은 하나다 — **분류가 사라져도 행사가 갈 곳을 잃지 않는다.** event.event_clsf_cd가
 * NOT NULL FK이므로(D13, 행사당 분류 정확히 1개) 사용 중인 분류는 지울 수 없고, PK인 코드는
 * 애초에 바뀌지 않는다.
 *
 * 역할 분류의 SYSTEM 같은 보호 분류는 없다 — 시드 4종(모집·세미나·프로젝트·행사)은 초기값일
 * 뿐이고(EventClassificationEntity 주석) 시스템이 코드로 가리키는 분류가 없어서, 쓰지 않는
 * 시드는 이름을 바꾸든 지우든 조직의 몫이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventCategoryServiceImpl implements EventCategoryService {

    /** indctSeqno를 생략했을 때의 기본 표시 순번. 기존 분류 뒤쪽으로 밀어 둔다 */
    private static final int DEFAULT_DISPLAY_ORDER = 99;

    private final EventClassificationRepository eventClassificationRepository;
    private final EventRepository eventRepository;
    private final EntityManager entityManager;

    /*
     * 목록 조회는 쿼리 2회다 — 분류 목록 1 + 사용 행사 수 집계 1. 분류마다 행사를 세면 그대로
     * N+1이 된다 (DB-13).
     *
     * 행사가 없는 분류는 집계 결과에 아예 나오지 않으므로 여기서 0으로 채운다 — 방금 만든
     * 분류가 목록에서 빠지면 만들자마자 화면에서 사라진 것으로 보인다.
     */
    @Override
    public List<EventCategoryResponse> getCategories() {
        Map<String, Long> eventCountByCode =
                eventRepository.countEventsGroupedByClassification().stream()
                        .collect(
                                Collectors.toMap(
                                        EventClassificationUsageCount::getEventClsfCd,
                                        EventClassificationUsageCount::getEventCount));

        return eventClassificationRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
                .map(
                        classification ->
                                EventCategoryResponse.of(
                                        classification,
                                        eventCountByCode.getOrDefault(
                                                classification.getCode(), 0L)))
                .toList();
    }

    /*
     * 분류 생성. 코드는 요청이 정하며 형식은 DTO의 @Pattern이 이미 걸렀다 (400 VALIDATION_FAILED).
     *
     * save()가 아니라 persist()인 것은 코드가 IDENTITY가 아니라 직접 넣는 PK이기 때문이다 —
     * save()는 식별자가 채워져 있으면 merge()로 돌고, merge는 행이 있으면 UPDATE로 넘어가
     * 동시 생성의 진 쪽이 409가 아니라 남의 분류 이름을 조용히 덮어쓴다
     * (RoleClassificationServiceImpl·#65와 같은 이유).
     */
    @Override
    @Transactional
    public EventCategoryResponse createCategory(EventCategoryCreateRequest request) {
        String code = request.eventClsfCd().trim();
        if (eventClassificationRepository.existsById(code)) {
            throw new GeneralException(EventErrorCode.EVENT_CLASSIFICATION_CODE_DUPLICATED);
        }

        EventClassificationEntity created =
                EventClassificationEntity.create(
                        code,
                        request.eventClsfNm().trim(),
                        request.indctSeqno() == null
                                ? DEFAULT_DISPLAY_ORDER
                                : request.indctSeqno());

        try {
            entityManager.persist(created);
            entityManager.flush();
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            // 선조회를 나란히 통과한 동시 요청은 제약 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(EventErrorCode.EVENT_CLASSIFICATION_CODE_DUPLICATED);
        }

        // 갓 만든 분류를 쓰는 행사는 있을 수 없다
        return EventCategoryResponse.of(created, 0L);
    }

    /*
     * 이름·표시 순번 변경. 코드는 본문에 아예 없으므로 바꿀 길이 없다
     * (EventCategoryUpdateRequest 주석). indctSeqno가 null이면 현재 값을 유지한다.
     */
    @Override
    @Transactional
    public EventCategoryResponse updateCategory(
            String classificationCode, EventCategoryUpdateRequest request) {

        EventClassificationEntity classification = findClassification(classificationCode);
        classification.update(
                request.eventClsfNm().trim(),
                request.indctSeqno() == null
                        ? classification.getDisplayOrder()
                        : request.indctSeqno());

        // 관리 화면이 저장 직후에도 "사용 중 N건"을 그대로 보여주므로 건수를 다시 실어 준다
        return EventCategoryResponse.of(
                classification, eventRepository.countByClassification(classification));
    }

    /*
     * 삭제. 사용 중인 분류(409 EVENT_CLASSIFICATION_IN_USE)만 막는다.
     *
     * 소속 행사를 함께 지우거나 다른 분류로 옮기지 않는 것은 의도된 것이다 — 그렇게 두면 삭제
     * 한 번으로 행사 목록의 필터가 조용히 바뀐다. 행사를 먼저 옮기게 해서 무엇이 어디로 가는지
     * 화면에서 보이게 한다 (ROLE_CLASSIFICATION_IN_USE와 같은 태도).
     */
    @Override
    @Transactional
    public void deleteCategory(String classificationCode) {
        EventClassificationEntity classification = findClassification(classificationCode);
        if (eventRepository.existsByClassification(classification)) {
            throw new GeneralException(EventErrorCode.EVENT_CLASSIFICATION_IN_USE);
        }
        eventClassificationRepository.delete(classification);
    }

    // ------------------------------------------------------------------ 헬퍼

    private EventClassificationEntity findClassification(String classificationCode) {
        return eventClassificationRepository
                .findById(classificationCode)
                .orElseThrow(
                        () -> new GeneralException(EventErrorCode.EVENT_CLASSIFICATION_NOT_FOUND));
    }
}
