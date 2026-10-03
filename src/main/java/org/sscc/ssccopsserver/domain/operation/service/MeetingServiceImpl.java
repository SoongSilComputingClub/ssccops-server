package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaItemRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaPromoteResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingAgendaEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingTransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingAgendaCount;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingAgendaRepository;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingRepository;
import org.sscc.ssccopsserver.domain.operation.repository.OperationDetailIds;
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

    // 드래프트 안건 승격(#625)이 업무 등록을 그대로 부른다 — 업무를 만드는 규칙을 두 곳에 두지 않는다
    private final WorkService workService;

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
        List<MeetingAgendaEntity> agendas = new ArrayList<>();
        int order = 1;
        for (MeetingAgendaItemRequest item : items) {
            OperationEntity targetOperation = resolveTargetOperation(item.targetOperationId());
            agendas.add(
                    meetingAgendaRepository.save(
                            MeetingAgendaEntity.create(
                                    meeting,
                                    item.agendaName(),
                                    item.processStatus(),
                                    order++,
                                    targetOperation,
                                    item.content(),
                                    submitter)));
        }
        return toAgendaResponses(agendas);
    }

    /*
     * 안건 응답 묶음. 연결 안건의 targetOperation.targetId(업무·하위 업무·회의의 상세 ID · #635)를
     * 운영 건 묶음으로 한 번에 읽는다 — 안건이 몇 건이든 쿼리 1회다(DB-13). 드래프트만 있으면
     * 쿼리하지 않는다.
     */
    private List<MeetingAgendaResponse> toAgendaResponses(List<MeetingAgendaEntity> agendas) {
        List<Long> operationIds =
                agendas.stream()
                        .map(MeetingAgendaEntity::getOperation)
                        .filter(Objects::nonNull)
                        .map(OperationEntity::getId)
                        .distinct()
                        .toList();
        Map<Long, OperationDetailIds> detailIdsByOperationId =
                operationIds.isEmpty()
                        ? Map.of()
                        : operationRepository.findDetailIdsByOperationIds(operationIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                OperationDetailIds::getOperationId,
                                                Function.identity(),
                                                (first, second) -> first));
        return agendas.stream()
                .map(
                        agenda -> {
                            OperationEntity operation = agenda.getOperation();
                            OperationDetailIds detailIds =
                                    operation == null
                                            ? null
                                            : detailIdsByOperationId.get(operation.getId());
                            Long targetId =
                                    detailIds == null
                                            ? null
                                            : detailIds.idFor(operation.getOperationType());
                            return MeetingAgendaResponse.from(agenda, targetId);
                        })
                .toList();
    }

    private MeetingAgendaResponse toAgendaResponse(MeetingAgendaEntity agenda) {
        return toAgendaResponses(List.of(agenda)).get(0);
    }

    /*
     * 안건이 가리키는 운영 건. 드래프트 안건(제목만 있는 안건 · ADR-0059)은 운영 건이 없으므로
     * 널을 돌려준다 — «둘 중 하나»는 요청 DTO와 MeetingAgendaEntity.create가 판정한다.
     */
    private OperationEntity resolveTargetOperation(Long targetOperationId) {
        if (targetOperationId == null) {
            return null;
        }
        return operationRepository
                .findByIdAndDeletedAtIsNull(targetOperationId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.OPERATION_NOT_FOUND));
    }

    /*
     * 상세 조회(OPS-025). 쿼리는 회의 1 + 안건 목록 1 + 안건이 가리키는 상세 ID 1로 3회다 —
     * 안건마다 연결 운영 건·제출자를 다시 조회하면 그대로 N+1이 된다(MeetingAgendaRepository의
     * EntityGraph와 toAgendaResponses가 막는다).
     */
    @Override
    public MeetingDetailResponse getMeeting(Long meetingId) {
        MeetingEntity meeting = findMeeting(meetingId);
        List<MeetingAgendaResponse> agendas =
                toAgendaResponses(
                        meetingAgendaRepository.findAllByMeetingOrderByAgendaOrderAsc(meeting));
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
        return toAgendaResponses(
                meetingAgendaRepository.findAllByMeetingOrderByAgendaOrderAsc(meeting));
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
        return toAgendaResponse(agenda);
    }

    // 안건 수정(OPS-028)
    @Override
    @Transactional
    public MeetingAgendaResponse updateAgenda(
            Long meetingId, Long agendaId, MeetingAgendaUpdateRequest request) {
        MeetingEntity meeting = findMeeting(meetingId);
        meeting.requireAgendaEditable();

        MeetingAgendaEntity agenda = findAgenda(meeting, agendaId);
        agenda.update(
                request.agendaName(),
                request.content(),
                request.resultContent(),
                request.processStatus());
        return toAgendaResponse(agenda);
    }

    /*
     * 드래프트 안건을 업무로 승격한다 (#625 · ADR-0059). 업무 등록(WorkService.createWork)을 그대로
     * 부르고, 만든 업무의 운영 건을 안건에 잇는다 — 이 메서드의 트랜잭션에 createWork가 합류하므로
     * 업무 생성과 안건 연결은 **한 트랜잭션**이다. 연결이 실패하면 업무도 남지 않는다.
     *
     * 업무의 필수 값(제목·유형·담당자)은 요청이 준다. 서버가 안건 제목을 업무 제목으로 채우거나
     * 유형·담당자를 정하지 않는 것은 결정이다(ADR-0059 «승격의 필수 값») — 화면은 업무 등록 시트를
     * 안건 제목으로 미리 채워 열고, 사람이 고른 값이 그대로 온다.
     *
     * 종료·취소된 회의에서도 승격한다(#634 · ssccops#573) — 승격은 안건 내용을 고치는 게 아니라
     * 업무를 만드는 일이라, 회의가 끝난 뒤 «그때 나온 얘기를 업무로» 옮기는 길을 막지 않는다. 기각:
     * 안건 수정과 같이 requireAgendaEditable로 409(MEETING_CLOSED) — #625가 그렇게 시작했으나
     * 운영진 결정(2026-10-03)으로 뺐다. 안건 추가·수정·철회는 그대로 막는다.
     * 순서는 회의 → 안건 → «이미 연결됨»을 업무 INSERT 전에 본다(MeetingAgendaEntity.requireDraft).
     */
    @Override
    @Transactional
    public MeetingAgendaPromoteResponse promoteAgendaToWork(
            Long meetingId, Long agendaId, WorkCreateRequest request, MemberEntity registrant) {
        MeetingEntity meeting = findMeeting(meetingId);
        MeetingAgendaEntity agenda = findAgenda(meeting, agendaId);
        agenda.requireDraft();

        WorkCreateResponse work = workService.createWork(request, registrant);
        OperationEntity operation =
                operationRepository
                        .findById(work.operationId())
                        .orElseThrow(
                                () -> new GeneralException(OperationErrorCode.OPERATION_NOT_FOUND));
        agenda.promoteTo(operation);
        return new MeetingAgendaPromoteResponse(toAgendaResponse(agenda), work);
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
