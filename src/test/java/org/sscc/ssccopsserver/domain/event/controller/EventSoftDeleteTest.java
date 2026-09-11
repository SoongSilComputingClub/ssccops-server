package org.sscc.ssccopsserver.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

/*
 * 행사 소프트 삭제·되살리기(#347 · ssccops ADR-0020) 통합 검증.
 *
 * **이 클래스의 대부분은 "지운 행사가 그 자리에서 보이지 않는가"를 자리마다 하나씩 본다.**
 * ADR-0014가 소프트 삭제를 기각한 이유가 "조회 하나만 필터를 빠뜨려도 지운 행사가 익명 공개
 * 목록에 뜬다"였고, ADR-0020이 그 위험을 감수하는 조건이 바로 이 회귀 테스트다 — 운영 목록 ·
 * 운영 상세 · 공개 목록 · 공개 상세 · 공유 링크 착지 · 내 신청 · 참가자 명단 · 이미지 업로드
 * 발급. 한 메서드에 뭉치지 않는 것은 어느 자리가 새는지가 실패 이름에서 바로 읽혀야 하기
 * 때문이다.
 *
 * 나머지는 규칙이다 — 학술 활동이 딸린 행사는 409 · 참가자가 있어도 지운다 · 되살리면 지우기 전
 * 그대로다 · 지운 행사는 폼을 붙잡지 않는다(그리고 그 폼이 그새 다른 행사에 붙었으면 복구를
 * 막는다) · 휴지통의 행사도 분류를 붙잡는다.
 *
 * 인증 주체 하나가 운영자이자 신청자다(FormSoftDeleteTest와 같은 판단). 여기서 보려는 것은 "지운
 * 뒤 그 신청이 어떻게 보이는가"이고 그 신청이 누구 것인지는 판정에 들어가지 않는다.
 *
 * 실패를 기대하는 요청은 각 테스트의 마지막에 몰아 둔다 — 서비스가 던진 예외가 참여 트랜잭션을
 * rollback-only로 표시하므로, 그 뒤에 성공을 기대하는 요청을 두면 무엇을 확인하는 테스트인지가
 * 흐려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, EventSoftDeleteTest.FixedClockConfig.class})
@Transactional
class EventSoftDeleteTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    /** 고정 기준 시각 (2026-03-15 00:00 KST). 삭제 시각(delDt)이 주입된 Clock에서 오는지 본다 */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    /** 위 시각을 서비스 표준 시간대(AP-12)로 표기한 값 */
    private static final String NOW_IN_SERVICE_ZONE = "2026-03-15T00:00:00+09:00";

    private static final String EVENTS = "/v1/events";

    private static final String SAMPLE_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기본 정보", "pageDescCn": null}],
              "qitems": [{
                "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                "reqYn": true, "pageSeq": 0, "optionList": []
              }]
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;

    private MemberEntity actor;
    private int studentNumberSeq = 1;

    @BeforeEach
    void setUp() {
        actor = saveMember(AUTH_USER_ID, "행사운영자");

        // 삭제·복구·휴지통은 클래스 레벨 EVENT_MANAGE다. 국장(OPERATOR)이 그것과 폼 권한에 함께 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                actor,
                MemberRoleFixture.DIRECTOR);
    }

    /* ── 운영 목록 · 휴지통 ─────────────────────────────── */

    /*
     * 이 이슈의 본래 요구다 — 잘못 만든 행사를 목록에서 치울 방법이 보관(ARCHIVE)뿐이었고, 보관은
     * 끝난 행사를 내리는 자리라 «보관» 칩을 누르면 실수가 계속 나왔다. 지운 행사는 목록 질의에서
     * 통째로 빠지고 휴지통 목록에만 남는다.
     */
    @Test
    void deletedEventLeavesTheAdminListAndLandsInTrash() throws Exception {
        Long kept = saveDraftEvent("남는 행사", null);
        Long removed = saveDraftEvent("지울 행사", null);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + removed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(authenticatedGet(EVENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(kept))
                .andExpect(jsonPath("$.data[0].delDt").isEmpty());

        // 상태 필터를 걸어도 오지 않는다 — 삭제 여부가 게시 상태와 같은 축이었다면 섞여 들어올 자리다
        mockMvc.perform(authenticatedGet(EVENTS + "?eventSttsCd=DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(kept));

        mockMvc.perform(authenticatedGet(EVENTS + "/deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(removed))
                .andExpect(jsonPath("$.data[0].eventTtl").value("지울 행사"))
                .andExpect(jsonPath("$.data[0].eventSttsCd").value("DRAFT"))
                // 삭제 시각은 주입된 Clock에서 온다 — 시스템 시각이면 이 비교가 성립하지 않는다
                .andExpect(jsonPath("$.data[0].delDt").value(NOW_IN_SERVICE_ZONE));

        // 행은 남는다 — 그래서 되살릴 수 있다
        assertThat(eventRepository.findById(removed)).isPresent();
    }

    /* ── 운영 상세 ────────────────────────────────────────── */

    // 상세·수정·전이가 전부 지나는 조회가 같은 404다. 지워진 행사를 계속 고칠 수 있으면 "지웠다"의 뜻이 화면마다 달라진다
    @Test
    void deletedEventIsNotFoundOnAdminDetail() throws Exception {
        Long eventId = saveDraftEvent("지울 행사", null);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet(EVENTS + "/" + eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 공개 목록 ────────────────────────────────────────── */

    /*
     * **ADR-0014가 소프트 삭제를 기각한 이유가 정확히 이 자리다.** 게시 중인 행사를 지우면 상태는
     * PUBLISHED 그대로 남으므로, 공개 목록 질의가 상태만 보면 지운 행사가 익명에게 그대로 나간다.
     */
    @Test
    void deletedEventLeavesThePublicList() throws Exception {
        Long kept = savePublishedEvent("남는 공개 행사", null);
        Long removed = savePublishedEvent("지울 공개 행사", null);

        mockMvc.perform(get("/public/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + removed)).andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(kept));
    }

    /* ── 공개 상세 ────────────────────────────────────────── */

    // 없는 행사와 같은 404다 — 코드를 나누면 그 번호의 행사가 있었다는 사실이 익명에게 새어 나간다
    @Test
    void deletedEventIsNotFoundOnPublicDetail() throws Exception {
        Long eventId = savePublishedEvent("지울 공개 행사", null);

        mockMvc.perform(get("/public/v1/events/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/events/" + eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 공유 링크 착지 ───────────────────────────────────── */

    /*
     * 게시 전에 나눈 공유 링크는 지운 뒤 죽는다. 공유 미리보기는 DRAFT·PUBLISHED를 여는 별도
     * 질의(findByIdAndDeletedAtIsNullAndStatusIn)라 공개 상세와 다른 자리이며, 여기가 새면 치운
     * 행사의 제목이 메신저 카드로 계속 열린다.
     */
    @Test
    void deletedEventClosesTheShareLinkLanding() throws Exception {
        Long eventId = saveDraftEvent("공유한 행사", null);
        String token = issueShareToken(eventId);

        mockMvc.perform(get("/public/v1/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("공유한 행사"));

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/share/{token}", token)).andExpect(status().isNotFound());
    }

    // 발급·조회·폐기도 살아 있는 행사만 지난다 — 지운 행사에 새 링크가 나가면 착지를 막은 것이 의미가 없다
    @Test
    void deletedEventCannotIssueAShareLink() throws Exception {
        Long eventId = saveDraftEvent("지울 행사", null);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedPost(EVENTS + "/" + eventId + "/share"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 내 신청 ──────────────────────────────────────────── */

    /*
     * **이 결정이 치르는 대가다** (ADR-0020). 신청자의 '내 신청'에서 그 항목이 빠진다. 응답 자체는
     * 지워지지 않으므로 '내 폼 응답'으로 옮겨 간다 — 행사에서 폼 연결을 풀었을 때(#336)와 같은
     * 결과이며, 두 목록의 경계("살아 있는 행사가 붙은 폼인가")가 양쪽 질의에서 같아야 같은 응답이
     * 두 줄이 되거나 어디에도 없는 일이 없다.
     */
    @Test
    void deletedEventLeavesTheApplicantsMyApplications() throws Exception {
        FormEntity form = saveOpenForm("신청 폼");
        Long eventId = savePublishedEvent("신청한 행사", form);
        saveSubmittedResponse(form, actor);

        mockMvc.perform(authenticatedGet(EVENTS + "/my-applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(eventId));
        mockMvc.perform(authenticatedGet("/v1/forms/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet(EVENTS + "/my-applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        // 응답은 남는다 — '내 폼 응답'에 선다
        mockMvc.perform(authenticatedGet("/v1/forms/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formId").value(form.getId()));
    }

    /* ── 참가자 명단 ──────────────────────────────────────── */

    // 명단 행은 남지만(D16) 지운 행사의 명단은 없는 행사와 같은 404다. 신청 목록도 같은 조회를 지난다
    @Test
    void deletedEventIsNotFoundOnParticipantList() throws Exception {
        Long eventId = savePublishedEvent("참가자 있는 행사", null);
        saveConfirmedParticipant(eventId, saveMember(UUID.randomUUID(), "참가자"));

        mockMvc.perform(authenticatedGet(EVENTS + "/" + eventId + "/participants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        assertThat(eventParticipantRepository.count()).isEqualTo(1);
        mockMvc.perform(authenticatedGet(EVENTS + "/" + eventId + "/participants"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 이미지 업로드 발급 ───────────────────────────────── */

    /*
     * 지워진 행사에는 올릴 수 없다(ADR-0020 규칙). 이 경로는 엔티티를 읽지 않고 존재 여부만
     * 묻는(existsById) 자리라 필터를 빠뜨리기 쉬운 곳이다 — 새면 편집 화면이 닫힌 행사에
     * 아무도 참조하지 않는 오브젝트가 쌓인다.
     */
    @Test
    void deletedEventDoesNotIssueImageUploadUrl() throws Exception {
        Long eventId = saveDraftEvent("지울 행사", null);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(
                        authenticatedPost(EVENTS + "/" + eventId + "/images")
                                .content("{\"fileExt\": \"png\", \"fileSize\": 1024}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 학술 활동이 딸린 행사 ────────────────────────────── */

    /*
     * acdm_actv.event_id가 NOT NULL이다 — 행사가 없는 것이 되면 학술 프로그램이 고아가 된다.
     * ADR-0014가 하드 삭제에서 500으로 발견한 경로를 이번에는 409로 끊는다. 판정은 acdm_actv 행의
     * 존재이지 분류가 아니다(학술 활동도 일반 분류 "EVENT"를 쓴다).
     */
    @Test
    void eventWithAcademicProgramCannotBeDeleted() throws Exception {
        Long eventId =
                AcademicProgramFixture.save(
                                eventRepository,
                                eventClassificationRepository,
                                academicProgramRepository,
                                academicProgramTypeRepository,
                                curriculumItemRepository,
                                formRepository,
                                formResponseHistoryRepository,
                                "STUDY",
                                "스터디 행사",
                                actor,
                                List.of("1주차"))
                        .getEvent()
                        .getId();

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_HAS_ACADEMIC_PROGRAM"));
    }

    /* ── 참가자가 있어도 지운다 ───────────────────────────── */

    /*
     * **확정된 규칙이다 — 참가자 수를 보지 않는다** (ADR-0020). 참가자가 있으면 못 지우게 하던
     * D9는 되살리지 않는다. 명단 행은 남고, 휴지통은 그 수를 실어 되살릴지 정하는 근거로 준다.
     */
    @Test
    void eventWithParticipantsIsDeletedAnyway() throws Exception {
        Long eventId = savePublishedEvent("참가자 있는 행사", null);
        saveConfirmedParticipant(eventId, saveMember(UUID.randomUUID(), "참가자"));

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet(EVENTS + "/deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventId").value(eventId))
                // 되살릴지 정하는 사람이 알아야 하는 것이 "이 행사에 참가자가 몇 명이었는가"다
                .andExpect(jsonPath("$.data[0].confirmedCount").value(1))
                .andExpect(jsonPath("$.data[0].eventSttsCd").value("PUBLISHED"));

        assertThat(eventParticipantRepository.count()).isEqualTo(1);
    }

    /* ── 되살린다 ──────────────────────────────────────────── */

    /*
     * **되살리기가 이 작업의 필수 항목이다.** 되돌릴 수 없으면 참가자가 있는 행사를 지우는 결정이
     * 하드 삭제와 같아진다. 게시 상태·폼·참가자는 지울 때 그대로이므로 게시 중이던 행사는
     * 되살아나서도 게시 중이고, 신청자의 '내 신청'도 함께 돌아온다.
     */
    @Test
    void restoredEventComesBackExactlyAsItWas() throws Exception {
        FormEntity form = saveOpenForm("신청 폼");
        Long eventId = savePublishedEvent("되살릴 행사", form);
        saveSubmittedResponse(form, actor);
        saveConfirmedParticipant(eventId, actor);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());
        mockMvc.perform(authenticatedPost(EVENTS + "/" + eventId + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(authenticatedGet(EVENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(eventId))
                .andExpect(jsonPath("$.data[0].eventSttsCd").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[0].formId").value(form.getId()))
                .andExpect(jsonPath("$.data[0].confirmedCount").value(1))
                .andExpect(jsonPath("$.data[0].delDt").isEmpty());
        mockMvc.perform(authenticatedGet(EVENTS + "/deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/public/v1/events/" + eventId)).andExpect(status().isOk());
        mockMvc.perform(authenticatedGet(EVENTS + "/my-applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(eventId))
                .andExpect(jsonPath("$.data[0].applicationStatus").value("CONFIRMED"));
    }

    /* ── 폼 전속 ──────────────────────────────────────────── */

    /*
     * **지운 행사는 폼을 붙잡지 않는다.** 잘못 만든 행사를 지웠는데 그 신청 폼을 다른 행사가 못
     * 쓰면 폼까지 새로 만들어야 한다. H2는 부분 인덱스(uk_event_form · V8)를 지원하지 않으므로
     * 여기서 확인하는 것은 선조회(existsByFormAndDeletedAtIsNull)이며, 인덱스 자체는
     * FlywayMigrationValidateTest가 본다.
     */
    @Test
    void deletedEventReleasesItsFormForAnotherEvent() throws Exception {
        FormEntity form = saveOpenForm("재사용할 폼");
        Long removed = saveDraftEvent("지울 행사", form);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + removed)).andExpect(status().isOk());

        mockMvc.perform(authenticatedPost(EVENTS).content(eventBody("새 행사", form.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formId").value(form.getId()));
    }

    /*
     * 그 폼을 다른 행사가 가져간 뒤에는 되살릴 수 없다 — 되살리면 폼 하나에 살아 있는 행사가
     * 둘이 된다. 연결을 풀고 되살리는 안은 기각했다(EventServiceImpl.restoreEvent 주석): 되살린
     * 행사가 지우기 전과 다른 것이 되는데 응답에는 그 사실이 실리지 않는다. 코드가 생성·수정과
     * 같은 FORM_ALREADY_LINKED인 것은 사실이 같기 때문이다.
     */
    @Test
    void restoringIsRefusedWhileAnotherLiveEventHoldsTheForm() throws Exception {
        FormEntity form = saveOpenForm("가져갈 폼");
        Long original = saveDraftEvent("먼저 지운 행사", form);
        mockMvc.perform(authenticatedDelete(EVENTS + "/" + original)).andExpect(status().isOk());
        createEvent("폼을 가져간 행사", form.getId());

        mockMvc.perform(authenticatedPost(EVENTS + "/" + original + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_ALREADY_LINKED"));
    }

    /*
     * 가져간 행사가 폼을 놓으면(여기서는 그 행사를 지운다) 그제야 되살아난다 — 어느 쪽이 폼을
     * 가질지를 서버가 아니라 사람이 정한다. 되살린 행사는 폼 연결까지 지우기 전 그대로다.
     */
    @Test
    void restoringSucceedsOnceTheFormIsReleased() throws Exception {
        FormEntity form = saveOpenForm("가져갈 폼");
        Long original = saveDraftEvent("먼저 지운 행사", form);
        mockMvc.perform(authenticatedDelete(EVENTS + "/" + original)).andExpect(status().isOk());
        Long taker = createEvent("폼을 가져간 행사", form.getId());

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + taker)).andExpect(status().isOk());
        mockMvc.perform(authenticatedPost(EVENTS + "/" + original + "/restore"))
                .andExpect(status().isOk());

        mockMvc.perform(authenticatedGet(EVENTS + "/" + original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").value(form.getId()));
    }

    /* ── 분류 삭제 가드 ───────────────────────────────────── */

    /*
     * 휴지통의 행사도 분류를 붙잡는다 — event.event_clsf_cd가 NOT NULL FK라 지운 행사를 빼고
     * "사용 중 아님"으로 통과시키면 DELETE가 FK 위반 500이 된다(ADR-0014가 하드 삭제에서 발견한
     * 것과 같은 경로). 이 하나가 DeletedAtIsNull 규칙의 의도된 예외이며, 화면의 "사용 중 N건"도
     * 같은 수를 보여 0건인데 지울 수 없는 상태를 만들지 않는다.
     */
    @Test
    void deletedEventStillBlocksClassificationDeletion() throws Exception {
        Long eventId = saveDraftEvent("PROJECT 분류 행사", "PROJECT", null);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet("/v1/event-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.eventClsfCd == 'PROJECT')].eventCount").value(1));
        mockMvc.perform(authenticatedDelete("/v1/event-categories/PROJECT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CLASSIFICATION_IN_USE"));
    }

    /* ── 거절 ─────────────────────────────────────────────── */

    /*
     * 이미 지운 행사를 또 지우는 것은 404가 아니라 409다 — 휴지통을 보고 있는 운영진에게
     * "없는 행사"와 "이미 지운 행사"는 다음에 할 일이 다르다. 조회 계열이 둘을 같은 404로
     * 묶는 것과 일부러 갈린다.
     */
    @Test
    void deletingTwiceIsConflict() throws Exception {
        Long eventId = saveDraftEvent("지울 행사", null);

        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId)).andExpect(status().isOk());
        mockMvc.perform(authenticatedDelete(EVENTS + "/" + eventId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DELETED"));
    }

    // 대칭. 조용히 통과시키면 두 운영진이 같은 휴지통을 열고 있을 때 뒤에 누른 쪽이 자기가 되살렸다고 믿는다
    @Test
    void restoringAnEventThatWasNotDeletedIsConflict() throws Exception {
        Long eventId = saveDraftEvent("멀쩡한 행사", null);

        mockMvc.perform(authenticatedPost(EVENTS + "/" + eventId + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_DELETED"));
    }

    // 없는 행사는 삭제·복구 양쪽에서 404다
    @Test
    void deletingAMissingEventIsNotFound() throws Exception {
        mockMvc.perform(authenticatedDelete(EVENTS + "/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 도우미 ───────────────────────────────────────────── */

    private Long saveDraftEvent(String title, FormEntity form) {
        return saveDraftEvent(title, "EVENT", form);
    }

    private Long saveDraftEvent(String title, String classificationCode, FormEntity form) {
        return eventRepository.saveAndFlush(newEvent(title, classificationCode, form)).getId();
    }

    private Long savePublishedEvent(String title, FormEntity form) {
        EventEntity event = newEvent(title, "EVENT", form);
        event.changeStatus(EventStatusAction.PUBLISH);
        return eventRepository.saveAndFlush(event).getId();
    }

    private EventEntity newEvent(String title, String classificationCode, FormEntity form) {
        EventClassificationEntity classification =
                eventClassificationRepository.findById(classificationCode).orElseThrow();
        return EventEntity.create(
                classification,
                actor,
                title,
                "# 안내",
                null,
                form,
                Instant.parse("2026-04-01T01:00:00Z"),
                Instant.parse("2026-04-01T05:00:00Z"),
                "학생회관",
                null);
    }

    /** 생성 API로 만든다 — 폼 전속 선조회가 실제 경로에서 어떻게 답하는지 봐야 하기 때문이다 */
    private Long createEvent(String title, Long formId) throws Exception {
        String response =
                mockMvc.perform(authenticatedPost(EVENTS).content(eventBody(title, formId)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.eventId", Long.class);
    }

    private String eventBody(String title, Long formId) {
        return """
               {"eventClsfCd": "EVENT", "eventTtl": "%s", "mtxtCn": "# 안내", "formId": %d}
               """
                .formatted(title, formId);
    }

    private String issueShareToken(Long eventId) throws Exception {
        String response =
                mockMvc.perform(authenticatedPost(EVENTS + "/" + eventId + "/share"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.shrTkn", String.class);
    }

    private FormEntity saveOpenForm(String title) throws Exception {
        QuestionCompositionContent content =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        return formRepository.saveAndFlush(
                FormEntity.create(actor, title, content, null, null, FormStatus.OPEN));
    }

    /*
     * 제출된 응답 한 건. 제출 API를 쓰지 않는 것은 이 클래스가 보려는 것이 제출 규칙이 아니라
     * "신청이 있는 행사를 지웠을 때 그 신청이 어떻게 보이는가"이기 때문이다.
     */
    private void saveSubmittedResponse(FormEntity form, MemberEntity respondent) {
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        form, respondent, ResponseContent.of(Map.of("q1", "홍길동")), NOW));
    }

    private void saveConfirmedParticipant(Long eventId, MemberEntity member) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(
                        event, member, EventParticipantStatus.CONFIRMED, null, actor));
    }

    private MemberEntity saveMember(UUID authUserId, String name) {
        String studentNumber = "2026%04d".formatted(studentNumberSeq++);
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }

    private MockHttpServletRequestBuilder authenticatedGet(String path) {
        return authenticated(get(path));
    }

    private MockHttpServletRequestBuilder authenticatedPost(String path) {
        return authenticated(post(path));
    }

    private MockHttpServletRequestBuilder authenticatedDelete(String path) {
        return authenticated(delete(path));
    }

    private static MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + AUTH_USER_ID)
                .contentType(MediaType.APPLICATION_JSON);
    }

    @TestConfiguration
    static class FixedClockConfig {

        /*
         * 삭제 시각(del_dt)이 주입된 Clock에서 오는지 확인해야 하므로 시각을 고정한다.
         * ClockConfig가 정의한 clock 빈과 이름이 겹치지 않게 다른 이름으로 둔다 (FormSoftDeleteTest 선례).
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }
    }
}
