package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramProgressResponse;
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
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramProgressCount;
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
        AcademicProgramProgressResponse progress =
                progressesOf(List.of(academicProgramId))
                        .getOrDefault(academicProgramId, AcademicProgramProgressResponse.zero());

        // 연결된 모집 폼에서 formId·파생 접수 상태를 채운다(#186). 이관(#148) 전 활동이나
        // 데이터 정합성이 깨져 폼이 없는 활동은 둘 다 null이다. 접수 상태는 상태 코드만 읽지
        // 않고 now까지 보는 FormReceiptPolicy가 유일한 판정 지점이며, 문자열 형식은
        // AcademicProgramTransitionResponse.formReceiptStatus(FormReceiptStatus)와 같다.
        FormEntity form = academicProgram.getEvent().getForm();
        Long formId = form == null ? null : form.getId();
        String formReceiptStatus =
                form == null ? null : formReceiptPolicy.receiptStatusOf(form).name();

        return AcademicProgramDetailResponse.of(
                academicProgram,
                progress,
                isDelayed(academicProgram, progress, Instant.now(clock)),
                viewer,
                formId,
                formReceiptStatus);
    }

    /*
     * 지연 판정(#610)을 진행률과 잇는 자리. 판정 자체는 AcademicProgramEntity.isDelayedAt이
     * 갖고, 목록 필터(AcademicProgramRepositoryImpl.DELAYED)가 같은 조건을 JPQL로 쓴다.
     * totalSessionCount는 이름과 달리 계획 항목 수다(AcademicProgramProgressResponse 주석).
     */
    private boolean isDelayed(
            AcademicProgramEntity academicProgram,
            AcademicProgramProgressResponse progress,
            Instant now) {
        return academicProgram.isDelayedAt(
                now, progress.totalSessionCount(), progress.approvedSessionCount());
    }

    /*
     * 활동별 진행률 (#609). 목록은 페이지의 활동 id를, 상세는 id 하나를 넘겨 **같은 집계 질의와
     * 같은 계산**을 지난다 — 둘을 따로 세면 같은 활동이 두 화면에서 다른 숫자로 보인다.
     * 카드마다 세면 그대로 N+1이라 질의는 활동 수와 무관하게 하나다(DB-13).
     *
     * 계획 항목이 없는 활동은 결과에 키가 없으므로 호출부가 zero()로 읽는다(그 규칙은 질의 주석).
     * 활동이 한 건도 없으면 질의를 보내지 않는다 — in ()은 DB마다 해석이 갈린다.
     */
    private Map<Long, AcademicProgramProgressResponse> progressesOf(List<Long> academicProgramIds) {
        if (academicProgramIds.isEmpty()) {
            return Map.of();
        }
        return curriculumItemRepository
                .countProgressByAcademicProgramIds(academicProgramIds)
                .stream()
                .collect(
                        Collectors.toMap(
                                AcademicProgramProgressCount::getAcademicProgramId,
                                count ->
                                        AcademicProgramProgressResponse.of(
                                                count.getCurriculumItemCount(),
                                                count.getApprovedSessionCount())));
    }

    /*
     * 계획 조회(#134). 존재하지 않는 활동은 빈 배열이 아니라 404다 — 커리큘럼이 0건인 활동과
     * 활동 자체가 없는 것을 같은 응답으로 뭉개면 화면이 둘을 구별하지 못한다(단건 조회와 같은
     * 태도).
     *
     * 소유권 판정은 활동당 한 번만 한다. 줄마다 물어봐도 답이 같은데(leadrMbrId는 회차별로
     * 다르지 않다) 정책을 회차 수만큼 부르면 판정이 줄마다 다를 수 있다는 오해를 남긴다.
     * requireLeader가 아니라 isLeader인 것은 이 API가 인증만 요구하기 때문이다 — 팀원·일반
     * 회원도 표를 보되 편집 버튼만 꺼진다. 종료된 활동은 리더에게도 꺼진다(#597) — 회차 제출·
     * 재제출이 AcademicProgramWritePolicy에서 409로 끊기므로 버튼과 판정을 맞춘다.
     *
     * 실적(#135)은 계획 줄마다 묻지 않고 활동 하나의 것을 한 번에 읽어 접는다 — 줄마다 물으면
     * 그대로 N+1이다(DB-13). 계약(항상 값이 있는 sesnSttsCd, 서버가 판정하는 isEditable)은
     * 실적이 붙어도 그대로다.
     */
    @Override
    public List<CurriculumItemWithSessionResponse> getCurriculumItems(
            Long academicProgramId, MemberEntity viewer) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        boolean writable =
                academicProgramOwnershipPolicy.isLeader(academicProgram, viewer)
                        && academicProgram.getStatus().acceptsWrites();

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
                                            item, writable)
                                    : CurriculumItemWithSessionResponse.withSession(
                                            item, session, writable);
                        })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireShareRevocable(Long academicProgramId, MemberEntity requester) {
        // 없는 활동을 먼저 404로 끊는다 — 발급 경로가 같은 이유로 조회를 먼저 태운다
        // (shr_lnk에 FK가 없어 DB가 막아 주지 않는다).
        academicProgramOwnershipPolicy.requireLeaderOrManager(
                findAcademicProgram(academicProgramId), requester);
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
     * 같은 수다(설계 결정 #3). 카드의 값을 채우는 집계(접수 건수 #483 · 진행률 #609)가 페이지당
     * 한 번씩 더해질 뿐 카드 수에 따라 늘지 않는다.
     *
     * '지금'은 한 번만 읽어 지연 필터(delayed=true)와 응답의 isDelayed가 같은 경계를 보게 한다
     * (#610) — 따로 읽으면 그 사이에 종료 시각이 지난 활동이 필터에는 걸리고 배지는 없는 줄이
     * 된다(SubWorkServiceImpl.searchSubWorks가 지연 경계를 한 번 읽는 것과 같다).
     */
    @Override
    public AcademicProgramSearchResponse searchAcademicPrograms(
            AcademicProgramCondition condition, MemberEntity viewer) {
        Instant now = Instant.now(clock);
        AcademicProgramSearchQuery query = condition.toQuery(viewer, now);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<AcademicProgramEntity> fetched = academicProgramRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<AcademicProgramEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        Map<Long, Long> applicationCounts = applicationCountsOf(rows);
        Map<Long, AcademicProgramProgressResponse> progresses =
                progressesOf(rows.stream().map(AcademicProgramEntity::getId).toList());
        List<AcademicProgramSummaryResponse> academicPrograms =
                rows.stream()
                        .map(
                                program -> {
                                    FormEntity form = program.getEvent().getForm();
                                    AcademicProgramProgressResponse progress =
                                            progresses.getOrDefault(
                                                    program.getId(),
                                                    AcademicProgramProgressResponse.zero());
                                    return AcademicProgramSummaryResponse.of(
                                            program,
                                            viewer,
                                            form == null
                                                    ? null
                                                    : formReceiptPolicy
                                                            .receiptStatusOf(form)
                                                            .name(),
                                            form == null
                                                    ? 0L
                                                    : applicationCounts.getOrDefault(
                                                            form.getId(), 0L),
                                            progress,
                                            isDelayed(program, progress, now));
                                })
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

    /*
     * 이 페이지의 폼들에 들어온 접수 건수 (#483 · 카드의 "지원 N건").
     *
     * 카드마다 세면 그대로 N+1이라 한 페이지치를 한 번에 모아 온다(DB-13). **무엇을 접수로
     * 세는가는 폼 도메인이 정한다**(FormService.countSubmittedResponsesByFormIds) — 폼 목록의
     * responseCount와 같은 기준이라 같은 폼을 두 화면에서 보면 숫자가 같다.
     *
     * 응답이 없는 폼은 결과에 키가 없으므로 호출부가 0으로 읽는다(그 규칙은 그쪽 주석).
     */
    private Map<Long, Long> applicationCountsOf(List<AcademicProgramEntity> rows) {
        List<Long> formIds =
                rows.stream()
                        .map(program -> program.getEvent().getForm())
                        .filter(Objects::nonNull)
                        .map(FormEntity::getId)
                        .toList();
        return formService.countSubmittedResponsesByFormIds(formIds);
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(
            AcademicProgramSearchQuery query, List<AcademicProgramEntity> rows, boolean hasNext) {
        return hasNext
                ? AcademicProgramCursor.of(query.sort(), rows.get(rows.size() - 1)).encode()
                : null;
    }

    /*
     * 국장 전용 3액션(#133 · 재시작 #597). 전이 가능 여부부터 검증한다(changeStatus가 먼저
     * 던진다) — 애초에 성립하지 않는 전이에 폼 오케스트레이션·승인 이력 기록 같은 부수 효과를
     * 먼저 만들면 검사 순서가 뒤집혀 엉뚱한 오류가 먼저 보인다(FormEntity.changeStatus와 같은
     * 태도).
     *
     * **이 경로는 AcademicProgramWritePolicy를 지나지 않는다** — 종료된 활동의 쓰기를 막는
     * 그 판정을 여기 걸면 재시작이 영영 성립하지 않는다. 종료된 활동에서 성립하는 전이가
     * REOPEN 하나라는 것은 전이표가 이미 말한다.
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
                    case REOPEN -> reopen(academicProgram, performer);
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
     * 여기서는 그 재량이 실제로 내려졌다는 사실을 acdm_actv_aprv에 기록하고, 모집 폼이 아직
     * 접수 중이면 닫는다(#597).
     *
     * 폼을 닫는 것은 종료가 그 활동의 쓰기를 멈추는데(AcademicProgramWritePolicy) 폼만 계속
     * 응답을 받으면 아무도 선발할 수 없는 지원서가 쌓이기 때문이다. 같은 트랜잭션이라 폼을
     * 닫지 못하면 종료도 되돌아간다. 이미 닫혔거나 작성 중인 폼은 건드리지 않는다 — 어느 상태에서
     * 닫을 수 있는지는 폼의 전이표(FormStatusAction.CLOSE)가 답한다.
     *
     * 승인 대기 회차가 남아 있어도 막지 않는다 — 재시작이 있어 막을 이유가 없고, 그 수는 웹의
     * 확인 시트가 보여 준다. 그 회차는 승인 대기 목록에서 빠진다(SessionReviewCondition).
     */
    private FormReceiptStatus approveCompletion(
            AcademicProgramEntity academicProgram, MemberEntity performer) {
        academicProgramApprovalRepository.save(
                AcademicProgramApprovalEntity.forCompletion(
                        academicProgram, performer, Instant.now(clock)));
        return closeRecruitmentFormIfOpen(academicProgram.getEvent().getForm());
    }

    /*
     * 폼을 닫았으면 닫은 뒤의 접수 상태를, 건드리지 않았으면 null을 돌려준다 — 응답의
     * formReceiptStatus는 «이 전이가 폼을 바꿨을 때만» 값이 있다(AcademicProgramTransitionResponse).
     *
     * 휴지통에 있는 폼은 건드리지 않는다. 폼 삭제는 접수 상태를 보지 않아(FormEntity.
     * requireDeletable) 접수 중인 채로 지워질 수 있는데, 폼 서비스는 지워진 폼을 404로 찾으므로
     * 부르면 종료 자체가 실패한다 — 지워진 폼은 이미 응답을 받지 않는다.
     */
    private FormReceiptStatus closeRecruitmentFormIfOpen(FormEntity form) {
        if (form == null
                || form.isDeleted()
                || !FormStatusAction.CLOSE.isAllowedFrom(form.getStatus())) {
            return null;
        }
        return formService
                .changeStatus(form.getId(), new FormStatusChangeRequest(FormStatusAction.CLOSE))
                .receiptStatus();
    }

    /*
     * 재시작(#597 · ADR-0057). 종료를 되돌려 쓰기를 다시 받는다 — 잘못 누른 종료를 DB를 고치지
     * 않고 되돌릴 유일한 길이다.
     *
     * 기록은 종료 줄을 지우지 않고 REOPEN 한 줄을 덧붙인다. 감사 로그(transition의
     * ACADEMIC_PROGRAM_TRANSITION)로만 남기지 않는 것은 그것을 읽어 오는 API가 없어 «누가 언제
     * 다시 열었나»가 화면에서 안 보이기 때문이다(승인 이력 조회 #139가 이 줄을 보여 준다).
     *
     * **모집 폼은 다시 열지 않는다** — 모집은 폼 화면에서 따로 연다(ADR-0057). 종료가 닫은
     * 폼만 골라 되살리려면 «누가 닫았나»를 따로 적어야 하고, 전부 되살리면 종료 전에 이미
     * 마감했던 모집까지 열린다. 그래서 폼을 건드리지 않고 formReceiptStatus는 null이다.
     */
    private FormReceiptStatus reopen(
            AcademicProgramEntity academicProgram, MemberEntity performer) {
        academicProgramApprovalRepository.save(
                AcademicProgramApprovalEntity.forReopen(
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
