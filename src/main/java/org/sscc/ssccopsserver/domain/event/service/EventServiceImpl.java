package org.sscc.ssccopsserver.domain.event.service;

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
 * **행사를 지우는 경로는 없다** (ssccops ADR-0014). 예전에는 참가자가 없을 때만 하드 삭제를
 * 허용했는데(D9), 그 규칙이 지키려던 것("행사를 지우면 명단이 갈 곳을 잃는다" · D16)을
 * 보관(ARCHIVE)이 이미 지킨다 — 지우지 않으면 명단도 R2 오브젝트도 갈 곳을 잃지 않는다.
 * 그래서 D9와 EVENT_HAS_PARTICIPANT가 함께 사라졌다.
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

        List<EventEntity> events = eventRepository.findAllForList(statuses, classificationCode);
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
        if (form != null && eventRepository.existsByForm(form)) {
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
            if (nextForm != null && eventRepository.existsByFormAndIdNot(nextForm, event.getId())) {
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

    private EventEntity findEvent(Long eventId) {
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
