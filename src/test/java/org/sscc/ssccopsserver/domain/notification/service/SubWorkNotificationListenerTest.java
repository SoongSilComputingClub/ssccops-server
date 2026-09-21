package org.sscc.ssccopsserver.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.domain.notification.repository.PushSubscriptionRepository;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 전이 → 알림 행 → 푸시 (ssccops#446 · ADR-0045). #446 수용 기준 1·2·5를 실제 요청 경로로 본다.
 *
 * **@Transactional을 걸지 않는다.** 리스너가 AFTER_COMMIT이라 테스트 트랜잭션(커밋 없음)에서는
 * 돌지 않는다 — 전용 H2에서 실제로 커밋한다(AuditPointsTest와 같은 이유). 게다가 @Async라 결과를
 * Awaitility로 기다린다. 픽스처는 DB에 남으므로 클래스당 한 번 세운다.
 *
 * 발송기는 @MockitoBean이다 — 실제 푸시 서비스는 없고, «구독마다 send가 불리고 GONE이면 구독이
 * 지워진다»(PushDispatcher)를 여기서 본다. 암호화·HTTP는 push/ 아래 단위 테스트가 본다.
 *
 * 유형 '예산지출'의 결재 권한은 SUB_WORK_APPROVE_TREASURER다 — 총무(그 권한을 직접 가진 역할)와
 * 최고관리자(SUPER · APPROVAL의 상위라 펼침으로 갖는다)가 승인자다. **회장은 아니다** — 회장 결재
 * (SUB_WORK_APPROVE_PRESIDENT)는 형제 권한이지 조상이 아니고, ApprovalAuthorityPolicy도 같은 답이라
 * «승인 버튼은 안 보이는데 알림은 오는» 사람이 없다. 총무가 요청하면 최고관리자에게만 간다.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:subwork-notification;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubWorkNotificationListenerTest {

    private static final UUID TREASURER = UUID.randomUUID();
    private static final UUID SUPER_ADMIN = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    // data.sql이 넣는 유형. 1=예산지출(승인 필요 · 총무 결재)
    private static final long SUB_WORK_TYPE_ID = 1L;
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private WorkService workService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

    @MockitoBean private WebPushSender webPushSender;

    private Long treasurerId;
    private Long superAdminId;
    private Long ownerId;
    private Long parentWorkId;

    @BeforeAll
    void fixtures() {
        MemberEntity treasurer = save(TREASURER, "20200001", "김도현", "treasurer@sscc.org");
        MemberEntity superAdmin = save(SUPER_ADMIN, "20200002", "이서연", "super@sscc.org");
        MemberEntity owner = save(OWNER, "20200003", "박준호", "owner@sscc.org");
        treasurerId = treasurer.getId();
        superAdminId = superAdmin.getId();
        ownerId = owner.getId();
        assign(treasurer, MemberRoleFixture.TREASURER);
        assign(superAdmin, "최고관리자");
        parentWorkId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 동아리 박람회",
                                        WorkType.EVENT,
                                        ownerId,
                                        null,
                                        null,
                                        null,
                                        null),
                                treasurer)
                        .workId();
        // 담당자의 브라우저 하나. 발송기가 이 구독으로 불려야 한다
        pushSubscriptionRepository.save(
                PushSubscriptionEntity.subscribe(
                        owner,
                        NotificationApp.ADMIN,
                        "https://push.example.test/owner",
                        "p256dh",
                        "auth",
                        null));
    }

    /*
     * 검토 요청 → 결재 권한 보유자 전원(요청자 제외). 총무가 요청했으므로 최고관리자에게만 가고
     * 담당자에게는 가지 않는다(담당자는 결재 권한이 없다).
     */
    @Test
    void requestReviewNotifiesApproversExceptThePerformer() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        Long subWorkId = createSubWork("부스 배치도 확정");
        transition(TREASURER, subWorkId, "START", null).andExpect(status().isOk());

        transition(TREASURER, subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        superAdminId,
                                                        NotificationType.APPROVAL_REQUESTED,
                                                        subWorkId))
                                        .hasSize(1));
        NotificationEntity notification =
                notificationsOf(superAdminId, NotificationType.APPROVAL_REQUESTED, subWorkId)
                        .get(0);
        assertThat(notification.getTitle()).isEqualTo("[승인 요청] 부스 배치도 확정");
        assertThat(notification.getBody()).isEqualTo("2026 동아리 박람회 · 담당 박준호 · 마감 2099-01-01");
        assertThat(notification.getLinkPath()).isEqualTo("/operations/sub-works/" + subWorkId);
        assertThat(notification.getApp()).isEqualTo(NotificationApp.ADMIN);
        assertThat(notification.getNotificationKey()).isNull();
        assertThat(notificationsOf(treasurerId, NotificationType.APPROVAL_REQUESTED, subWorkId))
                .as("요청자 본인에게는 가지 않는다")
                .isEmpty();
        assertThat(notificationsOf(ownerId, NotificationType.APPROVAL_REQUESTED, subWorkId))
                .as("담당자는 결재 권한이 없어 승인 요청을 받지 않는다")
                .isEmpty();
    }

    /* 승인 → 담당자. 담당자의 구독으로 푸시가 나가고 페이로드는 #446 계약 모양이다 */
    @Test
    void approveNotifiesThePersonInChargeAndPushesToTheirSubscription() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        Long subWorkId = createSubWork("현수막 제작");
        transition(TREASURER, subWorkId, "START", null).andExpect(status().isOk());
        transition(TREASURER, subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());
        for (Long itemId : checklistItemIds(subWorkId)) {
            checkItem(subWorkId, itemId);
        }

        transition(TREASURER, subWorkId, "APPROVE_COMPLETE", null).andExpect(status().isOk());

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        ownerId,
                                                        NotificationType.APPROVAL_APPROVED,
                                                        subWorkId))
                                        .hasSize(1));
        NotificationEntity notification =
                notificationsOf(ownerId, NotificationType.APPROVAL_APPROVED, subWorkId).get(0);
        assertThat(notification.getTitle()).isEqualTo("[승인] 현수막 제작");

        verify(webPushSender, timeout(WAIT.toMillis()))
                .send(
                        argThat(subscription -> subscription.isOwnedBy(ownerId)),
                        argThat(
                                json ->
                                        json.contains("\"notificationId\":" + notification.getId())
                                                && json.contains("\"type\":\"APPROVAL_APPROVED\"")
                                                && json.contains("\"title\":\"[승인] 현수막 제작\"")
                                                && json.contains("\"app\":\"ADMIN\"")
                                                && json.contains(
                                                        "\"linkPath\":\"/operations/sub-works/"
                                                                + subWorkId
                                                                + "\"")));
    }

    /* 반려 → 담당자 */
    @Test
    void rejectNotifiesThePersonInCharge() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        Long subWorkId = createSubWork("포스터 시안");
        transition(TREASURER, subWorkId, "START", null).andExpect(status().isOk());
        transition(TREASURER, subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());

        transition(TREASURER, subWorkId, "REJECT", "예산 초과").andExpect(status().isOk());

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        ownerId,
                                                        NotificationType.APPROVAL_REJECTED,
                                                        subWorkId))
                                        .hasSize(1));
        assertThat(
                        notificationsOf(ownerId, NotificationType.APPROVAL_REJECTED, subWorkId)
                                .get(0)
                                .getTitle())
                .isEqualTo("[반려] 포스터 시안");
    }

    /* 담당자 본인이 자기 건을 승인하면 자기에게는 가지 않는다 (#446 «본인이 본인 건을 승인하면 안 간다») */
    @Test
    void selfApprovalDoesNotNotifyTheApprover() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        Long subWorkId = createSubWork("장부 정리", treasurerId);
        transition(TREASURER, subWorkId, "START", null).andExpect(status().isOk());
        transition(TREASURER, subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());
        for (Long itemId : checklistItemIds(subWorkId)) {
            checkItem(subWorkId, itemId);
        }

        transition(TREASURER, subWorkId, "APPROVE_COMPLETE", null).andExpect(status().isOk());

        // 승인 요청은 최고관리자에게 갔다 — 그것이 «리스너가 돌았다»의 증거이고, 그 뒤 승인 알림은 없어야 한다
        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        superAdminId,
                                                        NotificationType.APPROVAL_REQUESTED,
                                                        subWorkId))
                                        .hasSize(1));
        Thread.sleep(500);
        assertThat(notificationsOf(treasurerId, NotificationType.APPROVAL_APPROVED, subWorkId))
                .isEmpty();
    }

    /* 푸시 서비스가 «구독이 죽었다»고 하면 그 행은 지워진다 (#446 수용 기준 5) */
    @Test
    void goneSubscriptionIsDeletedAfterSending() throws Exception {
        MemberEntity superAdmin = memberRepository.findById(superAdminId).orElseThrow();
        PushSubscriptionEntity dead =
                pushSubscriptionRepository.save(
                        PushSubscriptionEntity.subscribe(
                                superAdmin,
                                NotificationApp.ADMIN,
                                "https://push.example.test/super-old-browser",
                                "p256dh",
                                "auth",
                                null));
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.GONE);
        Long subWorkId = createSubWork("간식 구매");
        transition(TREASURER, subWorkId, "START", null).andExpect(status().isOk());

        transition(TREASURER, subWorkId, "REQUEST_REVIEW", null).andExpect(status().isOk());

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(pushSubscriptionRepository.findById(dead.getId()))
                                        .isEmpty());
        assertThat(notificationsOf(superAdminId, NotificationType.APPROVAL_REQUESTED, subWorkId))
                .as("알림 행은 푸시 결과와 무관하게 남는다")
                .hasSize(1);
        verify(webPushSender, never()).send(argThat(s -> s.isOwnedBy(ownerId)), anyString());
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private List<NotificationEntity> notificationsOf(
            Long memberId, NotificationType type, Long subWorkId) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getMember().getId().equals(memberId))
                .filter(n -> n.getType() == type)
                .filter(n -> n.getTargetId().equals(subWorkId))
                .toList();
    }

    private Long createSubWork(String title) throws Exception {
        return createSubWork(title, ownerId);
    }

    private Long createSubWork(String title, Long owner) throws Exception {
        String body =
                """
                {
                  "workId": %d,
                  "title": "%s",
                  "subWorkTypeId": %d,
                  "ownerId": %d,
                  "dueAt": "2099-01-01T23:59:00+09:00",
                  "content": "박람회 준비"
                }
                """
                        .formatted(parentWorkId, title, SUB_WORK_TYPE_ID, owner);
        String response =
                mockMvc.perform(
                                post("/v1/sub-works")
                                        .header("Authorization", "Bearer " + TREASURER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.subWorkId", Long.class);
    }

    private org.springframework.test.web.servlet.ResultActions transition(
            UUID who, Long subWorkId, String action, String reason) throws Exception {
        String body =
                reason == null
                        ? "{\"transition\": \"%s\"}".formatted(action)
                        : "{\"transition\": \"%s\", \"reason\": \"%s\"}".formatted(action, reason);
        return mockMvc.perform(
                post("/v1/sub-works/{subWorkId}/transitions", subWorkId)
                        .header("Authorization", "Bearer " + who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private List<Long> checklistItemIds(Long subWorkId) throws Exception {
        String response =
                mockMvc.perform(
                                get("/v1/sub-works/{subWorkId}", subWorkId)
                                        .header("Authorization", "Bearer " + TREASURER))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        List<Number> itemIds = JsonPath.parse(response).read("$.data.checklist[*].checklistItemId");
        return itemIds.stream().map(Number::longValue).toList();
    }

    private void checkItem(Long subWorkId, Long itemId) throws Exception {
        mockMvc.perform(
                        patch("/v1/sub-works/{subWorkId}/checklist/{itemId}", subWorkId, itemId)
                                .header("Authorization", "Bearer " + TREASURER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"isCompleted\": true}"))
                .andExpect(status().isOk());
    }

    private MemberEntity save(UUID authUserId, String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                email);
    }

    private void assign(MemberEntity member, String roleName) {
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                member,
                roleName);
    }
}
