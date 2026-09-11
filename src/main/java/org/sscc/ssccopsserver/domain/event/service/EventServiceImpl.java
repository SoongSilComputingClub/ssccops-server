package org.sscc.ssccopsserver.domain.event.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.EventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventDuplicateResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventSaveRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventSummaryResponse;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantCount;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.file.service.FileCopier;
import org.sscc.ssccopsserver.domain.file.service.FileEraser;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.service.FormService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import software.amazon.awssdk.core.exception.SdkException;

import lombok.RequiredArgsConstructor;

/*
 * 행사 CRUD·게시 전이의 구현 (ssccops#139).
 *
 * 폼 연결에서 지키는 것은 하나뿐이다 — 폼은 최대 한 행사에만 전속된다(D11 ·
 * FORM_ALREADY_LINKED). 신청이 발생한 연결은 움직이지 못하게 하던 가드(EVENT_FORM_IN_USE)는
 * 걷었고 그 코드도 함께 사라졌다 (#336) — 근거는 updateEvent의 연결 변경 자리에 적혀 있다.
 *
 * **삭제는 소프트 삭제다** (#347 · ssccops ADR-0020, ADR-0014를 뒤집는다). 예전에는 참가자가
 * 없을 때만 하드 삭제를 허용했고(D9 · EVENT_HAS_PARTICIPANT), ADR-0014가 그 경로를 통째로
 * 걷어내 보관(ARCHIVE) 하나로 일원화했다 — 하드 삭제가 학술 활동이 딸린 행사에서 FK 위반
 * 500이 났고 R2 오브젝트가 고아가 됐기 때문이다. 그 뒤 운영진이 "잘못 만든 행사를 치우고
 * 싶다"를 다시 요청했고(보관은 끝난 행사를 내리는 자리지 실수를 치우는 자리가 아니다), 폼이
 * 같은 문제를 소프트 삭제(#329)로 풀어 형판이 검증됐다. 그래서 DELETE가 소프트로 돌아왔다 —
 * 행이 남으므로 학술 FK도 R2 오브젝트도 갈 곳을 잃지 않고, D9는 되살리지 않는다(참가자가
 * 있어도 지운다). 학술 활동이 딸린 행사만 409로 막는다 (deleteEvent 주석).
 *
 * **조회는 전부 살아 있는 행사만 본다** — findEvent가 findByIdAndDeletedAtIsNull이고 목록 질의는
 * where에 조건을 갖는다. 예외는 삭제·복구 경로의 findEventIncludingDeleted 하나다.
 *
 * 모집 판정(receiptStatus)은 EventReceiptPolicy(그 안에서 FormReceiptPolicy)를, 진행 단계
 * (eventPhase)는 EventPhasePolicy를 호출만 한다 — 판정을 여기 복제하면 폼 화면과 행사 화면이
 * 같은 폼을 다르게 말한다(D3). 공개 조회(ssccops#143)도 같은 두 정책을 부른다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventServiceImpl implements EventService {

    /*
     * 본문(mtxt_cn) 글자 수 상한. DB는 TEXT라 제한하지 않으므로 저장 API가 최종 방어선이다 —
     * 폼 응답의 MAX_ANSWER_TOTAL_LENGTH(ResponseAnswerValidator)와 같은 값·같은 판단이다.
     */
    private static final int MAX_CONTENT_LENGTH = 100_000;

    /** 복제본 제목 접미. 폼 복제(FormServiceImpl.COPY_SUFFIX)와 같은 표기라야 두 사본이 같은 모양으로 읽힌다 */
    private static final String COPY_SUFFIX = " (복사본)";

    private final EventRepository eventRepository;
    private final EventClassificationRepository eventClassificationRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final FormRepository formRepository;
    private final EventReceiptPolicy eventReceiptPolicy;
    private final EventPhasePolicy eventPhasePolicy;

    /*
     * 본문에서 빠진 이미지를 지우는 자리 (ssccops#188). 행사 도메인이 이것을 갖는 것은
     * 본문이 곧 참조라는 사실을 아는 것이 이쪽뿐이기 때문이다 — file_rfrnc 행이 없어
     * 파일 도메인은 이 행사에 어떤 오브젝트가 딸려 있는지 알 방법이 없다.
     */
    private final FileEraser fileEraser;

    /*
     * 행사 복제(ssccops#198)가 쓰는 둘. 폼은 **서비스**를 부른다 — 사본 규칙(제목·DRAFT·문항 깊은
     * 복사·응답 미승계·구성 이력)의 주인이 FormServiceImpl.duplicateForm이라, 여기서 FormEntity를
     * 직접 만들면 그 규칙이 두 벌이 된다. 이미지 복사는 지우기(FileEraser)와 같은 이유로 행사
     * 도메인이 갖는다 — 본문이 곧 참조라는 사실을 아는 것이 이쪽뿐이다.
     */
    private final FormService formService;
    private final FileCopier fileCopier;

    /*
     * 삭제 가드(#347)가 "이 행사에 학술 활동이 딸려 있는가"를 묻는 포트. 공개 조회(#187)가 이미
     * 같은 포트로 학술 event를 판별하고 있어 새로 선언하지 않았다 — 학술 저장소를 직접 부르면
     * event → academicprogram → event 순환이 된다(DomainCycleTest · AcademicEventLinkProvider 주석).
     */
    private final AcademicEventLinkProvider academicEventLinkProvider;

    /** 삭제 시각(del_dt)의 출처. Instant.now()를 직접 부르면 테스트에서 고정할 수 없다 (ClockConfig) */
    private final Clock clock;

    /*
     * 행사 목록. 쿼리는 행사(분류·폼 페치 포함) 1 + 확정 참가자 집계 1로 2회다 — 행사마다
     * 참가자를 세면 그대로 N+1이 된다 (DB-13, 폼 목록의 3회 선례).
     *
     * 페이징을 두지 않은 것은 폼 목록(#32)과 같은 결정이다 — 화면이 필터 결과를 한 번에 그린다.
     */
    @Override
    public List<EventSummaryResponse> getEvents(String classificationCode, EventStatus statusCode) {
        // 상태 미지정은 "전체"다. NULL 비교 대신 전체 상태 집합을 넘긴다 (EventRepository 주석)
        Collection<EventStatus> statuses =
                statusCode == null ? EnumSet.allOf(EventStatus.class) : EnumSet.of(statusCode);

        return summariesOf(eventRepository.findAllForList(statuses, classificationCode));
    }

    /*
     * 목록 항목 조립. 운영 목록과 휴지통(#347)이 같은 것을 쓴다 — 두 화면이 같은 카드를 그리므로
     * 집계 규칙(CONFIRMED만 센다)이 두 벌이 되면 안 된다.
     */
    private List<EventSummaryResponse> summariesOf(List<EventEntity> events) {
        if (events.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 뒤따르는 집계를 아예 보내지 않는다
            return List.of();
        }

        List<Long> eventIds = events.stream().map(EventEntity::getId).toList();
        Map<Long, Long> confirmedCountByEventId =
                eventParticipantRepository
                        .countByEventIds(eventIds, EventParticipantStatus.CONFIRMED)
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        EventParticipantCount::getEventId,
                                        EventParticipantCount::getConfirmedCount));

        return events.stream()
                .map(
                        event ->
                                EventSummaryResponse.of(
                                        event,
                                        eventPhasePolicy.phaseOf(event),
                                        eventReceiptPolicy.receiptStatusOf(event),
                                        confirmedCountByEventId.getOrDefault(event.getId(), 0L)))
                .toList();
    }

    @Override
    public EventDetailResponse getEvent(Long eventId) {
        EventEntity event = findEvent(eventId);
        return toDetail(event);
    }

    /*
     * 행사 생성. 상태는 항상 DRAFT다(EventEntity.create) — 만들자마자 공개되는 경로를 두지
     * 않는다. 생성자는 인증 주체이며 요청 본문이 지정할 수 없다.
     */
    @Override
    @Transactional
    public EventDetailResponse createEvent(EventSaveRequest request, MemberEntity creator) {
        requireContentWithinLimit(request.mtxtCn());
        EventClassificationEntity classification = findClassification(request.eventClsfCd());
        FormEntity form = resolveForm(request.formId());
        if (form != null && eventRepository.existsByFormAndDeletedAtIsNull(form)) {
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }

        EventEntity event =
                EventEntity.create(
                        classification,
                        creator,
                        request.eventTtl(),
                        request.mtxtCn(),
                        request.thmbUrlAddr(),
                        form,
                        toInstant(request.eventBgngDt()),
                        toInstant(request.eventEndDt()),
                        request.plcNm(),
                        request.ptcpLmtCnt());

        try {
            eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException ex) {
            // 선조회를 나란히 통과한 동시 연결은 uk_event_form 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }

        return toDetail(event);
    }

    /*
     * 행사 수정. 상태(event_stts_cd)는 이 경로로 바뀌지 않는다 — 요청 DTO에 필드 자체가 없고
     * (폼 PUT 패턴), 상태를 바꾸는 길은 changeStatus 하나다.
     *
     * 폼 연결 규칙(D11)은 연결이 실제로 바뀔 때만 검사한다. 같은 formId를 그대로 되돌려
     * 보내는 저장(편집 화면이 늘 하는 일)에는 볼 것이 없다 — 이미 이 행사에 붙어 있는 폼이라
     * 전속 검사(FORM_ALREADY_LINKED)에 걸릴 것이 없고, 그 검사가 남은 유일한 규칙이다.
     */
    @Override
    @Transactional
    public EventDetailResponse updateEvent(Long eventId, EventSaveRequest request) {
        EventEntity event = findEvent(eventId);
        requireContentWithinLimit(request.mtxtCn());
        EventClassificationEntity classification = findClassification(request.eventClsfCd());

        FormEntity currentForm = event.getForm();
        Long currentFormId = currentForm == null ? null : currentForm.getId();
        boolean linkChanged = !Objects.equals(currentFormId, request.formId());

        FormEntity nextForm = currentForm;
        if (linkChanged) {
            /*
             * **신청이 발생한 뒤에도 연결은 바뀐다** (#336). 여기 있던 두 가드(연결된 폼에 제출
             * 이후 응답이 있거나 이 행사에 참가자가 있으면 409 EVENT_FORM_IN_USE)를 걷었다.
             *
             * 걷은 이유는 그 가드가 지키던 것보다 막아서 잃는 것이 컸기 때문이다 — 폼을 잘못
             * 연결한 행사에 신청이 **한 건이라도** 들어오면 연결을 고칠 길이 영영 없어져,
             * 운영진에게 남는 선택은 행사를 새로 만드는 것뿐이었다. 실제로 그 일이 났다.
             *
             * 연결을 옮길 때 무엇이 끊기는지가 옛 주석의 "소속을 잃는다"인데, 그 말이 가리키는
             * 것은 하나뿐이라 확인해 두고 걷는다.
             *   · **응답은 지워지지 않는다.** 옛 폼에 그대로 남는다. 다만 응답자의 '내 신청'
             *     목록에서 빠져 '내 폼 응답' 쪽으로 옮겨 간다 — 두 목록이 "폼에 붙은 행사가
             *     있는가"로 정확히 갈리기 때문이다(FormResponseHistoryRepository의
             *     findEventApplicationsByMember ↔ findNonEventResponsesByMember).
             *   · **이미 등록된 참가자는 그대로 남는다.** 명단 행은 (event_id, mbr_id)로 행사에
             *     달려 있어 폼 연결과 무관하다(D16 · 영구 보존).
             *   · 사라지는 것은 **옛 폼의 응답을 이 행사의 참가자 등록 근거로 쓸 수 있는 길**
             *     하나다. EventParticipationServiceImpl.findAcceptedApplication이 응답을 행사의
             *     연결 폼으로 좁혀 찾으므로(findByIdAndForm), 옮긴 뒤 옛 폼 응답은 이 행사에서
             *     없는 응답과 같은 404가 된다 — 아직 심사 전인 응답이 "소속을 잃는다"는 것이
             *     정확히 이 뜻이고, 그 이상은 아니다.
             *
             * 막는 대신 끊고, **끊긴다는 것은 웹이 저장 전 경고 팝업으로 알린다** (#336) —
             * 서버가 조용히 하는 일이 아니라 운영진이 알고 누르는 일이 된다.
             *
             * 되살리려거든 위 셋을 먼저 읽어라. 되찾는 것은 심사 전 응답의 등록 경로 하나이고,
             * 대신 다시 잃는 것은 잘못 연결한 폼을 고칠 유일한 길이다.
             */
            nextForm = resolveForm(request.formId());
            if (nextForm != null
                    && eventRepository.existsByFormAndIdNotAndDeletedAtIsNull(
                            nextForm, event.getId())) {
                throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
            }
        }

        /*
         * 본문에서 빠진 이미지를 지우기 위해 **고치기 전 값을 먼저 읽는다** (ssccops#188).
         * update 뒤에 읽으면 이미 새 값이라 비교할 대상이 없다.
         */
        Set<String> referencedBefore =
                EventImageLocation.fileNamesReferencedIn(
                        eventId, event.getContentMarkdown(), event.getThumbnailUrlAddress());

        event.update(
                classification,
                request.eventTtl(),
                request.mtxtCn(),
                request.thmbUrlAddr(),
                nextForm,
                toInstant(request.eventBgngDt()),
                toInstant(request.eventEndDt()),
                request.plcNm(),
                request.ptcpLmtCnt());

        fileEraser.eraseAfterCommit(
                droppedImageKeys(
                        eventId, referencedBefore, request.mtxtCn(), request.thmbUrlAddr()));

        try {
            // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다 — 먼저 흘려보내야 응답의 수정 일시가 실제 값이 된다
            eventRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 선조회를 나란히 통과한 동시 연결은 uk_event_form 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }

        return toDetail(event);
    }

    /*
     * 게시 상태 전이. 전이표·거절은 EventEntity.changeStatus가 갖고 여기서는 조회와 응답
     * 조립만 한다 (LY-02 · FormServiceImpl.changeStatus 선례).
     */
    @Override
    @Transactional
    public EventDetailResponse changeStatus(Long eventId, EventStatusChangeRequest request) {
        EventEntity event = findEvent(eventId);
        event.changeStatus(request.action());

        // mdfcn_dt는 @LastModifiedDate가 flush 시점에 채운다
        eventRepository.flush();

        return toDetail(event);
    }

    /*
     * 행사 복제 (ssccops#198). 폼 복제(FormServiceImpl.duplicateForm)가 세운 축을 그대로 따른다 —
     * 승계하는 것은 **회차가 바뀌어도 같은 것**이고, 초기화하는 것은 **회차마다 반드시 새로
     * 정하는 것**이다.
     *
     * | 승계 | 본문 · 분류 · 장소 · 정원 · 대표 이미지 |
     * | 초기화 | 제목 `(복사본)` · 상태 DRAFT · 행사 기간 |
     * | 승계하지 않음 | 참가자 명단 — (event_id, mbr_id) UNIQUE이고, 신청한 적 없는 행사에 참가자가 달린다 |
     *
     * 생성자는 원본 생성자가 아니라 복제한 회원이다 — 사본을 만든 사람이 사본의 주인이다.
     *
     * **[결정 1] 폼도 함께 복제해 사본을 연결한다.** 그대로 승계하면 두 행사가 같은 신청서를
     * 공유해 3주차·4주차 신청이 한 응답 목록에 섞인다(uk_event_form이 애초에 막는다). 비워 두고
     * 운영자가 다시 연결하게 하는 안은 복제의 목적(손대는 항목 줄이기)이 절반만 달성돼 기각.
     *
     * **[결정 2] 본문 이미지를 사본의 키로 복사한다.** 주소에 행사 번호가 박혀 있어(EventImageLocation)
     * 그대로 두면 사본의 본문이 원본 행사의 이미지를 가리키고, 원본을 보관하는 날
     * requirePublishedEvent가 404를 낸다 — 복제 직후에는 멀쩡하고 원본을 정리하는 날 조용히
     * 깨지는 종류다. "원본을 보관하지 않는다"는 운영 규칙에 기대는 안과 이미지를 떼고 복제하는
     * 안은 기각.
     *
     * **한 트랜잭션이다.** 폼 사본 → 행사 사본(식별자 확보) → 오브젝트 복사 → 주소 치환 순서이며
     * 어느 단계가 실패해도 앞 단계가 함께 되돌아간다 — 폼만 복제된 채 행사가 없거나, 이미지 없는
     * 사본이 남는 조합을 만들지 않는다. 오브젝트 복사가 커밋 뒤가 아니라 안에서 일어나는 이유는
     * FileCopier 주석에 있다.
     */
    @Override
    @Transactional
    public EventDuplicateResponse duplicateEvent(Long eventId, MemberEntity creator) {
        EventEntity source = findEvent(eventId);

        FormEntity formCopy = null;
        if (source.getForm() != null) {
            Long formCopyId = formService.duplicateForm(source.getForm().getId(), creator).formId();
            formCopy = formRepository.getReferenceById(formCopyId);
        }

        EventEntity copy =
                EventEntity.create(
                        source.getClassification(),
                        creator,
                        source.getTitle() + COPY_SUFFIX,
                        source.getContentMarkdown(),
                        source.getThumbnailUrlAddress(),
                        formCopy,
                        null,
                        null,
                        source.getPlaceName(),
                        source.getParticipantLimitCount());
        // 오브젝트 키에 사본의 번호가 들어가므로 먼저 흘려보내 식별자를 받는다
        eventRepository.saveAndFlush(copy);

        copyImages(source, copy);

        return EventDuplicateResponse.of(copy, source.getId());
    }

    /*
     * 원본 본문·대표 이미지가 가리키는 **이 행사의** 오브젝트를 사본의 키로 복사하고 주소를
     * 옮겨 적는다. 남의 행사 주소가 본문에 복사돼 있으면 건드리지 않는다 — 그 오브젝트는 원본의
     * 소유도 아니라서 복사할 근거가 없고, fileNamesReferencedIn이 애초에 세지 않는다.
     */
    private void copyImages(EventEntity source, EventEntity copy) {
        Set<String> fileNames =
                EventImageLocation.fileNamesReferencedIn(
                        source.getId(),
                        source.getContentMarkdown(),
                        source.getThumbnailUrlAddress());
        if (fileNames.isEmpty()) {
            return;
        }
        for (String fileName : fileNames) {
            try {
                fileCopier.copy(
                        EventImageLocation.objectKeyOf(source.getId(), fileName),
                        EventImageLocation.objectKeyOf(copy.getId(), fileName));
            } catch (SdkException ex) {
                // 트랜잭션 안이라 이 예외로 폼 사본·행사 사본이 함께 되돌아간다
                throw new GeneralException(EventErrorCode.EVENT_IMAGE_COPY_FAILED);
            }
        }
        copy.relocateImages(
                EventImageLocation.relocateReferences(
                        source.getId(), copy.getId(), source.getContentMarkdown()),
                EventImageLocation.relocateReferences(
                        source.getId(), copy.getId(), source.getThumbnailUrlAddress()));
    }

    /*
     * 행사 소프트 삭제 (#347 · ADR-0020 · DELETE /v1/events/{eventId}).
     *
     * **참가자 수를 보지 않는다** (ADR-0020 규칙 · 폼 #329와 같은 판단). 참가자가 있으면 못
     * 지우게 하던 D9는 ADR-0014에서 폐기됐고 되살리지 않는다 — 실수로 만든 행사에 신청이 하나만
     * 들어와도 영영 목록에 남는 것이 고치려는 증상이다. 그래서 여기에는 eventParticipantRepository
     * 호출이 없으며, **없는 것이 의도다.** 대가는 참가자의 '내 신청'에서 그 항목이 빠지는 것이고
     * (지운 행사의 신청은 '내 폼 응답'으로 옮겨 간다 — FormResponseHistoryRepository 주석),
     * 되살리면 그대로 돌아온다.
     *
     * **거절하는 것은 학술 활동이 딸린 행사 하나뿐이다** (409 EVENT_HAS_ACADEMIC_PROGRAM).
     * acdm_actv.event_id가 NOT NULL이라 그 행사가 목록·상세에서 사라지면 학술 프로그램이 없는
     * 행사를 가리키게 된다 — ADR-0014가 하드 삭제에서 500으로 발견한 경로를 이번에는 여기서
     * 끊는다. 판정을 상태·분류에서 유추하지 않고 acdm_actv 행의 존재로 묻는 것은 학술 활동도
     * 일반 분류를 쓰기 때문이다. 이 판정이 서비스에 있고 엔티티의 requireDeletable이 아닌 것은
     * 엔티티가 학술 연결을 조회할 수 없어서다(폼 전속 선조회와 같은 이유).
     *
     * **R2 이미지는 지우지 않는다.** 되돌릴 수 있어야 하므로 보관(REPUBLISH)과 같은 판단이다 —
     * 지우면 되살린 행사의 본문 이미지가 통째로 깨진다. 그래서 fileEraser 호출이 없다.
     *
     * **조회는 findEvent가 아니라 del_dt 필터가 없는 조회다.** 이미 지워진 행사를 404로 돌려주면
     * 휴지통을 보고 있는 운영진에게 "없는 행사"와 "이미 지운 행사"가 같은 답이 된다
     * (FormServiceImpl.deleteForm과 같은 판단). 게시 상태는 건드리지 않는다 — 지운 뒤 공개에서
     * 사라지는 것은 조회가 del_dt를 보기 때문이다.
     *
     * 지운 행사는 폼을 놓는다 — 정확히는 uk_event_form(부분 인덱스 · V8)과 전속 선조회가 살아
     * 있는 행사만 세므로, 여기서 연결을 풀지 않아도 다른 행사가 그 폼을 쓸 수 있다. 연결을 실제로
     * 풀지 않는 것은 되살릴 때 그대로 돌아와야 하기 때문이다(restoreEvent).
     */
    @Override
    @Transactional
    public void deleteEvent(Long eventId) {
        EventEntity event = findEventIncludingDeleted(eventId);
        if (event.isDeleted()) {
            throw new GeneralException(EventErrorCode.EVENT_ALREADY_DELETED);
        }
        if (academicEventLinkProvider.academicEventIdsAmong(List.of(eventId)).contains(eventId)) {
            throw new GeneralException(EventErrorCode.EVENT_HAS_ACADEMIC_PROGRAM);
        }
        event.softDelete(Instant.now(clock));
    }

    /*
     * 행사 되살리기 (#347 · POST /v1/events/{eventId}/restore).
     *
     * 되살리기는 del_dt를 비우는 것뿐이다 — 게시 상태·폼 연결·일시·본문·참가자·이미지는 지울 때
     * 그대로 남아 있으므로 되돌릴 것이 없다 (EventEntity.restore 주석).
     *
     * ── 폼이 그새 다른 행사에 붙었으면 복구를 막는다 (409 FORM_ALREADY_LINKED) ──
     *
     * 지운 행사는 폼을 붙잡지 않으므로(uk_event_form이 살아 있는 행사끼리만 걸린다) 지워진 동안
     * 다른 행사가 같은 폼을 연결할 수 있고, 그 뒤에 되살리면 폼 하나에 살아 있는 행사가 둘이 된다.
     * 두 길을 봤다.
     *
     *   · **연결을 풀고 되살린다** — 기각. 되살린 행사가 지우기 전과 다른 것(폼 없는 공지)이 되는데
     *     응답에는 그 사실이 실리지 않아 운영진이 알 수 없고, "되살리면 지우기 전 상태 그대로다"
     *     (ADR-0020 규칙)가 조용히 깨진다. 무엇보다 그 폼의 응답을 어느 행사의 신청으로 볼지가
     *     서버가 임의로 정한 결과가 된다.
     *   · **막는다** ← 채택. 운영진이 그 폼을 새 행사에서 풀거나 새 행사를 지운 뒤 다시 되살리면
     *     된다 — 무엇을 해야 하는지가 오류 코드에 그대로 드러나고, 어느 쪽 행사가 폼을 가질지를
     *     사람이 정한다.
     *
     * 어느 쪽이든 두 행사가 한 폼을 조용히 공유하게 두지는 않는다. 코드를 FORM_ALREADY_LINKED로
     * 두는 것은 사실이 생성·수정의 그것과 같기 때문이다("그 폼은 이미 다른 행사의 것이다").
     * 선조회를 나란히 지나친 경합은 flush에서 uk_event_form 위반으로 드러나 같은 코드로 옮긴다 —
     * 생성·수정과 같은 두 겹이다.
     *
     * 학술 가드를 여기서 다시 보지 않는 것은 학술 활동이 딸린 행사가 애초에 지워지지 않아 휴지통에
     * 있을 수 없기 때문이다. 조건을 하나 더 두면 도달할 수 없는 분기가 생긴다.
     */
    @Override
    @Transactional
    public void restoreEvent(Long eventId) {
        EventEntity event = findEventIncludingDeleted(eventId);
        if (!event.isDeleted()) {
            throw new GeneralException(EventErrorCode.EVENT_NOT_DELETED);
        }
        FormEntity form = event.getForm();
        if (form != null
                && eventRepository.existsByFormAndIdNotAndDeletedAtIsNull(form, event.getId())) {
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }
        event.restore();

        try {
            eventRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 선조회를 나란히 통과한 동시 연결은 uk_event_form 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(EventErrorCode.FORM_ALREADY_LINKED);
        }
    }

    /*
     * 휴지통 목록 (#347 · GET /v1/events/deleted). 지워진 행사만 지운 시각 역순으로 돌려준다.
     *
     * 확정 참가자 집계를 목록과 같이 싣는 것은 같은 조립을 쓰기 때문이며, 그중 confirmedCount는
     * 여기서 특히 값을 한다 — **되살릴지 정하는 사람이 알아야 하는 것이 "이 행사에 참가자가 몇
     * 명이었는가"다.** 지우는 순간 그 사람들의 '내 신청'에서 항목이 사라졌으므로, 되살리기는 그
     * 수만큼의 기록을 되돌리는 일이다 (FormServiceImpl.getDeletedForms의 responseCount와 같은 자리).
     *
     * 게시 상태·eventPhase·receiptStatus도 그대로 계산해 싣는다. 지웠다는 사실이 그 값들을
     * 바꾸지 않으므로 되살렸을 때 어떤 행사가 돌아오는지를 미리 보여 준다.
     */
    @Override
    public List<EventSummaryResponse> getDeletedEvents() {
        return summariesOf(eventRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDescIdDesc());
    }

    /*
     * 공유 링크 발급 전 가드 (ssccops#312 · ADR-0016). 인터페이스 주석에 근거가 있다.
     *
     * 발급 자체는 여기서 하지 않는다 — 토큰을 만드는 것은 `ShareLinkService`의 일이고, 행사
     * 도메인이 답하는 것은 **"이 행사를 지금 공유해도 되는가"** 하나다.
     */
    @Override
    public void requireShareableDraft(Long eventId) {
        if (findEvent(eventId).getStatus() != EventStatus.DRAFT) {
            throw new GeneralException(EventErrorCode.EVENT_SHARE_NOT_DRAFT);
        }
    }

    /*
     * 저장으로 본문·썸네일에서 빠진 이미지의 오브젝트 키 (ssccops#188 · ADR-0014).
     *
     * **static이고 package-private인 것은 이 규칙만 따로 검증하기 위해서다.** 지우는 실제
     * 동작은 커밋 뒤에 일어나는데 통합 테스트는 @Transactional이라 그 시점이 오지 않아,
     * "무엇을 지울 것인가"를 여기서 값으로 확인할 수 있어야 한다.
     *
     * **지우는 대상은 이 행사의 오브젝트뿐이다** — 패턴에 행사 번호가 박혀 있어
     * (EventImageLocation) 남의 행사 주소가 본문에 복사돼 있어도 후보에 들지 않는다.
     *
     * **남는 위험이 하나 있다.** 이 행사의 주소를 다른 행사 본문에 손으로 복사해 둔 상태에서
     * 여기서 그 이미지를 빼면 저쪽이 깨진다. 전 행사 본문을 훑어 참조를 세는 것은 저장마다
     * 전문 검색이라 택하지 않았고, 그 복사를 만드는 자동 경로가 없다는 것을 확인했다 —
     * 기획안 이관(#222)은 폼 응답의 텍스트만 옮기고 폼에는 이미지 문항 자체가 없으며, 행사
     * 복제 기능도 없다.
     *
     * **발급만 받고 본문에 넣지 않은 오브젝트는 잡지 못한다.** 저장된 본문끼리 비교하는
     * 방식이라 저장된 적 없는 것은 비교 대상이 아니다. 그것까지 지우려면 버킷을 훑는 스윕이
     * 필요한데, 이 저장소는 스케줄러를 두지 않기로 두 번 결정했다(폼 초안 90일 정리·폼 자동
     * 마감) — 여기서만 예외를 두지 않는다.
     */
    static List<String> droppedImageKeys(
            long eventId, Set<String> referencedBefore, String bodyAfter, String thumbnailAfter) {
        if (referencedBefore.isEmpty()) {
            return List.of();
        }
        Set<String> referencedAfter =
                EventImageLocation.fileNamesReferencedIn(eventId, bodyAfter, thumbnailAfter);

        return referencedBefore.stream()
                .filter(fileName -> !referencedAfter.contains(fileName))
                .map(fileName -> EventImageLocation.objectKeyOf(eventId, fileName))
                .toList();
    }

    // ------------------------------------------------------------------ 헬퍼

    /*
     * 살아 있는 행사 조회 (#347부터 del_dt를 함께 본다). 지워진 행사는 없는 행사와 같은 404다 —
     * 조건을 질의에 넣는 것은 조회한 뒤 isDeleted()로 거르면 그 분기 하나가 빠지는 것으로
     * 지운 행사가 그 화면에서만 계속 보이기 때문이다.
     */
    private EventEntity findEvent(Long eventId) {
        return eventRepository
                .findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));
    }

    /*
     * 삭제·복구 경로 전용 조회 (#347). **지워진 행사도 찾는다.**
     *
     * findEvent와 갈리는 유일한 자리이며, 그 이유는 이 두 경로만이 "없는 행사"와 "이미 지운
     * 행사"를 구별해 줘야 하기 때문이다 — 조회 계열은 둘을 같은 404로 묶어 존재를 숨기지만,
     * 삭제·복구는 휴지통을 이미 보고 있는 운영진이 부르므로 숨길 것이 없고 다음에 할 일이 갈린다.
     */
    private EventEntity findEventIncludingDeleted(Long eventId) {
        return eventRepository
                .findById(eventId)
                .orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));
    }

    private EventClassificationEntity findClassification(String classificationCode) {
        return eventClassificationRepository
                .findById(classificationCode)
                .orElseThrow(
                        () -> new GeneralException(EventErrorCode.EVENT_CLASSIFICATION_NOT_FOUND));
    }

    /*
     * 폼 연결 해석. NULL은 폼 없는 공지(D11)이고, 없는 폼은 404다.
     *
     * **지워진 폼(#329)도 없는 폼과 같다.** 소프트 삭제된 폼을 행사에 새로 붙일 수 있으면
     * 그 행사의 신청 링크가 열리자마자 404가 되고, 그 원인이 행사 쪽 화면에서는 보이지 않는다.
     * 이미 붙어 있는 폼을 지우는 것은 막지 않는다 — 그쪽은 행사가 폼 없는 공지처럼 서고
     * 되살리면 그대로 돌아온다.
     */
    private FormEntity resolveForm(Long formId) {
        if (formId == null) {
            return null;
        }
        return formRepository
                .findByIdAndDeletedAtIsNull(formId)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }

    private void requireContentWithinLimit(String contentMarkdown) {
        if (contentMarkdown != null && contentMarkdown.length() > MAX_CONTENT_LENGTH) {
            throw new GeneralException(EventErrorCode.EVENT_CONTENT_TOO_LARGE);
        }
    }

    private EventDetailResponse toDetail(EventEntity event) {
        long confirmedCount =
                eventParticipantRepository
                        .countByEventIds(List.of(event.getId()), EventParticipantStatus.CONFIRMED)
                        .stream()
                        .mapToLong(EventParticipantCount::getConfirmedCount)
                        .sum();
        return EventDetailResponse.of(
                event,
                eventPhasePolicy.phaseOf(event),
                eventReceiptPolicy.receiptStatusOf(event),
                confirmedCount);
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
