package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaItemRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingAgendaEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingTransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingAgendaCount;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingAgendaRepository;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingRepository;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingServiceImpl implements MeetingService {

    private final OperationRepository operationRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingAgendaRepository meetingAgendaRepository;
    private final MemberService memberService;

    // 전이 일시의 기준 시각. 테스트에서 고정할 수 있도록 주입받는다 (ClockConfig)
    private final Clock clock;

    /*
     * oper(공통)·mtg(확장)·mtg_dtl(안건, 함께 제출된 경우)을 한 트랜잭션에서 INSERT 한다
     * (WorkServiceImpl.createWork와 같은 경계 — AR-11).
     *
     * 회의 책임자는 담당자와 항상 같은 회원이다(ssccops-web#56) — 별도 입력을 받지 않으므로
     * personInCharge를 그대로 재사용한다.
     */
    @Override
    @Transactional
    public MeetingDetailResponse createMeeting(
            MeetingCreateRequest request, MemberEntity registrant) {
        MemberEntity personInCharge =
                memberService
                        .findAssignableMember(request.personInChargeId())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER));

        Instant beginAt = toInstant(request.startAt());
        Instant endAt = toInstant(request.endAt());
        validatePeriod(beginAt, endAt);

        OperationEntity operation =
                operationRepository.save(
                        OperationEntity.createForMeeting(
                                request.title(),
                                registrant,
                                personInCharge,
                                beginAt,
                                endAt,
                                request.priority()));
        MeetingEntity meeting =
                meetingRepository.save(
                        MeetingEntity.create(
                                operation,
                                request.meetingCategory(),
                                request.attendeeScope(),
                                personInCharge,
                                request.location()));

        List<MeetingAgendaResponse> agendas = createAgendas(meeting, request.agendas(), registrant);
        return MeetingDetailResponse.of(meeting, agendas);
    }

    private List<MeetingAgendaResponse> createAgendas(
            MeetingEntity meeting, List<MeetingAgendaItemRequest> items, MemberEntity submitter) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<MeetingAgendaResponse> agendas = new ArrayList<>();
        int order = 1;
        for (MeetingAgendaItemRequest item : items) {
            OperationEntity targetOperation = resolveTargetOperation(item.targetOperationId());
            MeetingAgendaEntity agenda =
                    meetingAgendaRepository.save(
                            MeetingAgendaEntity.create(
                                    meeting,
                                    item.agendaName(),
                                    item.processStatus(),
                                    order++,
                                    targetOperation,
                                    item.content(),
                                    submitter));
            agendas.add(MeetingAgendaResponse.from(agenda));
        }
        return agendas;
    }

    private OperationEntity resolveTargetOperation(Long targetOperationId) {
        if (targetOperationId == null) {
            return null;
        }
        return operationRepository
                .findByIdAndDeletedAtIsNull(targetOperationId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.OPERATION_NOT_FOUND));
    }

    /*
     * 상세 조회(OPS-025). 쿼리는 회의 1 + 안건 목록 1로 2회다 — 안건마다 연결 운영 건·제출자를
     * 다시 조회하면 그대로 N+1이 된다(MeetingAgendaRepository의 EntityGraph가 막는다).
     */
    @Override
    public MeetingDetailResponse getMeeting(Long meetingId) {
        MeetingEntity meeting = findMeeting(meetingId);
        List<MeetingAgendaResponse> agendas =
                meetingAgendaRepository.findAllByMeetingOrderByAgendaOrderAsc(meeting).stream()
                        .map(MeetingAgendaResponse::from)
                        .toList();
        return MeetingDetailResponse.of(meeting, agendas);
    }

    /*
     * 목록 조회(신규). 쿼리는 목록 1 + 안건 건수 집계 1로 2회이며, 회의가 몇 건이든 안건이
     * 몇 건이든 이 수는 변하지 않는다(DB-13, WorkServiceImpl.searchWorks와 같은 판단).
     */
    @Override
    public List<MeetingListItemResponse> listMeetings() {
        List<MeetingEntity> meetings =
                meetingRepository.findAllByOperationDeletedAtIsNullOrderByOperationCreatedAtDesc();
        if (meetings.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> agendaCountByMeetingId =
                meetingAgendaRepository
                        .countGroupedByMeetingIds(
                                meetings.stream().map(MeetingEntity::getId).toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        MeetingAgendaCount::getMeetingId,
                                        MeetingAgendaCount::getAgendaCount));

        return meetings.stream()
                .map(
                        meeting ->
                                MeetingListItemResponse.of(
                                        meeting,
                                        agendaCountByMeetingId
                                                .getOrDefault(meeting.getId(), 0L)
                                                .intValue()))
                .toList();
    }

    /*
     * 상태 전이(OPS-026). 개회·회의록작성·종료(TR-M1~M3)는 회의 책임자(의장) 본인만 수행할 수
     * 있고, 취소(TR-M4)는 정의서가 '의장·국장 이상'을 함께 허용하므로 컨트롤러의 MEETING_MANAGE
     * 권한만으로 충분해 여기서 더 좁히지 않는다.
     */
    @Override
    @Transactional
    public MeetingTransitionResponse transitionMeeting(
            Long meetingId, MeetingTransitionRequest request, MemberEntity performer) {
        MeetingEntity meeting = findMeeting(meetingId);
        MeetingTransitionAction action = request.transition();
        if (action != MeetingTransitionAction.CANCEL && !meeting.isChairedBy(performer)) {
            throw new GeneralException(OperationErrorCode.FORBIDDEN);
        }

        MeetingStatus previousStatus = meeting.getMeetingStatus();
        boolean hasUnresolvedAgenda =
                action == MeetingTransitionAction.CLOSE
                        && meetingAgendaRepository.existsByMeetingAndProcessStatus(
                                meeting, AgendaProcessStatus.PENDING);
        Instant changedAt = Instant.now(clock);
        meeting.applyTransition(action, request.reason(), hasUnresolvedAgenda);

        return MeetingTransitionResponse.of(meeting, action, previousStatus, changedAt);
    }

    @Override
    public List<MeetingAgendaResponse> getAgendas(Long meetingId) {
        MeetingEntity meeting = findMeeting(meetingId);
        return meetingAgendaRepository.findAllByMeetingOrderByAgendaOrderAsc(meeting).stream()
                .map(MeetingAgendaResponse::from)
                .toList();
    }

    // 안건 상정(OPS-027)
    @Override
    @Transactional
    public MeetingAgendaResponse addAgenda(
            Long meetingId, MeetingAgendaItemRequest request, MemberEntity submitter) {
        MeetingEntity meeting = findMeeting(meetingId);
        meeting.requireAgendaEditable();

        OperationEntity targetOperation = resolveTargetOperation(request.targetOperationId());
        int nextOrder =
                meetingAgendaRepository
                        .findTopByMeetingOrderByAgendaOrderDesc(meeting)
                        .map(last -> last.getAgendaOrder() + 1)
                        .orElse(1);

        MeetingAgendaEntity agenda =
                meetingAgendaRepository.save(
                        MeetingAgendaEntity.create(
                                meeting,
                                request.agendaName(),
                                request.processStatus(),
                                nextOrder,
                                targetOperation,
                                request.content(),
                                submitter));
        return MeetingAgendaResponse.from(agenda);
    }

    // 안건 수정(OPS-028)
    @Override
    @Transactional
    public MeetingAgendaResponse updateAgenda(
            Long meetingId, Long agendaId, MeetingAgendaUpdateRequest request) {
        MeetingEntity meeting = findMeeting(meetingId);
        meeting.requireAgendaEditable();

        MeetingAgendaEntity agenda = findAgenda(meeting, agendaId);
        agenda.update(request.content(), request.resultContent(), request.processStatus());
        return MeetingAgendaResponse.from(agenda);
    }

    // 안건 상정 철회(OPS-029)
    @Override
    @Transactional
    public void withdrawAgenda(Long meetingId, Long agendaId) {
        MeetingEntity meeting = findMeeting(meetingId);
        meeting.requireAgendaWithdrawable();

        MeetingAgendaEntity agenda = findAgenda(meeting, agendaId);
        meetingAgendaRepository.delete(agenda);
    }

    private MeetingEntity findMeeting(Long meetingId) {
        return meetingRepository
                .findByIdAndOperationDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.MEETING_NOT_FOUND));
    }

    /*
     * 회의 삭제(#125). 자기 operation만 소프트 삭제한다 — 안건(mtg_dtl)은 지우지 않는다.
     *
     * 대상 존재 여부와 삭제 여부를 함께 판정해야 하므로(404 vs 409) findByIdAndOperationDeletedAtIsNull
     * 대신 필터 없는 findById를 쓴다 — WorkServiceImpl.deleteWork와 같은 이유다.
     *
     * 상태(mtg_stts_cd)와 무관하게 항상 허용한다 — 종료(CLOSED)·취소(CANCELED)된 회의도
     * 삭제할 수 있다.
     */
    @Override
    @Transactional
    public void deleteMeeting(Long meetingId) {
        MeetingEntity meeting =
                meetingRepository
                        .findById(meetingId)
                        .orElseThrow(
                                () -> new GeneralException(OperationErrorCode.MEETING_NOT_FOUND));

        OperationEntity operation = meeting.getOperation();
        if (operation.isDeleted()) {
            throw new GeneralException(OperationErrorCode.ALREADY_DELETED);
        }
        operation.softDelete(Instant.now(clock));
    }

    private MeetingAgendaEntity findAgenda(MeetingEntity meeting, Long agendaId) {
        return meetingAgendaRepository
                .findByIdAndMeeting(agendaId, meeting)
                .orElseThrow(
                        () -> new GeneralException(OperationErrorCode.MEETING_AGENDA_NOT_FOUND));
    }

    private void validatePeriod(Instant beginAt, Instant endAt) {
        if (beginAt != null && endAt != null && endAt.isBefore(beginAt)) {
            throw new GeneralException(OperationErrorCode.INVALID_OPERATION_PERIOD);
        }
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
