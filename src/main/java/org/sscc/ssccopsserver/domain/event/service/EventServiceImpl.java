package org.sscc.ssccopsserver.domain.event.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.EventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventSaveRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventSummaryResponse;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantCount;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 행사 CRUD·게시 전이의 구현 (ssccops#139).
 *
 * 지키는 것은 둘이다 — 폼은 최대 한 행사에만 전속되고(D11 · FORM_ALREADY_LINKED), 신청이
 * 발생한 연결은 움직이지 않는다(D11 · EVENT_FORM_IN_USE).
 *
 * **행사를 지우는 경로는 없다** (ssccops ADR-0014). 예전에는 참가자가 없을 때만 하드 삭제를
 * 허용했는데(D9), 그 규칙이 지키려던 것("행사를 지우면 명단이 갈 곳을 잃는다" · D16)을
 * 보관(ARCHIVE)이 이미 지킨다 — 지우지 않으면 명단도 R2 오브젝트도 갈 곳을 잃지 않는다.
 * 그래서 D9와 EVENT_HAS_PARTICIPANT가 함께 사라졌다.
 *
 * 모집 판정(receiptStatus)은 EventReceiptPolicy(그 안에서 FormReceiptPolicy)를, 진행 단계
 * (eventPhase)는 EventPhasePolicy를 호출만 한다 — 판정을 여기 복제하면 폼 화면과 행사 화면이
 * 같은 폼을 다르게 말한다(D3). 공개 조회(ssccops#143)도 같은 두 정책을 부른다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventServiceImpl implements EventService {

    /*
     * 본문(mtxt_cn) 글자 수 상한. DB는 TEXT라 제한하지 않으므로 저장 API가 최종 방어선이다 —
     * 폼 응답의 MAX_ANSWER_TOTAL_LENGTH(ResponseAnswerValidator)와 같은 값·같은 판단이다.
     */
    private static final int MAX_CONTENT_LENGTH = 100_000;

    private final EventRepository eventRepository;
    private final EventClassificationRepository eventClassificationRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final FormRepository formRepository;
    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final EventReceiptPolicy eventReceiptPolicy;
    private final EventPhasePolicy eventPhasePolicy;

    /*
     * 행사 목록. 쿼리는 행사(분류·폼 페치 포함) 1 + 확정 참가자 집계 1로 2회다 — 행사마다
     * 참가자를 세면 그대로 N+1이 된다 (DB-13, 폼 목록의 3회 선례).
     *
     * 페이징을 두지 않은 것은 폼 목록(#32)과 같은 결정이다 — 화면이 필터 결과를 한 번에 그린다.
     */
    @Override
    public List<EventSummaryResponse> getEvents(String classificationCode, EventStatus statusCode) {
        // 상태 미지정은 "전체"다. NULL 비교 대신 전체 상태 집합을 넘긴다 (EventRepository 주석)
        Collection<EventStatus> statuses =
                statusCode == null ? EnumSet.allOf(EventStatus.class) : EnumSet.of(statusCode);

        List<EventEntity> events = eventRepository.findAllForList(statuses, classificationCode);
        if (events.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 뒤따르는 집계를 아예 보내지 않는다
            return List.of();
        }

        List<Long> eventIds = events.stream().map(EventEntity::getId).toList();
        Map<Long, Long> confirmedCountByEventId =
                eventParticipantRepository
                        .countByEventIds(eventIds, EventParticipantStatus.CONFIRMED)
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        EventParticipantCount::getEventId,
                                        EventParticipantCount::getConfirmedCount));

        return events.stream()
                .map(
                        event ->
                                EventSummaryResponse.of(
                                        event,
                                        eventPhasePolicy.phaseOf(event),
                                        eventReceiptPolicy.receiptStatusOf(event),
                                        confirmedCountByEventId.getOrDefault(event.getId(), 0L)))
                .toList();
    }

    @Override
    public EventDetailResponse getEvent(Long eventId) {
        EventEntity event = findEvent(eventId);
        return toDetail(event);
    }

    /*
     * 행사 생성. 상태는 항상 DRAFT다(EventEntity.create) — 만들자마자 공개되는 경로를 두지
     * 않는다. 생성자는 인증 주체이며 요청 본문이 지정할 수 없다.
     */
    @Override
    @Transactional
    public EventDetailResponse createEvent(EventSaveRequest request, MemberEntity creator) {
        requireContentWithinLimit(request.mtxtCn());
        EventClassificationEntity classification = findClassification(request.eventClsfCd());
        FormEntity form = resolveForm(request.formId());
        if (form != null && eventRepository.existsByForm(form)) {
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }

        EventEntity event =
                EventEntity.create(
                        classification,
                        creator,
                        request.eventTtl(),
                        request.mtxtCn(),
                        request.thmbUrlAddr(),
                        form,
                        toInstant(request.eventBgngDt()),
                        toInstant(request.eventEndDt()),
                        request.plcNm(),
                        request.ptcpLmtCnt());

        try {
            eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException ex) {
            // 선조회를 나란히 통과한 동시 연결은 uk_event_form 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }

        return toDetail(event);
    }

    /*
     * 행사 수정. 상태(event_stts_cd)는 이 경로로 바뀌지 않는다 — 요청 DTO에 필드 자체가 없고
     * (폼 PUT 패턴), 상태를 바꾸는 길은 changeStatus 하나다.
     *
     * 폼 연결 규칙(D11)은 연결이 실제로 바뀔 때만 검사한다. 같은 formId를 그대로 되돌려
     * 보내는 저장(편집 화면이 늘 하는 일)은 신청이 몇 건이든 통과해야 한다 — 연결을 움직이지
     * 않는 저장까지 막으면 신청이 시작된 행사는 오타 하나 못 고친다.
     */
    @Override
    @Transactional
    public EventDetailResponse updateEvent(Long eventId, EventSaveRequest request) {
        EventEntity event = findEvent(eventId);
        requireContentWithinLimit(request.mtxtCn());
        EventClassificationEntity classification = findClassification(request.eventClsfCd());

        FormEntity currentForm = event.getForm();
        Long currentFormId = currentForm == null ? null : currentForm.getId();
        boolean linkChanged = !Objects.equals(currentFormId, request.formId());

        FormEntity nextForm = currentForm;
        if (linkChanged) {
            /*
             * 신청 발생 후에는 연결을 움직일 수 없다(D11). "신청이 있다"는 연결된 폼의 제출 이후
             * 응답(임시저장 제외) 또는 이 행사의 참가자(수동 등록 포함) 어느 쪽으로든 성립한다 —
             * 응답만 보면 전화 접수로 참가자를 올린 행사의 연결이 자유로워지고, 참가자만 보면
             * 아직 심사 전인 응답이 소속을 잃는다.
             */
            if (currentForm != null
                    && formResponseHistoryRepository.existsByFormAndStatusIn(
                            currentForm, ResponseStatus.submittedOrLater())) {
                throw new GeneralException(EventErrorCode.EVENT_FORM_IN_USE);
            }
            if (eventParticipantRepository.existsByEvent(event)) {
                throw new GeneralException(EventErrorCode.EVENT_FORM_IN_USE);
            }

            nextForm = resolveForm(request.formId());
            if (nextForm != null && eventRepository.existsByFormAndIdNot(nextForm, event.getId())) {
                throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
            }
        }

        event.update(
                classification,
                request.eventTtl(),
                request.mtxtCn(),
                request.thmbUrlAddr(),
                nextForm,
                toInstant(request.eventBgngDt()),
                toInstant(request.eventEndDt()),
                request.plcNm(),
                request.ptcpLmtCnt());

        try {
            // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 — 먼저 흘려보내야 응답의 수정 일시가 실제 값이 된다
            eventRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 선조회를 나란히 통과한 동시 연결은 uk_event_form 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }

        return toDetail(event);
    }

    /*
     * 게시 상태 전이. 전이표·거절은 EventEntity.changeStatus가 갖고 여기서는 조회와 응답
     * 조립만 한다 (LY-02 · FormServiceImpl.changeStatus 선례).
     */
    @Override
    @Transactional
    public EventDetailResponse changeStatus(Long eventId, EventStatusChangeRequest request) {
        EventEntity event = findEvent(eventId);
        event.changeStatus(request.action());

        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다
        eventRepository.flush();

        return toDetail(event);
    }

    // ------------------------------------------------------------------ 헬퍼

    private EventEntity findEvent(Long eventId) {
        return eventRepository
                .findById(eventId)
                .orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));
    }

    private EventClassificationEntity findClassification(String classificationCode) {
        return eventClassificationRepository
                .findById(classificationCode)
                .orElseThrow(
                        () -> new GeneralException(EventErrorCode.EVENT_CLASSIFICATION_NOT_FOUND));
    }

    /** 폼 연결 해석. NULL은 폼 없는 공지(D11)이고, 없는 폼은 404다 */
    private FormEntity resolveForm(Long formId) {
        if (formId == null) {
            return null;
        }
        return formRepository
                .findById(formId)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }

    private void requireContentWithinLimit(String contentMarkdown) {
        if (contentMarkdown != null && contentMarkdown.length() > MAX_CONTENT_LENGTH) {
            throw new GeneralException(EventErrorCode.EVENT_CONTENT_TOO_LARGE);
        }
    }

    private EventDetailResponse toDetail(EventEntity event) {
        long confirmedCount =
                eventParticipantRepository
                        .countByEventIds(List.of(event.getId()), EventParticipantStatus.CONFIRMED)
                        .stream()
                        .mapToLong(EventParticipantCount::getConfirmedCount)
                        .sum();
        return EventDetailResponse.of(
                event,
                eventPhasePolicy.phaseOf(event),
                eventReceiptPolicy.receiptStatusOf(event),
                confirmedCount);
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
