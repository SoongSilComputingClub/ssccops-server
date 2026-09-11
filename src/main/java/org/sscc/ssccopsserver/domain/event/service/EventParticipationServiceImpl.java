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
        FormResponseHistoryEntity formResponse = resolveApplication(event, request);
        MemberEntity member = resolveMember(formResponse, request);

        // 선조회는 대부분의 요청을 500이 아니라 바로 409로 돌려주기 위한 것이고, 최종 방어선은 UNIQUE다
        if (eventParticipantRepository.existsByEventAndMember(event, member)) {
            throw new GeneralException(EventErrorCode.EVENT_PARTICIPANT_DUPLICATED);
        }

        return toMutationResponse(
                event, register(event, member, request, formResponse, registrant), member);
    }

    /*
     * 등록이거나, 이미 명단에 있으면 상태 맞추기 (#198).
     *
     * 등록과 갈리는 것은 **중복을 어떻게 읽는가** 하나다. 등록에서 이미 있는 회원은 잘못 누른
     * 것이지만(409), 선발을 다시 저장하는 요청에서 그것은 "이 사람의 자리를 이 값으로 두라"는
     * 정상적인 뜻이다 — 그래서 같은 값이면 아무 일도 하지 않고 다른 값이면 전이한다.
     *
     * **전이표를 다시 적지 않는다**(EventParticipantEntity.changeStatus). 확정↔대기의 왕복이
     * 성립하는지도, 취소된 사람을 되살릴 수 없다는 것도 전부 그 표가 답한다 — 여기서 한 벌 더
     * 판정하면 이 경로만 표 밖의 전이를 허용하는 자리가 생긴다.
     *
     * **같은 값이면 changeStatus를 부르지 않는다.** 그 표에서 재지정은 400인데(아무것도 바꾸지
     * 않은 요청이 mdfcn_dt만 갱신해 실제 승격·강등 시점을 흐린다), 화면의 상태를 통째로 다시
     * 보내는 요청에서 '바뀌지 않은 줄'은 오류가 아니라 대부분이다. 부르지 않으면 그 두 사실이
     * 부딪히지 않는다 — 규칙을 완화하는 것이 아니라 해당하지 않게 두는 것이다.
     *
     * **신청 근거(form_rspns_id)는 갱신하지 않는다.** updatable = false로 잠긴 값이고, "이
     * 사람이 왜 명단에 있는가"는 처음 올린 근거가 답한다 — 다시 저장할 때마다 근거가 옮겨
     * 다니면 그 값은 아무것도 증명하지 못한다.
     *
     * **도달 상태는 등록과 같은 어휘다**(EventParticipantStatus.isRegistrable). 명단 행이 이미
     * 있으면 전이표가 CONFIRMED→CANCELLED를 허용하므로, 그 검사가 없으면 '이미 뽑힌 신청자'에
     * 한해 이 경로가 취소 API가 된다 — 부르는 쪽(모집 선발)이 고르는 값은 확정과 대기뿐이고,
     * 취소는 명단 화면의 조작이라 자격도 화면도 다르다. 판정은 새로 적지 않고 등록이 쓰는 그
     * 술어를 그대로 쓴다.
     */
    @Override
    @Transactional
    public EventParticipantMutationResponse registerOrUpdateParticipant(
            Long eventId, EventParticipantRegisterRequest request, MemberEntity registrant) {

        if (!request.ptcpSttsCd().isRegistrable()) {
            throw new GeneralException(EventErrorCode.INVALID_PARTICIPANT_REGISTRATION_STATUS);
        }

        EventEntity event = findEvent(eventId);
        FormResponseHistoryEntity formResponse = resolveApplication(event, request);
        MemberEntity member = resolveMember(formResponse, request);

        EventParticipantEntity participant =
                eventParticipantRepository.findByEventAndMember(event, member).orElse(null);
        if (participant == null) {
            return toMutationResponse(
                    event, register(event, member, request, formResponse, registrant), member);
        }

        if (participant.getStatus() != request.ptcpSttsCd()) {
            participant.changeStatus(request.ptcpSttsCd());
        }

        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 (changeParticipantStatus와 같은 이유)
        eventParticipantRepository.flush();

        return toMutationResponse(event, participant, member);
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

        return toMutationResponse(event, participant, participant.getMember());
    }

    // ------------------------------------------------------------------ 헬퍼

    /*
     * 살아 있는 행사만 찾는다 (#347). 지워진 행사의 신청 목록·참가자 명단은 없는 행사와 같은
     * 404다 — 지운 행사의 명단을 계속 고칠 수 있으면 "지웠다"의 뜻이 화면마다 달라진다. 명단
     * 행 자체는 남으므로(D16 · 영구 보존) 되살리면 그대로 돌아온다.
     */
    private EventEntity findEvent(Long eventId) {
        return eventRepository
                .findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));
    }

    /*
     * 근거 해석. 두 등록 경로가 같은 헬퍼를 지나는 것은 근거 규칙(상호 배타·응답의 범위와
     * 심사 결과)이 무엇을 하려는 요청인지와 무관하기 때문이다 — 한쪽에만 적으면 그쪽만 남의
     * 폼 응답을 근거로 받는다.
     */
    private FormResponseHistoryEntity resolveApplication(
            EventEntity event, EventParticipantRegisterRequest request) {
        if (!request.hasExactlyOneSource()) {
            throw new GeneralException(EventErrorCode.INVALID_PARTICIPANT_SOURCE);
        }
        return request.isResponseBased()
                ? findAcceptedApplication(event, request.formRspnsId())
                : null;
    }

    private MemberEntity resolveMember(
            FormResponseHistoryEntity formResponse, EventParticipantRegisterRequest request) {
        return formResponse != null ? formResponse.getMember() : findMember(request.mbrId());
    }

    private EventParticipantEntity register(
            EventEntity event,
            MemberEntity member,
            EventParticipantRegisterRequest request,
            FormResponseHistoryEntity formResponse,
            MemberEntity registrant) {
        EventParticipantEntity participant =
                EventParticipantEntity.register(
                        event, member, request.ptcpSttsCd(), formResponse, registrant);
        try {
            eventParticipantRepository.saveAndFlush(participant);
        } catch (DataIntegrityViolationException ex) {
            // 선조회를 나란히 통과한 동시 등록은 uk_event_ptcp_event_member 위반으로만 드러난다
            throw new GeneralException(EventErrorCode.EVENT_PARTICIPANT_DUPLICATED);
        }
        return participant;
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
            EventEntity event, EventParticipantEntity participant, MemberEntity member) {
        long confirmedCount =
                eventParticipantRepository.countByEventAndStatus(
                        event, EventParticipantStatus.CONFIRMED);
        return EventParticipantMutationResponse.of(
                participant, confirmedCount, event.getParticipantLimitCount(), warningsOf(member));
    }
}
