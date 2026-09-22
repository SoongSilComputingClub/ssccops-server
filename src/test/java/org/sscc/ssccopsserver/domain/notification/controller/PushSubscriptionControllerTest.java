package org.sscc.ssccopsserver.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.repository.PushSubscriptionRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 푸시 구독 API (ssccops#446) — #446 계약 그대로: config · 등록(201/200 upsert) · 해지(204 · 자기 것만).
 *
 * test 프로필은 발송기가 Noop이라 config의 publicKey가 null이다 — «키 없이도 컨텍스트가 뜬다»가
 * 수용 기준이고, 키가 있을 때의 값은 VapidWebPushSenderTest가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class PushSubscriptionControllerTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final String ENDPOINT = "https://push.example.test/send/" + UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

    private Long myId;
    private Long otherId;

    @BeforeEach
    void setUp() {
        myId = save(ME, "20200001", "김도현", "me@sscc.org").getId();
        otherId = save(OTHER, "20200002", "이서연", "other@sscc.org").getId();
    }

    @Test
    void configCarriesNoPublicKeyWhenPushIsDisabled() throws Exception {
        mockMvc.perform(get("/v1/push/config").header("Authorization", "Bearer " + ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicKey").doesNotExist());
    }

    @Test
    void configRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/push/config")).andExpect(status().isUnauthorized());
    }

    /* 처음은 201, 같은 endpoint는 200이고 행은 하나다 — 키가 바뀌면 갱신된다 */
    @Test
    void subscribingTwiceWithTheSameEndpointUpsertsOneRow() throws Exception {
        String first =
                mockMvc.perform(subscribe(ME, ENDPOINT, "p256dh-1", "auth-1", "ADMIN"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.subscriptionId").isNumber())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long subscriptionId = JsonPath.parse(first).read("$.data.subscriptionId", Long.class);

        mockMvc.perform(subscribe(ME, ENDPOINT, "p256dh-2", "auth-2", "LMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscriptionId").value(subscriptionId));

        PushSubscriptionEntity stored =
                pushSubscriptionRepository.findById(subscriptionId).orElseThrow();
        assertThat(stored.getP256dhKey()).isEqualTo("p256dh-2");
        assertThat(stored.getAuthKey()).isEqualTo("auth-2");
        assertThat(stored.getApp()).isEqualTo(NotificationApp.LMS);
        assertThat(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).isPresent();
    }

    /* 한 기기를 두 사람이 번갈아 쓰면 마지막에 등록한 사람의 구독이다 */
    @Test
    void reSubscribingAsAnotherMemberMovesTheSubscription() throws Exception {
        mockMvc.perform(subscribe(ME, ENDPOINT, "k", "a", "ADMIN")).andExpect(status().isCreated());
        mockMvc.perform(subscribe(OTHER, ENDPOINT, "k", "a", "ADMIN")).andExpect(status().isOk());

        assertThat(
                        pushSubscriptionRepository
                                .findByEndpoint(ENDPOINT)
                                .orElseThrow()
                                .isOwnedBy(otherId))
                .isTrue();
    }

    @Test
    void unsubscribeDeletesOnlyMyOwnSubscriptionAndIsAlways204() throws Exception {
        mockMvc.perform(subscribe(ME, ENDPOINT, "k", "a", "ADMIN")).andExpect(status().isCreated());

        // 남의 구독은 지워지지 않는다 — 그래도 204
        mockMvc.perform(unsubscribe(OTHER, ENDPOINT)).andExpect(status().isNoContent());
        assertThat(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).isPresent();

        mockMvc.perform(unsubscribe(ME, ENDPOINT)).andExpect(status().isNoContent());
        assertThat(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).isEmpty();

        // 없는 endpoint도 204
        mockMvc.perform(unsubscribe(ME, "https://push.example.test/none"))
                .andExpect(status().isNoContent());
        assertThat(myId).isNotNull();
    }

    @Test
    void subscriptionBodyIsValidated() throws Exception {
        mockMvc.perform(
                        post("/v1/push/subscriptions")
                                .header("Authorization", "Bearer " + ME)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"endpoint\":\"" + ENDPOINT + "\",\"app\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(subscribe(ME, ENDPOINT, "k", "a", "DESKTOP"))
                .andExpect(status().isBadRequest());
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private org.springframework.test.web.servlet.RequestBuilder subscribe(
            UUID who, String endpoint, String p256dh, String auth, String app) {
        String body =
                """
                {
                  "endpoint": "%s",
                  "keys": {"p256dh": "%s", "auth": "%s"},
                  "expirationTime": null,
                  "app": "%s"
                }
                """
                        .formatted(endpoint, p256dh, auth, app);
        return post("/v1/push/subscriptions")
                .header("Authorization", "Bearer " + who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.RequestBuilder unsubscribe(
            UUID who, String endpoint) {
        return delete("/v1/push/subscriptions")
                .header("Authorization", "Bearer " + who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"" + endpoint + "\"}");
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
