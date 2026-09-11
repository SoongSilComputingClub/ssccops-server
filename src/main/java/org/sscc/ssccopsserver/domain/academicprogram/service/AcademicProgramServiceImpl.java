package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemWithSessionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramApprovalRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatusAction;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.service.FormReceiptPolicy;
import org.sscc.ssccopsserver.domain.form.service.FormService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramServiceImpl implements AcademicProgramService {

    private final AcademicProgramRepository academicProgramRepository;
    private final CurriculumItemRepository curriculumItemRepository;
    private final SessionRepository sessionRepository;
    private final AcademicProgramApprovalRepository academicProgramApprovalRepository;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;
    private final FormService formService;
    private final FormReceiptPolicy formReceiptPolicy;
    private final Clock clock;
    private final AuditLog auditLog;

    /*
     * 단건 조회(#131). AcademicProgram 행은 이제 폼 응답 승인 이관(#148)이 만든다 — 이 서비스는
     * 그 결과를 읽기만 한다(readOnly 트랜잭션, 어떤 상태도 바꾸지 않는다).
     */
    @Override
    public AcademicProgramDetailResponse getAcademicProgram(
            Long academicProgramId, MemberEntity viewer) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        long curriculumItemCount =
                curriculumItemRepository.countByAcademicProgramId(academicProgramId);

        // 연결된 모집 폼에서 formId·파생 접수 상태를 채운다(#186). 이관(#148) 전 활동이나
        // 데이터 정합성이 깨져 폼이 없는 활동은 둘 다 null이다. 접수 상태는 상태 코드만 읽지
        // 않고 now까지 보는 FormReceiptPolicy가 유일한 판정 지점이며, 문자열 형식은
        // AcademicProgramTransitionResponse.formReceiptStatus(FormReceiptStatus)와 같다.
        FormEntity form = academicProgram.getEvent().getForm();
        Long formId = form == null ? null : form.getId();
        String formReceiptStatus =
                form == null ? null : formReceiptPolicy.receiptStatusOf(form).name();

        return AcademicProgramDetailResponse.of(
                academicProgram, (int) curriculumItemCount, viewer, formId, formReceiptStatus);
    }

    /*
     * 계획 조회(#134). 존재하지 않는 활동은 빈 배열이 아니라 404다 — 커리큘럼이 0건인 활동과
     * 활동 자체가 없는 것을 같은 응답으로 뭉개면 화면이 둘을 구별하지 못한다(단건 조회와 같은
     * 태도).
     *
     * 소유권 판정은 활동당 한 번만 한다. 줄마다 물어봐도 답이 같은데(leadrMbrId는 회차별로
     * 다르지 않다) 정책을 회차 수만큼 부르면 판정이 줄마다 다를 수 있다는 오해를 남긴다.
     * requireLeader가 아니라 isLeader인 것은 이 API가 인증만 요구하기 때문이다 — 팀원·일반
     * 회원도 표를 보되 편집 버튼만 꺼진다.
     *
     * 실적(#135)은 계획 줄마다 묻지 않고 활동 하나의 것을 한 번에 읽어 접는다 — 줄마다 물으면
     * 그대로 N+1이다(DB-13). 계약(항상 값이 있는 sesnSttsCd, 서버가 판정하는 isEditable)은
     * 실적이 붙어도 그대로다.
     */
    @Override
    public List<CurriculumItemWithSessionResponse> getCurriculumItems(
            Long academicProgramId, MemberEntity viewer) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        boolean isLeader = academicProgramOwnershipPolicy.isLeader(academicProgram, viewer);

        List<CurriculumItemEntity> curriculumItems =
                curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(academicProgramId);
        Map<Long, SessionEntity> sessionsByCurriculumItemId =
                sessionRepository.findByCurriculumItemAcademicProgramId(academicProgramId).stream()
                        .collect(
                                Collectors.toMap(
                                        session -> session.getCurriculumItem().getId(),
                                        Function.identity()));

        return curriculumItems.stream()
                .map(
                        item -> {
                            SessionEntity session = sessionsByCurriculumItemId.get(item.getId());
                            return session == null
                                    ? CurriculumItemWithSessionResponse.withoutSession(
                                            item, isLeader)
                                    : CurriculumItemWithSessionResponse.withSession(
                                            item, session, isLeader);
                        })
                .toList();
    }

    private AcademicProgramEntity findAcademicProgram(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }

    /*
     * 목록 조회. 쿼리는 목록 · 필터 건수 · 전체 건수 셋으로 work 도메인의 목록 조회(OPS-020)와
     * 같은 수다(설계 결정 #3).
     */
    @Override
    public AcademicProgramSearchResponse searchAcademicPrograms(
            AcademicProgramCondition condition, MemberEntity viewer) {
        AcademicProgramSearchQuery query = condition.toQuery(viewer);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<AcademicProgramEntity> fetched = academicProgramRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<AcademicProgramEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        List<AcademicProgramSummaryResponse> academicPrograms =
                rows.stream()
                        .map(program -> AcademicProgramSummaryResponse.of(program, viewer))
                        .toList();

        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        nextCursorOf(query, rows, hasNext),
                        hasNext,
                        academicProgramRepository.countMatching(query),
                        academicProgramRepository.count());
        return new AcademicProgramSearchResponse(academicPrograms, page);
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(
            AcademicProgramSearchQuery query, List<AcademicProgramEntity> rows, boolean hasNext) {
        return hasNext
                ? AcademicProgramCursor.of(query.sort(), rows.get(rows.size() - 1)).encode()
                : null;
    }

    /*
     * 국장 전용 2액션(#133). 전이 가능 여부부터 검증한다(changeStatus가 먼저 던진다) — 애초에
     * 성립하지 않는 전이에 폼 오케스트레이션·승인 이력 기록 같은 부수 효과를 먼저 만들면
     * 검사 순서가 뒤집혀 엉뚱한 오류가 먼저 보인다(FormEntity.changeStatus와 같은 태도).
     */
    @Override
    @Transactional
    public AcademicProgramTransitionResponse transition(
            Long academicProgramId,
            AcademicProgramTransitionRequest request,
            MemberEntity performer) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        AcademicProgramStatus before = academicProgram.getStatus();

        academicProgram.changeStatus(request.transition());

        FormReceiptStatus formReceiptStatus =
                switch (request.transition()) {
                    case START_RECRUITMENT -> startRecruitment(academicProgram, request);
                    case APPROVE_COMPLETION -> approveCompletion(academicProgram, performer);
                };

        auditLog.record(
                AuditEvent.success(AuditAction.ACADEMIC_PROGRAM_TRANSITION)
                        .target(academicProgramId)
                        .decision(request.transition())
                        .change(before, academicProgram.getStatus())
                        .build());
        return AcademicProgramTransitionResponse.of(
                academicProgram.getId(), before, academicProgram.getStatus(), formReceiptStatus);
    }

    /*
     * 모집 시작. 연결된 Form에 모집 기간을 반영한 뒤 OPEN 전이하고, 같은 트랜잭션에서 이관된
     * Event를 게시한다(#187) — 세 조작을 묶어 클라이언트가 여러 번 부르지 않게 한다(설계 결정 #2).
     * FORM_HAS_NO_QUESTION 등 폼 도메인 예외는 감싸지 않고 그대로 전파한다(설계 결정 #5) —
     * 승인 후속 처리가 항상 폼을 만들어 두므로 START_RECRUITMENT 실패의 실질 원인은
     * FORM_NOT_LINKED가 아니라 이쪽이다.
     */
    private FormReceiptStatus startRecruitment(
            AcademicProgramEntity academicProgram, AcademicProgramTransitionRequest request) {
        EventEntity event = academicProgram.getEvent();
        Long formId = requireFormId(event);

        formService.changeReceiptPeriod(
                formId,
                toInstant(request.recruitmentStartDt()),
                toInstant(request.recruitmentEndDt()));
        FormStatusChangeResponse formResponse =
                formService.changeStatus(
                        formId, new FormStatusChangeRequest(FormStatusAction.OPEN));

        publishEventForRecruitment(event);

        return formResponse.receiptStatus();
    }

    /*
     * 모집이 시작되면 이관된 Event를 공개한다(#187). 이관(#148)이 만드는 Event는 항상 DRAFT라
     * 공개 앱 목록에 뜨지 않았고, 학술국장 동선에는 어드민에서 수동 게시하는 단계가 없었다.
     *
     * DRAFT일 때만 게시한다. 이미 PUBLISHED면(재시도·경합) 그대로 두고, ARCHIVED처럼 PUBLISH가
     * 전이표상 불가한 상태면 건드리지 않는다 — 운영자가 보관한 Event를 모집 시작이 되살리면
     * 그 결정을 덮어쓰게 되고, 학술 흐름에서 Event가 DRAFT가 아닌 채로 여기 오는 일은 거의 없다.
     * 접수 기간이 지나 공개에서 내려가는 것은 상태 전이가 아니라 PublicEventServiceImpl의
     * 조회 시점 판정이 맡는다.
     */
    private void publishEventForRecruitment(EventEntity event) {
        if (event.getStatus() == EventStatus.DRAFT) {
            event.changeStatus(EventStatusAction.PUBLISH);
        }
    }

    /*
     * 종료/수료 승인. 진행률 미달이어도 자동 차단하지 않는다(학술국장 재량, 설계 결정 #4) —
     * 여기서는 그 재량이 실제로 내려졌다는 사실만 acdm_actv_aprv에 기록한다.
     */
    private FormReceiptStatus approveCompletion(
            AcademicProgramEntity academicProgram, MemberEntity performer) {
        academicProgramApprovalRepository.save(
                AcademicProgramApprovalEntity.forCompletion(
                        academicProgram, performer, Instant.now(clock)));
        return null;
    }

    private Long requireFormId(EventEntity event) {
        if (event.getForm() == null) {
            throw new GeneralException(AcademicProgramErrorCode.FORM_NOT_LINKED);
        }
        return event.getForm().getId();
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
