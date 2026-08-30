package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentApplicationResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantRegisterRequest;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
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
 *   심사     → FormResponseService.reviewResponse                       (#141 · 전이표·검토 이력·처리자)
 *   명단 반영 → EventParticipationService.registerOrUpdateParticipant   (#198 · 근거·전이표·상태 검사)
 *   명단 조회 → EventParticipantRepository                              (ssccops#146의 질의 그대로)
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
    public List<RecruitmentApplicationResponse> getApplications(
            Long academicProgramId, ResponseStatus responseStatus, MemberEntity requester) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeaderOrManager(academicProgram, requester);
        requireRecruitmentStarted(academicProgram);

        List<FormResponseSummaryResponse> applications =
                formResponseService.getResponses(
                        recruitmentFormId(academicProgram), responseStatus);
        Map<Long, EventParticipantEntity> roster = rosterByMemberId(academicProgram);

        return applications.stream()
                .map(
                        application ->
                                RecruitmentApplicationResponse.of(
                                        application, roster.get(application.member().mbrId())))
                .toList();
    }

    /*
     * 선발 저장.
     *
     * 줄마다 심사한 뒤 곧바로 명단에 반영한다. 두 단계를 줄 단위로 붙여 두는 것은 등록이 그
     * 응답의 ACCEPTED를 전제하기 때문이고(EventParticipationServiceImpl.findAcceptedApplication),
     * 같은 트랜잭션의 영속성 컨텍스트라 방금 바꾼 상태를 그대로 본다.
     *
     * **다시 저장할 수 있다**(#198). 그전까지 이 메서드는 한 방향으로만 움직였다 — 이미 확정된
     * 신청자를 다시 고르면 폼 응답이 종결이라 400, 명단 행이 이미 있으면 409였고, 확정을 대기로
     * 내리는 전이 자체가 없어 한 번의 오조작이 복구되지 않았다. 세 자리가 함께 열렸다:
     *
     *   심사 → 이미 ACCEPTED면 부르지 않는다 (재선발은 재심사가 아니다)
     *   명단 → registerOrUpdateParticipant가 등록이거나 상태 맞추기다 (같은 값이면 아무 일도 없다)
     *   전이 → EventParticipantEntity.changeStatus가 CONFIRMED→WAITLISTED를 허용한다
     *
     * **판정을 여기 적지 않는 것이 요점이다.** 이 메서드가 아는 것은 "재선발은 재심사가 아니다"
     * 하나뿐이고, 어떤 전이가 성립하는지도 같은 값을 어떻게 다룰지도 전부 위임한 쪽의 규칙이다 —
     * 여기에 한 벌 더 적으면 행사 명단을 고치는 두 경로가 다른 표를 보게 된다.
     *
     * 실패는 여전히 전부 되돌린다. 한 줄만 걸러내고 나머지를 반영하지 않는 것은, 어느 줄이
     * 반영됐는지를 화면이 되짚어야 하는 상태를 만들지 않기 위해서다. 취소(CANCELLED)된
     * 참가자를 다시 고르는 요청은 400 INVALID_PARTICIPANT_STATUS_TRANSITION이다 — 취소를
     * 되돌리는 것은 이 이슈의 범위 밖이고, 그 판정도 전이표가 그대로 갖는다.
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
            acceptIfPending(formId, selection.formRspnsId(), performer);
            eventParticipationService.registerOrUpdateParticipant(
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

    /*
     * 아직 승인되지 않은 응답만 승인한다 (#198).
     *
     * 이미 ACCEPTED인 응답에 검토를 다시 걸면 종결 상태라 400이고(FormResponseHistoryEntity의
     * 전이표), 통과시킨다 해도 아무것도 바꾸지 않은 승인이 처리 이력에 한 줄 더 쌓인다(#141).
     * 재선발이 바꾸는 것은 참가 상태뿐이며, 그 사람이 신청을 냈고 승인됐다는 사실은 그대로다.
     *
     * 승인 자체가 성립하지 않는 상태(작성 중·수정요청 대기·반려)는 여기서 가려내지 않는다 —
     * 어느 상태에서 승인으로 갈 수 있는지는 폼 도메인의 전이표가 답하며, 그 요청은 종전처럼
     * 400 INVALID_RESPONSE_STATUS_TRANSITION으로 끊긴다.
     */
    private void acceptIfPending(Long formId, Long formResponseId, MemberEntity performer) {
        if (formResponseService.getResponseStatus(formId, formResponseId)
                == ResponseStatus.ACCEPTED) {
            return;
        }
        formResponseService.reviewResponse(formId, formResponseId, ACCEPT_REVIEW, performer);
    }

    /*
     * 이 활동의 명단을 회원 식별자로 접은 것 (#198 · 신청자 목록의 참가 상태).
     *
     * 신청 한 줄마다 "명단에 있나"를 묻지 않고 한 번에 모아 오며(DB-13), 응답이 몇 건이든
     * 질의는 하나다. 상태를 가리지 않는 것은 취소된 참가자도 신청자 표에 그대로 보여야 하기
     * 때문이다(D16 · 명단은 활동 이력으로 영구 보존한다).
     *
     * 회원으로 접을 수 있는 근거는 UNIQUE(uk_event_ptcp_event_member)다 — (행사, 회원)당 한
     * 줄이라 키가 겹치지 않는다.
     */
    private Map<Long, EventParticipantEntity> rosterByMemberId(
            AcademicProgramEntity academicProgram) {
        return eventParticipantRepository
                .findAllByEventAndStatusInOrderByIdAsc(
                        academicProgram.getEvent(), EnumSet.allOf(EventParticipantStatus.class))
                .stream()
                .collect(
                        Collectors.toMap(
                                participant -> participant.getMember().getId(),
                                Function.identity()));
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
     * 정원 초과는 **막지 않는다**(설계 결정 #2). 정원(pscp_max_cnt)은 기획 단계의 참고치이고,
     * 실제로 몇 명을 받을지는 지원자를 보고 사람이 정한다 — 서버가 끊으면 한 명을 더 받으려고
     * 활동 정보를 먼저 고쳐야 하는 절차가 생긴다.
     *
     * 그래도 조용히 넘기지는 않는다. 응답 스키마가 갱신된 명단 하나라(학술관리_API설계.md §3.7)
     * 경고를 실을 자리가 없으므로 서버 로그로 남기며, 화면은 활동 상세의 pscpMaxCnt와 이
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
