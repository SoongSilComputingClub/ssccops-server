package org.sscc.ssccopsserver.domain.event.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantMutationResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantRegisterRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantWarningResponse;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.FormResponseService;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 행사 신청 목록·참가자 명단의 구현 (ssccops#146).
 *
 * 새로 만드는 규칙은 명단에 관한 것뿐이다 — 신청 목록은 FormResponseService에 위임하고,
 * 심사는 폼 응답 검토 API(#141)가 그대로 맡는다. 참가 상태 전이표도 여기 없다
 * (EventParticipantEntity.changeStatus, LY-02).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventParticipationServiceImpl implements EventParticipationService {

    private final EventRepository eventRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final MemberRepository memberRepository;

    /*
     * 신청 목록은 폼 응답 목록이다. 규칙(DRAFT 제외 기본값·정렬·회원 조인)을 복제하지 않고
     * 위임한다 — 복제하면 폼 화면과 행사 화면이 같은 응답을 다른 순서·다른 범위로 보여준다.
     */
    private final FormResponseService formResponseService;

    @Override
    public List<FormResponseSummaryResponse> getApplications(
            Long eventId, ResponseStatus statusCode) {
        EventEntity event = findEvent(eventId);
        FormEntity form = event.getForm();
        if (form == null) {
            throw new GeneralException(EventErrorCode.EVENT_HAS_NO_FORM);
        }
        return formResponseService.getResponses(form.getId(), statusCode);
    }

    /*
     * 명단 조회. 상태 미지정은 **취소를 포함한 전부**다 — 폼 응답 목록이 기본값에서 DRAFT를
     * 빼는 것과 갈리는 지점인데, 그쪽은 "아직 내지 않은 답"을 심사 목록에서 감추는 것이고
     * 여기 취소는 실제로 일어난 일이라 명단에서 감추면 영구 보존(D16)이 뜻을 잃는다.
     */
    @Override
    public List<EventParticipantResponse> getParticipants(
            Long eventId, EventParticipantStatus statusCode) {
        EventEntity event = findEvent(eventId);
        Collection<EventParticipantStatus> statuses =
                statusCode == null
                        ? EnumSet.allOf(EventParticipantStatus.class)
                        : EnumSet.of(statusCode);
        return eventParticipantRepository
                .findAllByEventAndStatusInOrderByIdAsc(event, statuses)
                .stream()
                .map(EventParticipantResponse::from)
                .toList();
    }

    /*
     * 참가자 등록.
     *
     * 검사 순서는 근거 → 대상 → 중복이다. 근거가 둘 다 왔는지를 먼저 보는 것은 그 요청이
     * 무엇을 하려는지 자체가 정해지지 않은 상태라, 회원을 찾는 것도 중복을 세는 것도 의미가
     * 없기 때문이다.
     *
     * **정원은 어디에서도 검사하지 않는다**(D5). 초과 여부는 저장한 뒤 세어 응답에 싣는다.
     */
    @Override
    @Transactional
    public EventParticipantMutationResponse registerParticipant(
            Long eventId, EventParticipantRegisterRequest request, MemberEntity registrant) {

        EventEntity event = findEvent(eventId);
        if (!request.hasExactlyOneSource()) {
            throw new GeneralException(EventErrorCode.INVALID_PARTICIPANT_SOURCE);
        }

        FormResponseHistoryEntity formResponse =
                request.isResponseBased()
                        ? findAcceptedApplication(event, request.formRspnsId())
                        : null;
        MemberEntity member =
                formResponse != null ? formResponse.getMember() : findMember(request.mbrId());

        // 선조회는 대부분의 요청을 500이 아니라 바로 409로 돌려주기 위한 것이고, 최종 방어선은 UNIQUE다
        if (eventParticipantRepository.existsByEventAndMember(event, member)) {
            throw new GeneralException(EventErrorCode.EVENT_PARTICIPANT_DUPLICATED);
        }

        EventParticipantEntity participant =
                EventParticipantEntity.register(
                        event, member, request.ptcpSttsCd(), formResponse, registrant);
        try {
            eventParticipantRepository.saveAndFlush(participant);
        } catch (DataIntegrityViolationException ex) {
            // 선조회를 나란히 통과한 동시 등록은 uk_event_ptcp_event_member 위반으로만 드러난다
            throw new GeneralException(EventErrorCode.EVENT_PARTICIPANT_DUPLICATED);
        }

        return toMutationResponse(event, participant, warningsOf(member));
    }

    /*
     * 참가 상태 전이. 전이표와 거절은 엔티티가 갖고 여기서는 범위 검사와 응답 조립만 한다
     * (LY-02 · EventServiceImpl.changeStatus와 같은 방식).
     *
     * 경고를 함께 싣는 것은 승격이 등록과 같은 사건이기 때문이다 — 대기로 올려 둔 사이에
     * 회원이 탈퇴했다면 확정하는 시점에 알려야 한다.
     */
    @Override
    @Transactional
    public EventParticipantMutationResponse changeParticipantStatus(
            Long eventId, Long eventParticipantId, EventParticipantStatusChangeRequest request) {

        EventEntity event = findEvent(eventId);
        EventParticipantEntity participant =
                eventParticipantRepository
                        .findByIdAndEvent(eventParticipantId, event)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                EventErrorCode.EVENT_PARTICIPANT_NOT_FOUND));

        participant.changeStatus(request.ptcpSttsCd());

        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 — 먼저 흘려보내야 응답이 실제 값이 된다
        eventParticipantRepository.flush();

        return toMutationResponse(event, participant, warningsOf(participant.getMember()));
    }

    // ------------------------------------------------------------------ 헬퍼

    private EventEntity findEvent(Long eventId) {
        return eventRepository
                .findById(eventId)
                .orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));
    }

    /*
     * 응답 기반 등록의 근거 확인. 두 가지를 함께 본다.
     *
     * 하나는 **범위**다 — 응답을 식별자만으로 찾으면 다른 행사(다른 폼)의 지원서를 근거로 남의
     * 행사 명단에 사람을 올릴 수 있다. findByIdAndForm으로만 찾으며 없는 응답과 남의 폼 응답은
     * 같은 404다(FormResponseServiceImpl과 같은 자리).
     *
     * 다른 하나는 **심사 결과**다. ACCEPTED가 아니면 409 — 심사와 등록은 순서가 있는 두 사건이고,
     * 수락되지 않은 응답으로 명단에 올리면 폼의 심사 결과와 참가자 명단이 서로 다른 사실을
     * 말하게 된다.
     */
    private FormResponseHistoryEntity findAcceptedApplication(EventEntity event, Long responseId) {
        FormEntity form = event.getForm();
        if (form == null) {
            throw new GeneralException(EventErrorCode.EVENT_HAS_NO_FORM);
        }

        FormResponseHistoryEntity response =
                formResponseHistoryRepository
                        .findByIdAndForm(responseId, form)
                        .orElseThrow(
                                () -> new GeneralException(FormErrorCode.FORM_RESPONSE_NOT_FOUND));

        if (response.getStatus() != ResponseStatus.ACCEPTED) {
            throw new GeneralException(EventErrorCode.APPLICATION_NOT_ACCEPTED);
        }
        return response;
    }

    private MemberEntity findMember(Long memberId) {
        return memberRepository
                .findById(memberId)
                .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /*
     * 조직을 떠난 회원을 명단에 올렸을 때의 경고 (§8-5 · #78의 warnings 패턴).
     *
     * **막지 않는다.** 졸업생 홈커밍처럼 떠난 사람이 참가자인 것이 정상인 행사가 있고, 모집
     * 행사라면 잘못 고른 것이다 — 서버가 어느 쪽인지 알 수 없으므로 사람이 보게 한다.
     * 남길 것이 없으면 빈 배열이지 null이 아니다.
     */
    private List<EventParticipantWarningResponse> warningsOf(MemberEntity member) {
        List<EventParticipantWarningResponse> warnings = new ArrayList<>();
        MemberStatusCode status = MemberStatusCode.from(member.getMembershipStatus().getCode());
        if (status == MemberStatusCode.WITHDRAWN) {
            warnings.add(EventParticipantWarningResponse.memberWithdrawn());
        } else if (status == MemberStatusCode.EXPELLED) {
            warnings.add(EventParticipantWarningResponse.memberExpelled());
        }
        return warnings;
    }

    /*
     * 확정 인원은 이 요청이 반영된 뒤의 값이다 — 등록·승격이 정원을 넘겼는지를 화면이 판단하는
     * 재료이므로, 요청 전의 숫자를 내리면 방금 만든 초과를 보여주지 못한다.
     */
    private EventParticipantMutationResponse toMutationResponse(
            EventEntity event,
            EventParticipantEntity participant,
            List<EventParticipantWarningResponse> warnings) {
        long confirmedCount =
                eventParticipantRepository.countByEventAndStatus(
                        event, EventParticipantStatus.CONFIRMED);
        return EventParticipantMutationResponse.of(
                participant, confirmedCount, event.getParticipantLimitCount(), warnings);
    }
}
