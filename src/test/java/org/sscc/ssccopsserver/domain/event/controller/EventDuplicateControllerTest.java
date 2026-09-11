package org.sscc.ssccopsserver.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.event.service.EventImageLocation;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
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
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/*
 * 행사 복제(ssccops#198 · POST /v1/events/{eventId}/duplicate) 통합 검증.
 *
 * **S3Client를 목으로 갈아 끼운다** — 이미지 복사는 트랜잭션 안에서 일어나므로(FileCopier 주석)
 * 진짜 빈이면 이 테스트가 R2에 붙으려 한다. 확인하려는 것은 복사 알고리즘이 아니라 **어느 키를
 * 어느 키로** 복사해 달라고 했는가와, 그 실패가 무엇이 되는가다.
 *
 * 폼 복제·행사 생성은 EventControllerTest와 같은 표본 방식을 쓴다(국장 역할이 EVENT_MANAGE와
 * 폼 권한에 함께 닿는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class EventDuplicateControllerTest {

    private static final String EVENTS = "/v1/events";

    /** application-test.yaml의 app.public-base-url과 같은 값이어야 한다 */
    private static final String APP_BASE_URL = "https://api.test.local";

    private static final String VALID_COMPOSITION =
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
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;

    @MockitoBean private S3Client r2Client;

    private UUID managerToken;
    private UUID outsiderToken;
    private MemberEntity manager;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260201", "행사운영자");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                manager,
                MemberRoleFixture.DIRECTOR);

        // EVENT_MANAGE가 없는 회원. '다른 권한만' 가진 쪽이어야 403이 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260202", "업무담당");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                outsider,
                AuthorityCode.WORK_MANAGE);

        when(r2Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
    }

    /* ── 승계와 초기화 ───────────────────────────────────── */

    /*
     * 승계/초기화 표를 한 자리에서 못 박는다 — 본문·분류·장소·정원은 따라오고, 제목에는
     * (복사본)이 붙고, 상태는 DRAFT, 기간은 비고, 생성자는 복제한 사람이다. 게시된 원본을
     * 복제해도 사본은 DRAFT다.
     */
    @Test
    void duplicateInheritsContentButResetsTitleStatusAndPeriod() throws Exception {
        Long sourceId =
                createEventWithBody(
                        eventBody(
                                "SEMINAR",
                                "매주 특강",
                                null,
                                "2026-03-10T18:00:00+09:00",
                                "2026-03-10T21:00:00+09:00"));
        changeStatus(sourceId, "PUBLISH").andExpect(status().isOk());

        String response =
                mockMvc.perform(
                                authorized(
                                        post(EVENTS + "/" + sourceId + "/duplicate"), managerToken))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.sourceEventId").value(sourceId))
                        .andExpect(jsonPath("$.data.eventTtl").value("매주 특강 (복사본)"))
                        .andExpect(jsonPath("$.data.eventSttsCd").value("DRAFT"))
                        .andExpect(jsonPath("$.data.formId").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long copyId = JsonPath.parse(response).read("$.data.eventId", Long.class);
        assertThat(copyId).isNotEqualTo(sourceId);

        mockMvc.perform(authorized(get(EVENTS + "/" + copyId), managerToken))
                .andExpect(jsonPath("$.data.eventClsfCd").value("SEMINAR"))
                .andExpect(jsonPath("$.data.mtxtCn").value("# 모집 요강"))
                .andExpect(jsonPath("$.data.plcNm").value("정보과학관 21203"))
                .andExpect(jsonPath("$.data.ptcpLmtCnt").value(40))
                .andExpect(jsonPath("$.data.eventSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.eventBgngDt").isEmpty())
                .andExpect(jsonPath("$.data.eventEndDt").isEmpty())
                .andExpect(jsonPath("$.data.eventPhase").value("NONE"));

        EventEntity copy = eventRepository.findById(copyId).orElseThrow();
        assertThat(copy.getCreator().getId()).isEqualTo(manager.getId());
        // 원본은 그대로다 — 복제가 원본을 건드리지 않는다
        mockMvc.perform(authorized(get(EVENTS + "/" + sourceId), managerToken))
                .andExpect(jsonPath("$.data.eventTtl").value("매주 특강"))
                .andExpect(jsonPath("$.data.eventSttsCd").value("PUBLISHED"));
    }

    /** 참가자 명단은 따라오지 않는다 — 신청한 적 없는 행사에 참가자가 달린다 */
    @Test
    void participantsAreNotInherited() throws Exception {
        Long sourceId = createEvent("EVENT", "참가자 있는 행사");
        saveConfirmedParticipant(sourceId, "20260211");

        Long copyId = duplicate(sourceId);

        EventEntity copy = eventRepository.findById(copyId).orElseThrow();
        assertThat(eventParticipantRepository.existsByEvent(copy)).isFalse();
        mockMvc.perform(authorized(get(EVENTS + "/" + copyId), managerToken))
                .andExpect(jsonPath("$.data.confirmedCount").value(0));
        mockMvc.perform(authorized(get(EVENTS + "/" + sourceId), managerToken))
                .andExpect(jsonPath("$.data.confirmedCount").value(1));
    }

    /* ── [결정 1] 폼도 함께 복제한다 ─────────────────────── */

    /*
     * 사본은 **새 폼**에 연결된다 — 원본 폼을 같이 쓰면 uk_event_form에 걸리기 전에 두 회차의
     * 신청이 한 응답 목록에 섞인다. 새 폼은 폼 복제 규칙 그대로(제목 (복사본) · DRAFT)이고
     * 원본의 연결은 그대로다.
     */
    @Test
    void linkedFormIsDuplicatedRatherThanShared() throws Exception {
        Long formId = createOpenForm("특강 신청서");
        Long sourceId = createEventLinkedTo("SEMINAR", "폼 있는 특강", formId);

        String response =
                mockMvc.perform(
                                authorized(
                                        post(EVENTS + "/" + sourceId + "/duplicate"), managerToken))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.formId").isNumber())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long copyFormId = JsonPath.parse(response).read("$.data.formId", Long.class);
        Long copyId = JsonPath.parse(response).read("$.data.eventId", Long.class);

        assertThat(copyFormId).isNotEqualTo(formId);
        FormEntity copyForm = formRepository.findById(copyFormId).orElseThrow();
        assertThat(copyForm.getTitle()).isEqualTo("특강 신청서 (복사본)");
        assertThat(copyForm.getStatus()).isEqualTo(FormStatus.DRAFT);

        mockMvc.perform(authorized(get(EVENTS + "/" + copyId), managerToken))
                .andExpect(jsonPath("$.data.formId").value(copyFormId));
        mockMvc.perform(authorized(get(EVENTS + "/" + sourceId), managerToken))
                .andExpect(jsonPath("$.data.formId").value(formId));
    }

    /* ── [결정 2] 본문 이미지를 사본의 키로 복사한다 ────── */

    /*
     * 이 행사의 이미지는 전부 `events/{사본}/{같은 파일명}`으로 복사되고 본문·대표 이미지의
     * 주소가 그리로 옮겨진다. 남의 행사 주소는 복사하지도 옮기지도 않는다 — 그 오브젝트는
     * 원본의 소유가 아니다.
     */
    @Test
    void bodyImagesAreCopiedToTheCopysKeysAndReferencesRewritten() throws Exception {
        Long sourceId = createEvent("EVENT", "이미지 있는 행사");
        String poster = UUID.randomUUID() + ".png";
        String photo = UUID.randomUUID() + ".webp";
        String foreign = UUID.randomUUID() + ".jpg";
        String body =
                "![포스터]("
                        + imageUrl(sourceId, poster)
                        + ")\n\n![남의 행사]("
                        + imageUrl(999_999L, foreign)
                        + ")";
        updateEvent(sourceId, "이미지 있는 행사", body, imageUrl(sourceId, photo));

        Long copyId = duplicate(sourceId);

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(r2Client, times(2)).copyObject(captor.capture());
        List<CopyObjectRequest> requests = captor.getAllValues();
        assertThat(requests)
                .allSatisfy(
                        request -> {
                            assertThat(request.sourceBucket()).isEqualTo("test-bucket");
                            assertThat(request.destinationBucket()).isEqualTo("test-bucket");
                        });
        assertThat(requests)
                .extracting(CopyObjectRequest::sourceKey, CopyObjectRequest::destinationKey)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                EventImageLocation.objectKeyOf(sourceId, poster),
                                EventImageLocation.objectKeyOf(copyId, poster)),
                        org.assertj.core.groups.Tuple.tuple(
                                EventImageLocation.objectKeyOf(sourceId, photo),
                                EventImageLocation.objectKeyOf(copyId, photo)));

        mockMvc.perform(authorized(get(EVENTS + "/" + copyId), managerToken))
                .andExpect(
                        jsonPath("$.data.mtxtCn")
                                .value(
                                        "![포스터]("
                                                + imageUrl(copyId, poster)
                                                + ")\n\n![남의 행사]("
                                                + imageUrl(999_999L, foreign)
                                                + ")"))
                .andExpect(jsonPath("$.data.thmbUrlAddr").value(imageUrl(copyId, photo)));
        // 원본의 주소는 그대로다
        mockMvc.perform(authorized(get(EVENTS + "/" + sourceId), managerToken))
                .andExpect(jsonPath("$.data.thmbUrlAddr").value(imageUrl(sourceId, photo)));
    }

    /** 이미지가 없는 행사는 R2에 아무것도 묻지 않는다 */
    @Test
    void eventWithoutImagesDoesNotTouchR2() throws Exception {
        Long sourceId = createEvent("EVENT", "글만 있는 행사");

        duplicate(sourceId);

        verify(r2Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    /*
     * 원본 오브젝트가 없어도 복제는 된다 — 서버는 PUT을 관측하지 않아 본문의 참조가 실물을
     * 가리킨다는 보장이 애초에 없고, 원본에서 이미 깨진 이미지가 복제를 막으면 안 된다.
     * 주소는 그래도 사본의 것으로 옮긴다(원본에서와 같이 깨져 있을 뿐이다).
     */
    @Test
    void missingSourceObjectDoesNotBlockDuplication() throws Exception {
        Long sourceId = createEvent("EVENT", "깨진 이미지 행사");
        String gone = UUID.randomUUID() + ".png";
        updateEvent(sourceId, "깨진 이미지 행사", "![](" + imageUrl(sourceId, gone) + ")", null);
        when(r2Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());

        Long copyId = duplicate(sourceId);

        mockMvc.perform(authorized(get(EVENTS + "/" + copyId), managerToken))
                .andExpect(jsonPath("$.data.mtxtCn").value("![](" + imageUrl(copyId, gone) + ")"));
    }

    /*
     * 그 밖의 R2 실패는 502이며 **트랜잭션 안이라 폼 사본·행사 사본이 함께 되돌아간다.** 되돌아가는
     * 것 자체는 @Transactional 테스트에서 관측할 수 없다(롤백이 테스트 끝에 한 번 일어난다) —
     * 여기서 못 박는 것은 응답 코드와, 실패하는 요청이 마지막이라는 사실이다(AGENTS.md ·
     * 이어지는 요청은 UnexpectedRollbackException을 만난다).
     */
    @Test
    void otherR2FailureReturns502() throws Exception {
        Long sourceId = createEvent("EVENT", "복사 실패 행사");
        updateEvent(
                sourceId,
                "복사 실패 행사",
                "![](" + imageUrl(sourceId, UUID.randomUUID() + ".png") + ")",
                null);
        when(r2Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(SdkException.builder().message("boom").build());

        mockMvc.perform(authorized(post(EVENTS + "/" + sourceId + "/duplicate"), managerToken))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EVENT_IMAGE_COPY_FAILED"));
    }

    /* ── 인증·인가 ───────────────────────────────────────── */

    @Test
    void duplicateUnknownEventReturns404() throws Exception {
        mockMvc.perform(authorized(post(EVENTS + "/999999/duplicate"), managerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    // 생성과 같은 문이다 — EVENT_MANAGE가 없으면 403
    @Test
    void duplicateWithoutEventManageIsForbidden() throws Exception {
        Long sourceId = createEvent("EVENT", "권한 확인 행사");

        mockMvc.perform(authorized(post(EVENTS + "/" + sourceId + "/duplicate"), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post(EVENTS + "/" + sourceId + "/duplicate"))
                .andExpect(status().isUnauthorized());
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

    private Long duplicate(Long sourceId) throws Exception {
        String response =
                mockMvc.perform(
                                authorized(
                                        post(EVENTS + "/" + sourceId + "/duplicate"), managerToken))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.eventId", Long.class);
    }

    private static String imageUrl(long eventId, String fileName) {
        return APP_BASE_URL + EventImageLocation.publicPathOf(eventId, fileName);
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(
            Long eventId, String action) throws Exception {
        return mockMvc.perform(
                authorized(post(EVENTS + "/" + eventId + "/status"), managerToken)
                        .content("{\"action\": \"" + action + "\"}"));
    }

    private void updateEvent(Long eventId, String title, String body, String thumbnail)
            throws Exception {
        String request =
                """
                {
                  "eventClsfCd": "EVENT",
                  "eventTtl": "%s",
                  "mtxtCn": %s,
                  "thmbUrlAddr": %s,
                  "formId": null,
                  "eventBgngDt": null,
                  "eventEndDt": null,
                  "plcNm": "정보과학관 21203",
                  "ptcpLmtCnt": 40
                }
                """
                        .formatted(title, jsonString(body), jsonString(thumbnail));
        mockMvc.perform(
                        authorized(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.put(EVENTS + "/" + eventId),
                                        managerToken)
                                .content(request))
                .andExpect(status().isOk());
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String eventBody(
            String classificationCode, String title, Long formId, String beginAt, String endAt) {
        return """
               {
                 "eventClsfCd": "%s",
                 "eventTtl": "%s",
                 "mtxtCn": "# 모집 요강",
                 "formId": %s,
                 "eventBgngDt": %s,
                 "eventEndDt": %s,
                 "plcNm": "정보과학관 21203",
                 "ptcpLmtCnt": 40
               }
               """
                .formatted(
                        classificationCode,
                        title,
                        formId == null ? "null" : formId,
                        quoteOrNull(beginAt),
                        quoteOrNull(endAt));
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private Long createEvent(String classificationCode, String title) throws Exception {
        return createEventWithBody(eventBody(classificationCode, title, null, null, null));
    }

    private Long createEventLinkedTo(String classificationCode, String title, Long formId)
            throws Exception {
        return createEventWithBody(eventBody(classificationCode, title, formId, null, null));
    }

    private Long createEventWithBody(String body) throws Exception {
        String response =
                mockMvc.perform(authorized(post(EVENTS), managerToken).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.eventId", Long.class);
    }

    /** 폼 API로 OPEN 폼을 만든다 — 국장 역할이 FORM_WRITE에도 닿는다 */
    private Long createOpenForm(String title) throws Exception {
        String body =
                """
                {
                  "formTtlNm": "%s",
                  "formSttsCd": "OPEN",
                  "rcptBgngDt": null,
                  "rcptEndDt": null,
                  "qitemCpstCn": %s
                }
                """
                        .formatted(title, VALID_COMPOSITION);
        String response =
                mockMvc.perform(authorized(post("/v1/forms"), managerToken).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.formId", Long.class);
    }

    private void saveConfirmedParticipant(Long eventId, String studentNumber) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        MemberEntity member = saveMember(UUID.randomUUID(), studentNumber, "참가자");
        eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(
                        event, member, EventParticipantStatus.CONFIRMED, null, manager));
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
