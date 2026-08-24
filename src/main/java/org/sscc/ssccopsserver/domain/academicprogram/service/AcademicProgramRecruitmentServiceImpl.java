package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantRegisterRequest;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.service.EventParticipationService;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseReviewRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.service.FormResponseService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 팀원 명단·모집 신청자·선발의 구현 (#138).
 *
 * 이 클래스에서 새로 만드는 규칙은 둘뿐이다 — "모집이 시작된 뒤라야 신청자를 다룬다"
 * (RECRUITMENT_NOT_STARTED)와 "심사와 등록은 한 건이다"(selectMembers의 트랜잭션). 나머지는
 * 전부 위임이다:
 *
 *   심사     → FormResponseService.reviewResponse           (#141 · 전이표·검토 이력·처리자)
 *   등록     → EventParticipationService.registerParticipant (ssccops#146 · 근거·중복·상태 검사)
 *   명단 조회 → EventParticipantRepository                    (ssccops#146의 질의 그대로)
 *
 * 명단 조회만 리포지토리를 직접 부르는 것은 응답 모양이 달라서다. 행사 쪽
 * EventParticipationService.getParticipants는 학번·학과·등급까지 실은
 * EventParticipantResponse를 돌려주는데 그 경로는 EVENT_MANAGE로 잠겨 있고 이쪽은 인증만이다
 * (AcademicProgramMemberResponse 주석). 질의는 같은 것을 쓰므로 정렬·필터 규칙은 한 벌로 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramRecruitmentServiceImpl implements AcademicProgramRecruitmentService {

    /** 심사 결과를 ACCEPTED로 고정한 검토 요청. 선발은 수락 외의 결론에 도달할 수 없다 */
    private static final FormResponseReviewRequest ACCEPT_REVIEW =
            new FormResponseReviewRequest(ResponseStatus.ACCEPTED, null);

    private final AcademicProgramRepository academicProgramRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;
    private final FormResponseService formResponseService;
    private final EventParticipationService eventParticipationService;

    @Override
    public List<AcademicProgramMemberResponse> getMembers(
            Long academicProgramId, EventParticipantStatus participantStatus) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        return membersOf(academicProgram, participantStatus);
    }

    /*
     * 검사 순서는 넓은 것부터다 — 활동(404) → 자격(403) → 모집 상태(409) → 폼 연결(409).
     * 자격을 상태보다 먼저 보는 것은 남의 활동에 대해 번호를 바꿔 가며 부르는 것만으로 그
     * 활동이 모집을 시작했는지 알아낼 수 없게 하기 위해서다(SessionServiceImpl과 같은 태도).
     */
    @Override
    public List<FormResponseSummaryResponse> getApplications(
            Long academicProgramId, ResponseStatus responseStatus, MemberEntity requester) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeaderOrManager(academicProgram, requester);
        requireRecruitmentStarted(academicProgram);

        return formResponseService.getResponses(recruitmentFormId(academicProgram), responseStatus);
    }

    /*
     * 선발 확정.
     *
     * 줄마다 심사한 뒤 곧바로 등록한다. 두 단계를 줄 단위로 붙여 두는 것은 등록이 그 응답의
     * ACCEPTED를 전제하기 때문이고(EventParticipationServiceImpl.findAcceptedApplication),
     * 같은 트랜잭션의 영속성 컨텍스트라 방금 바꾼 상태를 그대로 본다.
     *
     * 실패는 전부 되돌린다 — 이미 확정된 신청자를 다시 고르면 폼 응답이 ACCEPTED(종결)라
     * 400 INVALID_RESPONSE_STATUS_TRANSITION이고, 이미 명단에 있는 회원이면 409
     * EVENT_PARTICIPANT_DUPLICATED다. 한 줄만 걸러내고 나머지를 반영하지 않는 것은, 어느 줄이
     * 반영됐는지를 화면이 되짚어야 하는 상태를 만들지 않기 위해서다. 같은 formRspnsId가 두 번
     * 실려 온 요청도 같은 이유로 여기서 끊긴다(두 번째 줄이 이미 ACCEPTED인 응답을 만난다).
     */
    @Override
    @Transactional
    public List<AcademicProgramMemberResponse> selectMembers(
            Long academicProgramId, RecruitmentSelectRequest request, MemberEntity performer) {

        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        requireRecruitmentStarted(academicProgram);

        Long formId = recruitmentFormId(academicProgram);
        Long eventId = academicProgram.getEvent().getId();

        for (RecruitmentSelectionRequest selection : request.selections()) {
            formResponseService.reviewResponse(
                    formId, selection.formRspnsId(), ACCEPT_REVIEW, performer);
            eventParticipationService.registerParticipant(
                    eventId,
                    new EventParticipantRegisterRequest(
                            selection.formRspnsId(), null, selection.ptcpSttsCd()),
                    performer);
        }

        List<AcademicProgramMemberResponse> members = membersOf(academicProgram, null);
        warnIfCapacityExceeded(academicProgram, members);
        return members;
    }

    // ------------------------------------------------------------------ 헬퍼

    private AcademicProgramEntity findAcademicProgram(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }

    /*
     * 명단 한 벌. 상태 미지정은 전체 상태로 표현한다 — 열거형 파라미터에 NULL을 넣고
     * `:status is null`로 분기하면 Hibernate가 타입을 추론하지 못한다(행사 도메인의 같은 질의를
     * 그대로 쓰므로 정렬도 등록 순번 오름차순이다).
     */
    private List<AcademicProgramMemberResponse> membersOf(
            AcademicProgramEntity academicProgram, EventParticipantStatus participantStatus) {
        Collection<EventParticipantStatus> statuses =
                participantStatus == null
                        ? EnumSet.allOf(EventParticipantStatus.class)
                        : EnumSet.of(participantStatus);
        return eventParticipantRepository
                .findAllByEventAndStatusInOrderByIdAsc(academicProgram.getEvent(), statuses)
                .stream()
                .map(participant -> AcademicProgramMemberResponse.of(participant, academicProgram))
                .toList();
    }

    private void requireRecruitmentStarted(AcademicProgramEntity academicProgram) {
        if (!academicProgram.getStatus().hasStartedRecruitment()) {
            throw new GeneralException(AcademicProgramErrorCode.RECRUITMENT_NOT_STARTED);
        }
    }

    /*
     * 모집 폼. 승인 후속 처리(#133)가 생성 시점에 항상 빈 폼을 만들어 연결하고 모집 시작
     * (START_RECRUITMENT)이 그 폼을 열지 못하면 전이 자체가 실패하므로, 모집이 시작된 활동에서
     * 여기 걸릴 일은 없다 — 데이터 정합성이 깨진 경우에 대한 방어선이다
     * (AcademicProgramServiceImpl.requireFormId와 같은 자리).
     */
    private Long recruitmentFormId(AcademicProgramEntity academicProgram) {
        EventEntity event = academicProgram.getEvent();
        if (event.getForm() == null) {
            throw new GeneralException(AcademicProgramErrorCode.FORM_NOT_LINKED);
        }
        return event.getForm().getId();
    }

    /*
     * 정원 초과는 **막지 않는다**(설계 결정 #2). 정원(cpcty_max_cnt)은 기획 단계의 참고치이고,
     * 실제로 몇 명을 받을지는 지원자를 보고 사람이 정한다 — 서버가 끊으면 한 명을 더 받으려고
     * 활동 정보를 먼저 고쳐야 하는 절차가 생긴다.
     *
     * 그래도 조용히 넘기지는 않는다. 응답 스키마가 갱신된 명단 하나라(학술관리_API설계.md §3.7)
     * 경고를 실을 자리가 없으므로 서버 로그로 남기며, 화면은 활동 상세의 cpctyMaxCnt와 이
     * 명단의 확정 인원을 비교해 사람에게 알린다.
     */
    private void warnIfCapacityExceeded(
            AcademicProgramEntity academicProgram, List<AcademicProgramMemberResponse> members) {
        Integer capacity = academicProgram.getCapacityMaxCount();
        if (capacity == null) {
            return;
        }
        long confirmed =
                members.stream()
                        .filter(member -> member.ptcpSttsCd() == EventParticipantStatus.CONFIRMED)
                        .count();
        if (confirmed > capacity) {
            log.warn(
                    "선발 확정이 정원을 넘겼다(차단하지 않는다). academicProgramId={}, 확정={}, 정원={}",
                    academicProgram.getId(),
                    confirmed,
                    capacity);
        }
    }
}
