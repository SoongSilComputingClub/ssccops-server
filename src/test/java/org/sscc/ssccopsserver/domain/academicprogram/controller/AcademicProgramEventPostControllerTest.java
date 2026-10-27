package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 스터디장의 행사 게시물 편집 (ssccops#618 · ADR-0069 · GET · PUT /v1/academic-programs/{id}/event-post ·
 * POST …/event-post/images).
 *
 * 자격은 **리더 한 사람**이다 — 학술국장(ACADEMIC_PROGRAM_MANAGE) · 국원(시드된 그 역할 · READ) · 다른 권한만
 * 가진 회원 모두 403이다. 창은 모집 폼 접수 상태 DRAFT · SCHEDULED · ACCEPTING이고, EXPIRED · CLOSED는 409
 * ACADEMIC_PROGRAM_EVENT_POST_LOCKED, 수료 · 폐지는 AcademicProgramWritePolicy의 409가 먼저다.
 *
 * 접수 상태는 API로 만든다 — 모집 시작(START_RECRUITMENT)의 기간으로 SCHEDULED · ACCEPTING · EXPIRED를,
 * 폼 상태 API의 CLOSE로 CLOSED를 만든다. 고정 Clock 없이 지금 기준 상대 일자를 쓴다
 * (AcademicProgramRecruitmentControllerTest와 같은 이유 — 컨텍스트를 늘리지 않는다).
 *
 * S3Presigner를 목으로 갈아 끼운다(EventImageControllerTest와 같은 구성이라 컨텍스트를 함께 쓴다) — 확인하려는
 * 것은 서명이 아니라 «행사 이미지와 같은 키에 서명을 요청하는가»다.
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다(domain/academicprogram/AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramEventPostControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";

    /** application-test.yaml의 app.public-base-url과 같은 값이어야 한다 */
    private static final String APP_BASE_URL = "https://api.test.local";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    @MockitoBean private S3Presigner r2Presigner;

    private UUID managerToken;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID staffToken;
    private UUID outsiderToken;
    private UUID otherLeaderToken;

    private AcademicProgramEntity program;
    private FormEntity recruitmentForm;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        MemberEntity manager = saveMember(managerToken, "20261201", "학술국장");
        grant(manager, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);
        // 접수 마감(CLOSED)을 폼 상태 API로 만들기 위해서다 — 이 경로의 자격과는 무관하다
        grant(manager, AuthorityCode.FORM_STATUS_CHANGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20261202", "스터디장");

        staffToken = UUID.randomUUID();
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                saveMember(staffToken, "20261203", "국원"),
                MemberRoleFixture.STAFF);

        // 권한이 아예 없는 것이 아니라 '다른 권한만' 가져야 인증만으로는 통과하지 못한다는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20261204", "다른 운영진"), AuthorityCode.WORK_MANAGE);

        // 다른 프로그램의 스터디장도 이 프로그램에는 남이다
        otherLeaderToken = UUID.randomUUID();
        MemberEntity otherLeader = saveMember(otherLeaderToken, "20261205", "다른 스터디장");
        createProgram("남의 스터디", otherLeader);

        program = createProgram("소개 글을 고칠 스터디", leader);
        recruitmentForm = linkForm(program);

        when(r2Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            PutObjectPresignRequest presignRequest = invocation.getArgument(0);
                            return stubPresignedPutObject(presignRequest.putObjectRequest().key());
                        });
    }

    // ------------------------------------------------------------------ 조회

    // 모집 시작 전(폼 DRAFT)에는 열려 있다 — 네 칸과 판정을 함께 내린다
    @Test
    void leaderReadsEditablePostBeforeRecruitment() throws Exception {
        mockMvc.perform(authorized(get(postPath()), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(program.getEvent().getId()))
                .andExpect(jsonPath("$.data.eventTtl").value("소개 글을 고칠 스터디"))
                .andExpect(jsonPath("$.data.mtxtCn").value("본문"))
                .andExpect(jsonPath("$.data.thmbUrlAddr").doesNotExist())
                .andExpect(jsonPath("$.data.plcNm").doesNotExist())
                .andExpect(jsonPath("$.data.receiptStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.isEditable").value(true))
                .andExpect(jsonPath("$.data.lockReason").value(Matchers.nullValue()));
    }

    // ------------------------------------------------------------------ 수정

    /*
     * 네 칸만 바뀌고 나머지는 저장된 값 그대로다 — 분류 · 폼 연결 · 기간 · 정원 · 게시 상태. 받지 않으므로
     * 덮어쓸 수 없다는 것을 DB에서 다시 읽어 확인한다.
     */
    @Test
    void leaderUpdatesOnlyIntroductionFields() throws Exception {
        EventEntity before = program.getEvent();
        String classificationCode = before.getClassification().getCode();
        Instant beginAt = before.getBeginAt();
        Instant endAt = before.getEndAt();
        Integer participantLimit = before.getParticipantLimitCount();
        var eventStatus = before.getStatus();

        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(
                                        introductionBody(
                                                "다듬은 제목",
                                                "## 소개\\n다듬은 본문",
                                                APP_BASE_URL + "/public/v1/events/1/images/x.png",
                                                "정보과학관 21304")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventTtl").value("다듬은 제목"))
                .andExpect(jsonPath("$.data.mtxtCn").value("## 소개\n다듬은 본문"))
                .andExpect(jsonPath("$.data.plcNm").value("정보과학관 21304"))
                .andExpect(jsonPath("$.data.isEditable").value(true));

        entityManager.flush();
        entityManager.clear();
        EventEntity after = eventRepository.findById(program.getEvent().getId()).orElseThrow();
        assertThat(after.getTitle()).isEqualTo("다듬은 제목");
        assertThat(after.getContentMarkdown()).isEqualTo("## 소개\n다듬은 본문");
        assertThat(after.getThumbnailUrlAddress())
                .isEqualTo(APP_BASE_URL + "/public/v1/events/1/images/x.png");
        assertThat(after.getPlaceName()).isEqualTo("정보과학관 21304");
        assertThat(after.getClassification().getCode()).isEqualTo(classificationCode);
        assertThat(after.getForm().getId()).isEqualTo(recruitmentForm.getId());
        assertThat(after.getBeginAt()).isEqualTo(beginAt);
        assertThat(after.getEndAt()).isEqualTo(endAt);
        assertThat(after.getParticipantLimitCount()).isEqualTo(participantLimit);
        assertThat(after.getStatus()).isEqualTo(eventStatus);
    }

    // 접수 예정(SCHEDULED)에도 고칠 수 있다
    @Test
    void leaderUpdatesWhileScheduled() throws Exception {
        startRecruitment(laterBy(7), laterBy(14));

        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody("예정 중 제목", "본문", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.isEditable").value(true));
    }

    // 접수 중(ACCEPTING)에도 고칠 수 있다 — 모집 폼 문항(#483)과 갈리는 자리다
    @Test
    void leaderUpdatesWhileAccepting() throws Exception {
        startRecruitment(earlierBy(1), laterBy(7));

        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody("접수 중 제목", "본문", null, "온라인")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptStatus").value("ACCEPTING"))
                .andExpect(jsonPath("$.data.isEditable").value(true));
    }

    // 접수 기간이 지나면(EXPIRED) 조회는 되고 잠긴 이유가 실리며, 저장은 409다
    @Test
    void expiredRecruitmentLocksThePost() throws Exception {
        startRecruitment(earlierBy(14), earlierBy(7));

        mockMvc.perform(authorized(get(postPath()), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.data.isEditable").value(false))
                .andExpect(jsonPath("$.data.lockReason").value("RECRUITMENT_CLOSED"));

        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody("늦은 제목", "본문", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_EVENT_POST_LOCKED"));
    }

    // 운영자가 접수를 마감하면(CLOSED) 같다
    @Test
    void closedRecruitmentLocksThePost() throws Exception {
        startRecruitment(earlierBy(1), laterBy(7));
        mockMvc.perform(
                        authorized(
                                        post("/v1/forms/" + recruitmentForm.getId() + "/status"),
                                        managerToken)
                                .content("{\"action\": \"CLOSE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(postPath()), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.lockReason").value("RECRUITMENT_CLOSED"));

        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody("늦은 제목", "본문", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_EVENT_POST_LOCKED"));
    }

    /*
     * 수료되면 잠긴 이유가 PROGRAM_COMPLETED이고 저장은 AcademicProgramWritePolicy의 409다 — 수료가 접수 중인
     * 폼을 함께 닫아 접수도 끝나 있지만 프로그램 상태가 앞선다.
     */
    @Test
    void completedProgramLocksThePost() throws Exception {
        startRecruitment(earlierBy(1), laterBy(7));
        transitionProgram("{\"transition\": \"APPROVE_COMPLETION\"}");

        mockMvc.perform(authorized(get(postPath()), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.isEditable").value(false))
                .andExpect(jsonPath("$.data.lockReason").value("PROGRAM_COMPLETED"));

        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody("늦은 제목", "본문", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_COMPLETED"));
    }

    // 폐지는 모집 전에도 온다 — 폼이 DRAFT라 접수 판정으로는 열려 있지만 프로그램 상태가 잠근다
    @Test
    void discontinuedProgramLocksThePost() throws Exception {
        transitionProgram("{\"transition\": \"DISCONTINUE\", \"reason\": \"최소 인원 미달\"}");

        mockMvc.perform(authorized(get(postPath()), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.isEditable").value(false))
                .andExpect(jsonPath("$.data.lockReason").value("PROGRAM_DISCONTINUED"));

        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody("늦은 제목", "본문", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_DISCONTINUED"));
    }

    // ------------------------------------------------------------------ 검증

    // 제목은 행사 저장과 같이 필수다
    @Test
    void blankTitleIsRejected() throws Exception {
        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody(" ", "본문", null, null)))
                .andExpect(status().isBadRequest());
    }

    // 본문 상한도 행사 저장과 같은 413이다
    @Test
    void oversizedContentIsRejectedLikeEventSave() throws Exception {
        mockMvc.perform(
                        authorized(put(postPath()), leaderToken)
                                .content(introductionBody("제목", "가".repeat(100_001), null, null)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("EVENT_CONTENT_TOO_LARGE"));
    }

    // ------------------------------------------------------------------ 자격 — 리더 한 사람

    @Test
    void academicDirectorCannotReadThroughThisPath() throws Exception {
        mockMvc.perform(authorized(get(postPath()), managerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void academicDirectorCannotUpdateThroughThisPath() throws Exception {
        mockMvc.perform(
                        authorized(put(postPath()), managerToken)
                                .content(introductionBody("국장이 고친 제목", "본문", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 국원(시드된 역할 · ACADEMIC_PROGRAM_READ)도 이 경로는 읽지 못한다 — 관계자 조회가 아니다
    @Test
    void staffCannotReadThroughThisPath() throws Exception {
        mockMvc.perform(authorized(get(postPath()), staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void otherMemberCannotUpdate() throws Exception {
        mockMvc.perform(
                        authorized(put(postPath()), outsiderToken)
                                .content(introductionBody("남이 고친 제목", "본문", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 리더 역할을 가졌다는 사실은 열쇠가 아니다 — 다른 프로그램의 스터디장도 남이다
    @Test
    void leaderOfAnotherProgramCannotRead() throws Exception {
        mockMvc.perform(authorized(get(postPath()), otherLeaderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 자격이 잠금보다 먼저다 — 남에게 «접수가 끝났다»를 먼저 알리지 않는다
    @Test
    void forbiddenPrecedesLock() throws Exception {
        startRecruitment(earlierBy(14), earlierBy(7));

        mockMvc.perform(
                        authorized(put(postPath()), outsiderToken)
                                .content(introductionBody("남이 고친 제목", "본문", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unsignedUpPrincipalGetsSignupRequired() throws Exception {
        mockMvc.perform(authorized(get(postPath()), UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    @Test
    void missingProgramIs404() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS + "/999999/event-post"), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 본문 이미지

    // 행사 이미지와 같은 키(events/{eventId}/…)와 같은 영구 주소 · 같은 201이다
    @Test
    void leaderIssuesImageUploadUrlWithEventKeyRule() throws Exception {
        Long eventId = program.getEvent().getId();

        mockMvc.perform(
                        authorized(post(postPath() + "/images"), leaderToken)
                                .content("{\"fileExt\": \"png\", \"fileSize\": 204800}"))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.data.objectKey")
                                .value(Matchers.startsWith("events/" + eventId + "/")))
                .andExpect(
                        jsonPath("$.data.imageUrl")
                                .value(
                                        Matchers.startsWith(
                                                APP_BASE_URL
                                                        + "/public/v1/events/"
                                                        + eventId
                                                        + "/images/")))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.uploadUrl").exists());
    }

    @Test
    void academicDirectorCannotIssueImageUploadUrl() throws Exception {
        mockMvc.perform(
                        authorized(post(postPath() + "/images"), managerToken)
                                .content("{\"fileExt\": \"png\", \"fileSize\": 204800}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void otherMemberCannotIssueImageUploadUrl() throws Exception {
        mockMvc.perform(
                        authorized(post(postPath() + "/images"), outsiderToken)
                                .content("{\"fileExt\": \"png\", \"fileSize\": 204800}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 잠긴 게시물에는 이미지도 발급하지 않는다 — 들어갈 본문이 없어 고아가 된다
    @Test
    void lockedPostRefusesImageUploadUrl() throws Exception {
        startRecruitment(earlierBy(14), earlierBy(7));

        mockMvc.perform(
                        authorized(post(postPath() + "/images"), leaderToken)
                                .content("{\"fileExt\": \"png\", \"fileSize\": 204800}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_EVENT_POST_LOCKED"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private String postPath() {
        return PROGRAMS + "/" + program.getId() + "/event-post";
    }

    private static String introductionBody(
            String title, String content, String thumbnail, String place) {
        return "{\"eventTtl\": %s, \"mtxtCn\": %s, \"thmbUrlAddr\": %s, \"plcNm\": %s}"
                .formatted(quoted(title), quoted(content), quoted(thumbnail), quoted(place));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private void startRecruitment(String beginAt, String endAt) throws Exception {
        transitionProgram(
                """
                {"transition": "START_RECRUITMENT",
                 "recruitmentStartDt": "%s",
                 "recruitmentEndDt": "%s"}
                """
                        .formatted(beginAt, endAt));
    }

    private void transitionProgram(String body) throws Exception {
        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/" + program.getId() + "/transitions"),
                                        managerToken)
                                .content(body))
                .andExpect(status().isOk());
    }

    private static String laterBy(int days) {
        return OffsetDateTime.now(ZoneOffset.ofHours(9))
                .plusDays(days)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    private static String earlierBy(int days) {
        return laterBy(-days);
    }

    private AcademicProgramEntity createProgram(String title, MemberEntity owner) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                "STUDY",
                title,
                owner,
                List.of("OT", "1주차"));
    }

    /* 문항이 하나 있는 DRAFT 모집 폼을 잇는다 — 빈 폼으로는 모집을 시작할 수 없다(FORM_HAS_NO_QUESTION) */
    private FormEntity linkForm(AcademicProgramEntity target) {
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                leader,
                                "모집 폼",
                                new QuestionCompositionContent(
                                        List.of(new Page("기본 정보", null)),
                                        List.of(
                                                new QuestionItem(
                                                        "q1",
                                                        "지원 동기",
                                                        QuestionItemType.LONG_TEXT,
                                                        true,
                                                        0,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null))),
                                null,
                                null,
                                FormStatus.DRAFT));
        EventEntity event = target.getEvent();
        event.linkForm(form);
        eventRepository.saveAndFlush(event);
        return form;
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static PresignedPutObjectRequest stubPresignedPutObject(String objectKey) {
        URI uri =
                URI.create(
                        "https://test-account.r2.cloudflarestorage.com/test-bucket/"
                                + objectKey
                                + "?X-Amz-Signature=stub");
        return PresignedPutObjectRequest.builder()
                .expiration(Instant.now().plusSeconds(600))
                .isBrowserExecutable(false)
                .signedHeaders(Map.of("host", List.of("test-account.r2.cloudflarestorage.com")))
                .httpRequest(SdkHttpRequest.builder().method(SdkHttpMethod.PUT).uri(uri).build())
                .build();
    }
}
