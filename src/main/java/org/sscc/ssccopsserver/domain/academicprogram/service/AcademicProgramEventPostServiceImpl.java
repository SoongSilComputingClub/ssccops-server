package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramEventPostLockReason;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramEventPostResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventIntroductionUpdateRequest;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.service.EventImageService;
import org.sscc.ssccopsserver.domain.event.service.EventService;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.service.FormReceiptPolicy;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 스터디장의 행사 게시물 편집 (ssccops#618 · ADR-0069 참고).
 *
 * **판정은 학술 도메인에, 저장은 행사 도메인에 둔다.** «그 프로그램의 리더인가»는 ADR-0067이 프로그램 단위로
 * 못 박은 판정이라 행사 API(/v1/events)에 섞으면 행사 도메인이 학술의 리더를 알아야 하고 의존이 거꾸로 선다
 * (DomainCycleTest · ADR-0069 선택지 B). 학술 → 행사 의존은 승인 이관 때부터 있다. 저장 규칙(본문 상한 ·
 * 빠진 이미지 정리)은 EventService.updateIntroduction이 갖고 여기서 다시 적지 않는다.
 *
 * **자격은 리더 한 사람이다** — requireLeaderOrManager가 아니다. 학술국장 · 국원은 관리자 페이지의 행사
 * 편집을 쓰고(EVENT_MANAGE), 이 경로에서 그들을 받으면 같은 행사를 고치는 학술국장 경로가 둘이 된다.
 * 리더 역할(스터디장 등)에 권한을 붙이는 길(ADR-0069 선택지 A)은 ADR-0067이 막았다.
 *
 * **판정 순서**(쓰기 둘): 활동 404 → 리더 403 → 수료 · 폐지 409(AcademicProgramWritePolicy — 종료된 활동의
 * 쓰기를 거절하는 유일한 자리) → 폼 연결 409 → 접수 종료 409 ACADEMIC_PROGRAM_EVENT_POST_LOCKED. 자격을 상태보다
 * 먼저 보는 것은 이 도메인의 다른 쓰기와 같다 — 권한 없는 사람에게 «끝났다»를 먼저 알리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramEventPostServiceImpl implements AcademicProgramEventPostService {

    /*
     * 스터디장이 게시물을 고칠 수 있는 접수 상태 (ADR-0069 참고 — 사용자 결정 «모집이 끝나면 그 이후
     * 수정은 못 한다»). 모집 폼 문항(#483 · DRAFT · SCHEDULED)과 달리 ACCEPTING이 든다 — 문항은 응답을
     * 받는 도중에 바뀌면 안 되지만 소개 글은 접수 중에 가장 많이 읽히고 고칠 일도 그때 생긴다. 공개
     * 웹사이트가 학술 행사를 접수 중일 때만 보여 주므로(#187) 이 창이 곧 «게시물이 읽히는 동안»이다.
     */
    private static final Set<FormReceiptStatus> EDITABLE_RECEIPT_STATUSES =
            EnumSet.of(
                    FormReceiptStatus.DRAFT,
                    FormReceiptStatus.SCHEDULED,
                    FormReceiptStatus.ACCEPTING);

    private final AcademicProgramRepository academicProgramRepository;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;

    /* 쓰기 둘이 활동을 얻는 자리 — 수료 · 폐지는 여기서 409다(#597 · #611) */
    private final AcademicProgramWritePolicy academicProgramWritePolicy;

    /* 접수 창의 유일한 근거. 시각까지 보는 판정은 그 클래스만 한다 */
    private final FormReceiptPolicy formReceiptPolicy;

    private final EventService eventService;
    private final EventImageService eventImageService;
    private final AuditLog auditLog;

    /*
     * 조회는 잠겨 있어도 200이다 — 접수가 끝난 뒤에도 리더는 자기 게시물을 볼 수 있어야 하고, 화면은
     * lockReason으로 읽기 전용 이유를 그린다(모집 폼 조회 #483과 같은 태도). 수료 · 폐지도 막지 않는다
     * (조회는 AcademicProgramWritePolicy를 지나지 않는다).
     */
    @Override
    public AcademicProgramEventPostResponse getEventPost(
            Long academicProgramId, MemberEntity requester) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeader(academicProgram, requester);
        return toResponse(academicProgram, requireRecruitmentForm(academicProgram));
    }

    @Override
    @Transactional
    public AcademicProgramEventPostResponse updateEventPost(
            Long academicProgramId, EventIntroductionUpdateRequest request, MemberEntity actor) {
        AcademicProgramEntity academicProgram = requireEditable(academicProgramId, actor);
        EventEntity event = academicProgram.getEvent();

        // 바뀐 칸의 이름만 감사에 싣는다 — 고치기 전 값을 먼저 읽어 둔다(값은 싣지 않는다 · ADR-0024)
        List<String> changedFields = changedFieldNames(event, request);

        eventService.updateIntroduction(event.getId(), request);

        /*
         * 행사 수정과 같은 수준의 감사 (ADR-0069 «감사»). 행위자는 AuditLog가 SecurityContext에서 채운다.
         * 아무 칸도 바뀌지 않은 저장도 남긴다 — 화면이 «저장»을 누른 사실이 사건이고, 바뀐 것이 없다는
         * 것은 빈 changed_fields가 말한다.
         */
        auditLog.record(
                AuditEvent.success(AuditAction.ACADEMIC_PROGRAM_EVENT_POST_UPDATE)
                        .target(event.getId())
                        .changedFields(changedFields)
                        .build());

        return toResponse(academicProgram, academicProgram.getEvent().getForm());
    }

    /*
     * 이미지 발급도 쓰기와 같은 문을 지난다 — 잠긴 게시물에 올릴 이미지는 본문에 들어갈 길이 없어 고아가 된다.
     * 키 · 공개 주소 · 형식 · 크기 규칙은 전부 EventImageService.issueUploadUrl 그대로다(ADR-0069 «이미지»).
     */
    @Override
    public EventImageUploadResponse issueImageUploadUrl(
            Long academicProgramId, EventImageUploadRequest request, MemberEntity actor) {
        AcademicProgramEntity academicProgram = requireEditable(academicProgramId, actor);
        return eventImageService.issueUploadUrl(academicProgram.getEvent().getId(), request);
    }

    // ------------------------------------------------------------------ 헬퍼

    private AcademicProgramEntity requireEditable(Long academicProgramId, MemberEntity actor) {
        AcademicProgramEntity academicProgram =
                academicProgramWritePolicy.requireLeader(academicProgramId, actor);
        FormEntity form = requireRecruitmentForm(academicProgram);
        FormReceiptStatus receiptStatus = formReceiptPolicy.receiptStatusOf(form);
        if (!EDITABLE_RECEIPT_STATUSES.contains(receiptStatus)) {
            log.warn(
                    "모집 접수가 끝나 행사 게시물이 잠겼다. academicProgramId={}, formId={}, receiptStatus={}",
                    academicProgram.getId(),
                    form.getId(),
                    receiptStatus);
            throw new GeneralException(AcademicProgramErrorCode.ACADEMIC_PROGRAM_EVENT_POST_LOCKED);
        }
        return academicProgram;
    }

    /*
     * 잠긴 이유. 프로그램 상태가 접수보다 앞선다(AcademicProgramEventPostLockReason 주석) — 쓰기 경로가
     * 수료 · 폐지 409를 접수 409보다 먼저 내는 것과 같은 순서다. switch 식이라 상태를 더하면 여기서
     * 컴파일이 멈춘다.
     */
    private AcademicProgramEventPostResponse toResponse(
            AcademicProgramEntity academicProgram, FormEntity form) {
        FormReceiptStatus receiptStatus = formReceiptPolicy.receiptStatusOf(form);
        AcademicProgramEventPostLockReason lockReason =
                switch (academicProgram.getStatus()) {
                    case COMPLETED -> AcademicProgramEventPostLockReason.PROGRAM_COMPLETED;
                    case DISCONTINUED -> AcademicProgramEventPostLockReason.PROGRAM_DISCONTINUED;
                    case APPROVED, ONGOING ->
                            EDITABLE_RECEIPT_STATUSES.contains(receiptStatus)
                                    ? null
                                    : AcademicProgramEventPostLockReason.RECRUITMENT_CLOSED;
                };
        return AcademicProgramEventPostResponse.of(
                academicProgram.getEvent(), receiptStatus, lockReason);
    }

    /*
     * 폼이 없는 프로그램은 409 FORM_NOT_LINKED다 — 승인 후속 처리(#133)가 언제나 빈 모집 폼을 이으므로
     * 정상 흐름에서는 닿지 않는다. 폼이 없으면 «접수가 끝났는가»에 답할 근거가 없어, 열린 것으로도
     * 잠긴 것으로도 지어내지 않는다(모집 폼 조회 #483과 같은 코드).
     */
    private FormEntity requireRecruitmentForm(AcademicProgramEntity academicProgram) {
        FormEntity form = academicProgram.getEvent().getForm();
        if (form == null) {
            throw new GeneralException(AcademicProgramErrorCode.FORM_NOT_LINKED);
        }
        return form;
    }

    private static List<String> changedFieldNames(
            EventEntity event, EventIntroductionUpdateRequest request) {
        List<String> names = new ArrayList<>();
        if (!Objects.equals(event.getTitle(), request.eventTtl())) {
            names.add("eventTtl");
        }
        if (!Objects.equals(event.getContentMarkdown(), request.mtxtCn())) {
            names.add("mtxtCn");
        }
        if (!Objects.equals(event.getThumbnailUrlAddress(), request.thmbUrlAddr())) {
            names.add("thmbUrlAddr");
        }
        if (!Objects.equals(event.getPlaceName(), request.plcNm())) {
            names.add("plcNm");
        }
        return names;
    }

    private AcademicProgramEntity findAcademicProgram(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }
}
