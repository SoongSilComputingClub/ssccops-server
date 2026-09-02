package org.sscc.ssccopsserver.domain.event.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.ApplicationStatus;
import org.sscc.ssccopsserver.domain.event.dto.MyApplicationResponse;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.event.repository.MyApplicationParticipation;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.RequiredArgsConstructor;

/*
 * 내 신청 현황 조회의 구현 (ssccops#145).
 *
 * 새로 만드는 규칙은 없다 — DRAFT 제외는 ResponseStatus.submittedOrLater()를 그대로 쓰고,
 * 상태 파생은 ApplicationStatus.of가 갖는다. 이 클래스가 하는 일은 세 조회를 짝짓는 것뿐이다.
 *
 * ── 질의는 신청 건수와 무관하게 세 번이다 (DB-13) ────────────
 *   1. 내가 행사 폼에 낸 응답 (폼까지 페치, 정렬 포함)
 *   2. 그 폼들이 붙은 행사 (분류까지 페치)
 *   3. 그 행사들에서 내 참가자 행 (프로젝션)
 * 신청이 한 건도 없으면 1회로 끝난다 — 뒤 두 질의는 빈 in 절이 되어 DB마다 다르게 처리되고,
 * 애초에 짝지을 것이 없다.
 *
 * 순서는 1번 질의가 정한다(제출 일시 내림차순, 동률은 식별자 내림차순). 2·3번은 Map으로만
 * 쓰이므로 그 정렬을 흔들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyApplicationServiceImpl implements MyApplicationService {

    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final EventRepository eventRepository;
    private final EventParticipantRepository eventParticipantRepository;

    @Override
    public List<MyApplicationResponse> getMyApplications(MemberEntity member) {
        List<FormResponseHistoryEntity> applications =
                formResponseHistoryRepository.findEventApplicationsByMember(
                        member, ResponseStatus.submittedOrLater());
        if (applications.isEmpty()) {
            return List.of();
        }

        Map<Long, EventEntity> eventsByFormId = findEventsByFormId(applications);
        Map<Long, MyApplicationParticipation> participationsByEventId =
                findParticipationsByEventId(member, eventsByFormId.values());

        return applications.stream()
                .map(
                        application ->
                                toResponse(application, eventsByFormId, participationsByEventId))
                .toList();
    }

    private Map<Long, EventEntity> findEventsByFormId(
            List<FormResponseHistoryEntity> applications) {
        Set<Long> formIds =
                applications.stream()
                        .map(application -> application.getForm().getId())
                        .collect(Collectors.toSet());
        return eventRepository.findAllByFormIdIn(formIds).stream()
                .collect(Collectors.toMap(event -> event.getForm().getId(), Function.identity()));
    }

    /*
     * (행사, 회원)당 참가자 행은 최대 하나다(uk_event_ptcp_event_member) — 그 사실이 있어야
     * 행사 식별자를 key로 접을 수 있다.
     */
    private Map<Long, MyApplicationParticipation> findParticipationsByEventId(
            MemberEntity member, Collection<EventEntity> events) {
        Set<Long> eventIds = events.stream().map(EventEntity::getId).collect(Collectors.toSet());
        return eventParticipantRepository.findAllByMemberAndEventIdIn(member, eventIds).stream()
                .collect(
                        Collectors.toMap(
                                MyApplicationParticipation::getEventId, Function.identity()));
    }

    /*
     * 다중 응답 폼(#143)에서는 한 행사에 내 신청이 여러 줄일 수 있고, 명단 행은 그래도 하나다.
     * 그 하나의 참가 상태가 그 행사의 내 신청 전부에 실린다 — 명단은 응답이 아니라 **사람**을
     * 담으므로(uk_event_ptcp_event_member) "이 행사에 나는 확정됐다"가 신청 줄마다 다를 수 없다.
     */
    private MyApplicationResponse toResponse(
            FormResponseHistoryEntity application,
            Map<Long, EventEntity> eventsByFormId,
            Map<Long, MyApplicationParticipation> participationsByEventId) {

        EventEntity event = eventsByFormId.get(application.getForm().getId());
        MyApplicationParticipation participation = participationsByEventId.get(event.getId());
        ApplicationStatus status =
                ApplicationStatus.of(
                        participation == null ? null : participation.getPtcpSttsCd(),
                        application.getStatus());

        return MyApplicationResponse.of(
                event,
                application,
                status,
                participation == null ? null : participation.getEventPtcpId());
    }
}
