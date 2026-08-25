package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemDraft;
import org.sscc.ssccopsserver.domain.academicprogram.dto.ProposalDraft;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 승인된 기획안 → 학술 활동 이관 구현 (#150).
 *
 * ── 만드는 순서와 그 이유 ─────────────────────────────────────
 * Event → AcademicProgram → CurriculumItem[] → 승인 후속 처리(#133). Event가 먼저인 것은
 * AcademicProgram이 그 확장이기 때문이고(event_id NOT NULL), 후속 처리가 마지막인 것은 그것이
 * 이미 저장된 활동을 받기 때문이다. 넷 다 한 트랜잭션이며 중간에 무엇이 실패해도 호출부의
 * ACCEPT까지 되돌아간다 — @Transactional은 REQUIRED(기본값)라 새 트랜잭션을 열지 않는다.
 * REQUIRES_NEW로 열면 이관만 커밋되고 승인이 롤백되는(또는 그 반대의) 경로가 생겨,
 * 이 이슈가 막으려는 상태를 이 이슈가 만들게 된다.
 *
 * ── 이관은 복사다 ─────────────────────────────────────────────
 * 폼 응답을 가리키는 form_rspns_id는 출처 기록이지 연결이 아니다. 이관 뒤 응답을 고쳐도
 * 활동은 바뀌지 않으며(역방향 동기화 없음, ssccops#148), 그것이 안전한 것은 승인이
 * 종결(#141)이라 응답이 더 움직이지 않기 때문이다.
 *
 * ── 이 서비스가 하지 않는 것 ──────────────────────────────────
 * 역할 부여와 빈 모집 폼 생성은 #133이 이미 만든 AcademicProgramApprovalEffectsService를
 * 그대로 부른다. 여기서 다시 구현하면 "승인 직후 무엇이 일어나는가"가 두 벌이 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AcademicProgramMigrationServiceImpl implements AcademicProgramMigrationService {

    /*
     * 서비스 표준 시간대 (AP-12). 폼은 활동 기간을 날짜(DATE 문항)로 받고 event는 일시(Instant)로
     * 갖기 때문에 그 사이를 메울 기준이 필요하다 — UTC로 읽으면 한국 시간 오전 9시 이전이
     * 전날로 밀려 "3월 5일 시작"이 3월 4일로 저장된다.
     */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 이관이 만드는 Event의 분류. 학술 활동 전용 분류(스터디·프로젝트)를 새로 시드하지 않는 것은
     * 그 어휘가 이미 academic_program_type에 있기 때문이다 — 같은 구분을 두 코드테이블이 나눠
     * 가지면 운영진이 한쪽만 늘렸을 때 어느 쪽이 참인지 알 수 없다. event_clsf는 화면에서
     * 추가·수정하는 운영 데이터이므로(ssccops#134) 여기서 고정하는 것은 **초기값**이며,
     * 운영진이 만들어진 행사의 분류를 바꾸는 것은 정상이다(행사 수정 API).
     *
     * 'PROJECT' 분류가 있으니 유형별로 갈라 넣는 쪽도 후보였지만, 스터디에 대응하는 분류가 없어
     * 절반만 맞는 매핑이 된다 — 반쯤 맞는 규칙은 없는 규칙보다 읽기 어렵다.
     */
    private static final String MIGRATED_EVENT_CLASSIFICATION_CODE = "EVENT";

    /*
     * 활동 기간 종료일의 '끝'. LocalTime.MAX(23:59:59.999999999)를 쓰지 않는 것은 나노초가
     * DB의 timestamp 정밀도(마이크로초)에서 반올림돼 **다음 날 00:00:00으로 넘어가기 때문이다** —
     * 실제로 H2에서 6월 30일 종료가 7월 1일 0시로 저장됐다. 초 단위로 끊으면 어느 엔진에서도
     * 값이 그대로 남고, 화면에 보이는 종료 일시도 종료일 그날이다.
     */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final EventRepository eventRepository;
    private final EventClassificationRepository eventClassificationRepository;
    private final AcademicProgramRepository academicProgramRepository;
    private final CurriculumItemRepository curriculumItemRepository;
    private final ProposalResponseParser proposalResponseParser;
    private final AcademicProgramApprovalEffectsService academicProgramApprovalEffectsService;

    @Override
    public AcademicProgramEntity migrate(FormResponseHistoryEntity response) {
        requireNotMigrated(response);

        ProposalDraft draft = proposalResponseParser.parse(response.getContent());
        MemberEntity proposer = response.getMember();

        EventEntity event = eventRepository.save(createEvent(draft, proposer));
        AcademicProgramEntity academicProgram = save(createAcademicProgram(event, response, draft));
        curriculumItemRepository.saveAll(createCurriculumItems(academicProgram, draft));

        /*
         * 여기서부터는 #133의 몫이다 — 리더 역할 부여와 빈 모집 폼 생성. 같은 트랜잭션이라
         * 역할 부여가 ROLE_ALREADY_ASSIGNED로 실패하면 방금 만든 Event·AcademicProgram·
         * CurriculumItem과 폼 응답의 ACCEPT까지 전부 되돌아간다.
         */
        academicProgramApprovalEffectsService.applyPostApprovalEffects(academicProgram);
        return academicProgram;
    }

    /*
     * 행사 본문(mtxt_cn)은 NOT NULL이라 무엇이든 들어가야 한다. 활동 소개·목표를 복사하는 것은
     * 빈 문자열로 두면 승인 직후의 행사가 제목만 있는 껍데기가 되기 때문이다 — 게시 전 상태
     * (DRAFT)라 아무에게도 보이지 않지만, 리더가 모집 공고를 쓸 때 백지가 아니라 자기가 낸
     * 기획안에서 시작하게 된다. 같은 문장이 academic_program.goal_cn에도 있는 것은 중복이 아니라
     * 복사다 — 이관 이후 둘은 각자 편집되는 다른 값이며, 어느 쪽도 다른 쪽을 따라가지 않는다.
     */
    private EventEntity createEvent(ProposalDraft draft, MemberEntity proposer) {
        return EventEntity.create(
                findEventClassification(),
                proposer,
                draft.title(),
                draft.goalContent(),
                null,
                null,
                startOfDay(draft.periodBeginDate()),
                endOfDay(draft.periodEndDate()),
                draft.placeName(),
                draft.capacityMaxCount());
    }

    private AcademicProgramEntity createAcademicProgram(
            EventEntity event, FormResponseHistoryEntity response, ProposalDraft draft) {
        return AcademicProgramEntity.create(
                event,
                response,
                draft.type(),
                draft.goalContent(),
                draft.prepContent(),
                draft.scheduleText(),
                draft.capacityMinCount(),
                draft.capacityMaxCount(),
                response.getMember());
    }

    private static List<CurriculumItemEntity> createCurriculumItems(
            AcademicProgramEntity academicProgram, ProposalDraft draft) {
        List<CurriculumItemEntity> items = new ArrayList<>();
        for (CurriculumItemDraft item : draft.curriculumItems()) {
            items.add(
                    CurriculumItemEntity.create(
                            academicProgram, item.seqno(), item.ttl(), item.planDt()));
        }
        return items;
    }

    /*
     * 중복 이관 방어 (설계 결정 #5). 정상 흐름에서는 도달할 수 없다 — ACCEPTED는 종결 상태라
     * 같은 응답을 두 번 승인하는 경로가 없다(#141). 그럼에도 선조회를 두는 것은 UNIQUE 위반이
     * 원인 모를 500으로 나가지 않게 하기 위해서이며, 동시 요청은 선조회가 막지 못하므로
     * UNIQUE(uk_academic_program_form_rspns)가 그 뒤에 남는다.
     */
    private void requireNotMigrated(FormResponseHistoryEntity response) {
        if (academicProgramRepository.existsByFormResponse(response)) {
            throw new GeneralException(AcademicProgramErrorCode.PROPOSAL_ALREADY_MIGRATED);
        }
    }

    private EventClassificationEntity findEventClassification() {
        return eventClassificationRepository
                .findById(MIGRATED_EVENT_CLASSIFICATION_CODE)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "행사 분류 시드가 없습니다: " + MIGRATED_EVENT_CLASSIFICATION_CODE));
    }

    /*
     * 활동 기간의 종료는 그날의 끝이다. 시작과 같이 자정으로 두면 종료일 당일에 이미 '종료'
     * (EventPhase.ENDED)로 파생돼, 마지막 회차를 하는 날 활동이 끝난 것으로 보인다.
     */
    private static Instant endOfDay(LocalDate date) {
        return date.atTime(END_OF_DAY).atZone(SERVICE_ZONE).toInstant();
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(SERVICE_ZONE).toInstant();
    }

    /*
     * UNIQUE 위반을 409로 옮기는 자리. 선조회를 통과한 동시 요청 둘이 같은 응답을 이관할 때
     * 뒤엣것이 여기로 온다 (회원가입 #20·유형 등록 #130과 같은 규칙).
     *
     * save가 아니라 saveAndFlush인 것은 그러지 않으면 이 위반이 커밋 시점에야 터지기 때문이다 —
     * 그때는 이 트랜잭션을 연 폼 도메인의 호출 스택 밖이라, 학술 도메인의 제약이 무엇인지 모르는
     * 자리에서 원인 모를 500이 된다. 지금 던져야 사유를 붙일 수 있다.
     */
    private AcademicProgramEntity save(AcademicProgramEntity academicProgram) {
        try {
            return academicProgramRepository.saveAndFlush(academicProgram);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(AcademicProgramErrorCode.PROPOSAL_ALREADY_MIGRATED);
        }
    }
}
