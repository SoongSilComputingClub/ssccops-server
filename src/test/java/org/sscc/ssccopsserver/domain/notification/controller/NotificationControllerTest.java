package org.sscc.ssccopsserver.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 알림 API (ssccops#446) — #446 계약 그대로: 목록(커서 · unreadCount) · unread-count · 읽음 · 모두 읽음 ·
 * 남의 알림 404. 알림 행은 리포지토리로 직접 넣는다 — 전이에서 행이 생기는 경로는
 * SubWorkNotificationListenerTest(전용 H2 · 실제 커밋)가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class NotificationControllerTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private NotificationRepository notificationRepository;

    private MemberEntity me;
    private MemberEntity other;

    @BeforeEach
    void setUp() {
        me = save(ME, "20200001", "김도현", "me@sscc.org");
        other = save(OTHER, "20200002", "이서연", "other@sscc.org");
    }

    @Test
    void listPagesNewestFirstWithCursorAndCarriesUnreadCount() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(notify(me, "알림 " + i).getId());
        }
        notify(other, "남의 알림");

        String firstPage =
                mockMvc.perform(
                                get("/v1/notifications")
                                        .param("size", "2")
                                        .header("Authorization", "Bearer " + ME))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items.length()").value(2))
                        .andExpect(jsonPath("$.data.items[0].notificationId").value(ids.get(4)))
                        .andExpect(jsonPath("$.data.items[0].title").value("알림 5"))
                        .andExpect(jsonPath("$.data.items[0].type").value("APPROVAL_REQUESTED"))
                        .andExpect(jsonPath("$.data.items[0].app").value("ADMIN"))
                        .andExpect(
                                jsonPath("$.data.items[0].linkPath")
                                        .value("/operations/sub-works/1"))
                        .andExpect(jsonPath("$.data.items[0].targetType").value("SUB_WORK"))
                        .andExpect(jsonPath("$.data.items[0].targetId").value(1))
                        .andExpect(jsonPath("$.data.items[0].readAt").doesNotExist())
                        .andExpect(jsonPath("$.data.items[0].createdAt").isNotEmpty())
                        .andExpect(jsonPath("$.data.items[1].notificationId").value(ids.get(3)))
                        .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                        .andExpect(jsonPath("$.data.unreadCount").value(5))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(firstPage).read("$.data.nextCursor");

        mockMvc.perform(
                        get("/v1/notifications")
                                .param("size", "2")
                                .param("cursor", cursor)
                                .header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].notificationId").value(ids.get(2)))
                .andExpect(jsonPath("$.data.items[1].notificationId").value(ids.get(1)));

        // 마지막 페이지는 nextCursor가 없다
        mockMvc.perform(
                        get("/v1/notifications")
                                .param("size", "10")
                                .header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void brokenCursorIs400NotFirstPage() throws Exception {
        mockMvc.perform(
                        get("/v1/notifications")
                                .param("cursor", "not-a-cursor!")
                                .header("Authorization", "Bearer " + ME))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unreadCountCountsOnlyMine() throws Exception {
        notify(me, "a");
        notify(me, "b");
        notify(other, "c");

        mockMvc.perform(
                        get("/v1/notifications/unread-count")
                                .header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(2));
    }

    /* 읽음은 멱등이다 — 두 번째 호출도 200이고 처음 읽은 시각이 그대로다 */
    @Test
    void markReadIsIdempotentAndDropsUnreadCount() throws Exception {
        Long id = notify(me, "a").getId();

        String first =
                mockMvc.perform(
                                post("/v1/notifications/{id}/read", id)
                                        .header("Authorization", "Bearer " + ME))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.notificationId").value(id))
                        .andExpect(jsonPath("$.data.readAt").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String readAt = JsonPath.parse(first).read("$.data.readAt");

        mockMvc.perform(
                        post("/v1/notifications/{id}/read", id)
                                .header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readAt").value(readAt));

        mockMvc.perform(
                        get("/v1/notifications/unread-count")
                                .header("Authorization", "Bearer " + ME))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    /* 남의 알림과 없는 알림은 같은 404다 — 어느 번호가 존재하는지 드러내지 않는다 */
    @Test
    void someoneElsesNotificationIs404LikeAMissingOne() throws Exception {
        Long theirs = notify(other, "남의 알림").getId();

        mockMvc.perform(
                        post("/v1/notifications/{id}/read", theirs)
                                .header("Authorization", "Bearer " + ME))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(
                        post("/v1/notifications/{id}/read", 999_999L)
                                .header("Authorization", "Bearer " + ME))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(notificationRepository.findById(theirs).orElseThrow().isRead()).isFalse();
    }

    @Test
    void readAllMarksOnlyMineAndReportsHowMany() throws Exception {
        notify(me, "a");
        notify(me, "b");
        Long theirs = notify(other, "c").getId();

        mockMvc.perform(post("/v1/notifications/read-all").header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(2));
        mockMvc.perform(post("/v1/notifications/read-all").header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(0));

        assertThat(notificationRepository.countByMemberIdAndReadAtIsNull(me.getId())).isZero();
        assertThat(notificationRepository.findById(theirs).orElseThrow().isRead()).isFalse();
    }

    /*
     * 테스트 알림 (#528 · ssccops#454) — 호출자에게 TEST 행 하나, 대상은 (MEMBER, 본인), 링크는 그 앱의
     * «내 정보». test 프로필은 발송기가 Noop이라 pushed가 0이다 — 구독으로 실제 send가 불리고 수가
     * 세어지는 것은 FormResponseReviewNotificationListenerTest(@MockitoBean 발송기)가 본다.
     */
    @Test
    void testNotificationCreatesARowForMeAndReportsZeroPushedWhenSenderIsOff() throws Exception {
        String response =
                mockMvc.perform(
                                post("/v1/notifications/test")
                                        .header("Authorization", "Bearer " + ME)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"app\": \"ADMIN\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.notificationId").isNumber())
                        .andExpect(jsonPath("$.data.pushed").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long id = JsonPath.parse(response).read("$.data.notificationId", Long.class);

        NotificationEntity saved = notificationRepository.findById(id).orElseThrow();
        assertThat(saved.getMember().getId()).isEqualTo(me.getId());
        assertThat(saved.getType()).isEqualTo(NotificationType.TEST);
        assertThat(saved.getTitle()).isEqualTo("[테스트] 알림이 잘 옵니다");
        assertThat(saved.getBody()).isEqualTo("이 기기로 푸시가 오면 설정이 끝난 것입니다");
        assertThat(saved.getApp()).isEqualTo(NotificationApp.ADMIN);
        assertThat(saved.getLinkPath()).isEqualTo("/my");
        assertThat(saved.getTargetType()).isEqualTo(NotificationTargetType.MEMBER);
        assertThat(saved.getTargetId()).isEqualTo(me.getId());

        // 목록에도 바로 보인다 — 종 아이콘이 이 행으로 «알림이 온다»를 보여 준다
        mockMvc.perform(get("/v1/notifications").header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("TEST"))
                .andExpect(jsonPath("$.data.items[0].linkPath").value("/my"))
                .andExpect(jsonPath("$.data.unreadCount").value(1));
    }

    /* www에서 누르면 링크가 /me다 */
    @Test
    void testNotificationForWwwLinksToMe() throws Exception {
        mockMvc.perform(
                        post("/v1/notifications/test")
                                .header("Authorization", "Bearer " + ME)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"app\": \"WWW\"}"))
                .andExpect(status().isOk());

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getMember().getId().equals(me.getId()))
                .singleElement()
                .satisfies(
                        n -> {
                            assertThat(n.getApp()).isEqualTo(NotificationApp.WWW);
                            assertThat(n.getLinkPath()).isEqualTo("/me");
                        });
    }

    /* 회원당 1분 3회 — 네 번째는 429 RATE_LIMITED이고 행도 만들지 않는다. 남(OTHER)의 한도는 따로다 */
    @Test
    void fourthTestNotificationWithinAMinuteIs429() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(
                            post("/v1/notifications/test")
                                    .header("Authorization", "Bearer " + ME)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"app\": \"LMS\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(
                        post("/v1/notifications/test")
                                .header("Authorization", "Bearer " + OTHER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"app\": \"LMS\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/v1/notifications/test")
                                .header("Authorization", "Bearer " + ME)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"app\": \"LMS\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        assertThat(notificationRepository.countByMemberIdAndReadAtIsNull(me.getId())).isEqualTo(3);
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private NotificationEntity notify(MemberEntity recipient, String title) {
        return notificationRepository.save(
                NotificationEntity.create(
                        recipient,
                        NotificationType.APPROVAL_REQUESTED,
                        title,
                        "박람회 · 담당 김도현 · 마감 2026-10-01",
                        NotificationApp.ADMIN,
                        "/operations/sub-works/1",
                        NotificationTargetType.SUB_WORK,
                        1L,
                        null));
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
}
