package org.sscc.ssccopsserver.domain.event.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.PublicEventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.PublicEventSummaryResponse;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantCount;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 공개(익명) 행사 조회의 구현 (ssccops#143).
 *
 * 이 클래스가 지키는 것은 하나다 — **PUBLISHED 밖의 행사는 존재하지 않는 것으로 답한다.**
 * 목록은 질의에 상태 집합을 고정해 넣고, 상세는 findByIdAndStatus로만 찾아 없으면 404다.
 * 조회한 뒤 상태를 보고 거르는 방식을 쓰지 않는 것은 그 분기 하나가 빠지는 것으로 작성 중인
 * 행사의 본문이 익명에게 나가기 때문이다 (공개 폼 조회 #35가 접수 불가 폼을 200이 아니라 409로
 * 끊은 것과 같은 판단).
 *
 * **학술 활동에서 이관된 event는 예외로 조회 시점 판정을 하나 더 탄다(#187).** 모집 시작
 * (START_RECRUITMENT)이 event를 PUBLISHED로 만들지만, 접수 기간이 지나면 공개에서 사라져야
 * 한다 — 모집이 끝난 스터디를 방문자에게 계속 노출할 이유가 없다. 상태를 배치로 되돌리지 않고
 * 여기서 거르므로(FormReceiptPolicy가 폼에서 배치를 두지 않기로 한 이유를 그대로 물려받는다:
 * 운영자 ARCHIVE와 구별 불가·종료 일시 연장 시 되돌릴 방법·멀티 인스턴스 중복 실행 방지 부재),
 * 학술국장이 접수 종료 일시를 미래로 늘리면 다음 조회부터 다시 보인다. 일반 공지형 행사는
 * 접수 상태와 무관하게 그대로 노출한다(기존 동작).
 *
 * 쓰기는 없다(@Transactional(readOnly = true) — 클래스 레벨). 익명 경로에 쓰기 자리를 두지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicEventServiceImpl implements PublicEventService {

    private final EventRepository eventRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final EventPhasePolicy eventPhasePolicy;
    private final EventReceiptPolicy eventReceiptPolicy;
    private final AcademicProgramRepository academicProgramRepository;

    /*
     * 공개 목록. 질의는 하나다 — 분류·연결 폼은 목록 질의가 함께 페치하고(EventRepository),
     * 확정 인원은 목록에 싣지 않으므로 집계도 필요 없다. 운영자 목록이 2회인 것과 갈리는 지점이며
     * 근거는 계약이다: 공개 목록에는 confirmedCount가 없다.
     *
     * 상태 필터를 파라미터로 열지 않는다. 열면 "?eventSttsCd=DRAFT"가 곧 작성 중 행사 목록이 된다.
     */
    @Override
    public List<PublicEventSummaryResponse> getPublishedEvents(String classificationCode) {
        List<EventEntity> events =
                eventRepository.findAllForList(
                        EnumSet.of(EventStatus.PUBLISHED), classificationCode);
        Set<Long> academicEventIds = academicEventIdsAmong(events);

        return events.stream()
                .filter(event -> isVisibleToPublic(event, academicEventIds))
                .map(
                        event ->
                                PublicEventSummaryResponse.of(
                                        event,
                                        eventPhasePolicy.phaseOf(event),
                                        eventReceiptPolicy.receiptStatusOf(event)))
                .toList();
    }

    /*
     * 공개 상세. 확정 인원은 운영자 상세와 같은 집계(CONFIRMED만)를 쓴다 — 대기·취소를 함께 세면
     * 화면의 "확정 N/정원"이 부풀어 방문자가 남은 자리를 잘못 읽는다.
     */
    @Override
    public PublicEventDetailResponse getPublishedEvent(Long eventId) {
        EventEntity event =
                eventRepository
                        .findByIdAndStatus(eventId, EventStatus.PUBLISHED)
                        .orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

        // 접수가 끝난 학술 event는 목록에서 빠지는 것과 같은 기준으로 상세에서도 404다 —
        // 코드를 나누면 "그 번호에 무엇인가 있다"가 새어 나간다 (findByIdAndStatus와 같은 태도)
        if (!isVisibleToPublic(event, academicEventIdsAmong(List.of(event)))) {
            throw new GeneralException(EventErrorCode.EVENT_NOT_FOUND);
        }

        long confirmedCount =
                eventParticipantRepository
                        .countByEventIds(List.of(event.getId()), EventParticipantStatus.CONFIRMED)
                        .stream()
                        .mapToLong(EventParticipantCount::getConfirmedCount)
                        .sum();

        return PublicEventDetailResponse.of(
                event,
                eventPhasePolicy.phaseOf(event),
                eventReceiptPolicy.receiptStatusOf(event),
                confirmedCount);
    }

    /*
     * 주어진 event 중 학술 활동에서 이관된 것들의 id (#187). 공개 목록/상세가 이미 읽어 온
     * event에 대해서만 물으므로 질의는 IN 하나다 — event 도메인이 학술 도메인에 묻는 유일한
     * 자리이며, 판별 규칙(1:1 관계)의 주인은 학술 도메인이다(AcademicProgramRepository).
     */
    private Set<Long> academicEventIdsAmong(List<EventEntity> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        return academicProgramRepository.findEventIdsByEventIdIn(
                events.stream().map(EventEntity::getId).toList());
    }

    /*
     * 학술 event는 접수 중(ACCEPTING)일 때만 공개한다(#187). 일반 공지형 행사는 접수 상태와
     * 무관하게 그대로 노출한다 — 기존 동작이다. 폼이 없어 receiptStatus가 null인 학술 event는
     * (정상 흐름에서는 승인 후속 처리가 늘 폼을 붙이므로 생기지 않는다) 공개하지 않는다.
     */
    private boolean isVisibleToPublic(EventEntity event, Set<Long> academicEventIds) {
        if (!academicEventIds.contains(event.getId())) {
            return true;
        }
        return eventReceiptPolicy.receiptStatusOf(event) == FormReceiptStatus.ACCEPTING;
    }
}
