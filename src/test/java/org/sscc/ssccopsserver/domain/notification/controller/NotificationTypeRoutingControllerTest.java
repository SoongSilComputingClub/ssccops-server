package org.sscc.ssccopsserver.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationTypeRecipientRepository;
import org.sscc.ssccopsserver.domain.notification.service.NotificationRoutingPolicy;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 알림 수신 앱 기준표의 조회·수정 API (#535 · ssccops#465 · ADR-0047).
 *
 * test 프로필은 Flyway가 꺼져 있어 V23의 시드가 없다 — 표가 비어 있으므로 모든 유형이 «보낸 앱을
 * 따른다»로 시작하고, 이 클래스가 확인하는 것이 정확히 그 기본값에서 정책을 세우는 경로다.
 *
 * **기준표 캐시는 공용 컨텍스트의 싱글턴이라 트랜잭션 롤백이 비워 주지 않는다** — 앞뒤로 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class NotificationTypeRoutingControllerTest {

    private static final UUID SUPER_USER = UUID.randomUUID();
    private static final UUID PLAIN_USER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationTypeRecipientRepository recipientRepository;
    @Autowired private NotificationRoutingPolicy routingPolicy;

    private MemberEntity superUser;

    @BeforeEach
    void setUp() {
        routingPolicy.invalidate();
        superUser = save(SUPER_USER, "20200001", "김도현", "super@sscc.org");
        save(PLAIN_USER, "20200002", "이서연", "plain@sscc.org");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                superUser,
                AuthorityCode.SUPER);
    }

    @AfterEach
    void forgetRoutingTable() {
        routingPolicy.invalidate();
    }

    /*
     * 유형 목록은 enum에서 온다 — 표가 비어 있어도 12줄이고 전부 «보낸 앱을 따른다»다.
     * label은 화면이 그대로 쓰는 이름이라 여기서 못 박는다.
     */
    @Test
    void listsEveryTypeFromTheEnumAndMarksUnregisteredOnesAsFollowingTheSendingApp()
            throws Exception {
        mockMvc.perform(
                        get("/v1/notifications/types")
                                .header("Authorization", "Bearer " + SUPER_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(NotificationType.values().length))
                .andExpect(jsonPath("$.data[0].type").value("APPROVAL_REQUESTED"))
                .andExpect(jsonPath("$.data[0].label").value("승인 요청"))
                .andExpect(jsonPath("$.data[0].apps.length()").value(0))
                .andExpect(jsonPath("$.data[0].followsSendingApp").value(true));
    }

    /* 저장하면 그 줄의 apps가 채워지고 followsSendingApp이 false가 된다 */
    @Test
    void savingAppsMakesTheTypeFollowTheTable() throws Exception {
        mockMvc.perform(
                        put("/v1/notifications/types/{type}", "APPROVAL_REQUESTED")
                                .header("Authorization", "Bearer " + SUPER_USER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apps\": [\"LMS\", \"ADMIN\", \"ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("APPROVAL_REQUESTED"))
                // 중복은 접히고 순서는 enum 선언 순으로 고정된다
                .andExpect(jsonPath("$.data.apps[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.apps[1]").value("LMS"))
                .andExpect(jsonPath("$.data.apps.length()").value(2))
                .andExpect(jsonPath("$.data.followsSendingApp").value(false));

        assertThat(recipientRepository.findAll())
                .extracting(row -> row.getTypeCode() + ":" + row.getApp())
                .containsExactlyInAnyOrder("APPROVAL_REQUESTED:ADMIN", "APPROVAL_REQUESTED:LMS");
    }

    /*
     * **저장이 캐시를 비운다** (ADR-0047). 저장 직후의 목록 조회가 곧바로 새 정책으로 답하는지를
     * 알림 목록 쪽에서 본다 — 캐시를 비우지 않으면 여기서 0건이 온다.
     */
    @Test
    void savingInvalidatesTheCacheSoTheNextListUsesTheNewPolicy() throws Exception {
        Long id = notify(NotificationType.APPROVAL_REQUESTED, NotificationApp.ADMIN);

        // 아직 미등록이라 행의 app(ADMIN)을 따른다 — lms에서는 보이지 않는다
        mockMvc.perform(
                        get("/v1/notifications")
                                .param("app", "LMS")
                                .header("Authorization", "Bearer " + SUPER_USER))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(
                        put("/v1/notifications/types/{type}", "APPROVAL_REQUESTED")
                                .header("Authorization", "Bearer " + SUPER_USER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apps\": [\"LMS\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/v1/notifications")
                                .param("app", "LMS")
                                .header("Authorization", "Bearer " + SUPER_USER))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notificationId").value(id));
        // 그리고 ADMIN에서는 더 이상 보이지 않는다 — 기준표가 행의 app을 이긴다
        mockMvc.perform(
                        get("/v1/notifications")
                                .param("app", "ADMIN")
                                .header("Authorization", "Bearer " + SUPER_USER))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /* 빈 배열은 400 — 알림을 조용히 끄는 설정을 만들 수 없다 (ADR-0047 «최소 한 앱») */
    @Test
    void anEmptyAppListIsRejected() throws Exception {
        mockMvc.perform(
                        put("/v1/notifications/types/{type}", "APPROVAL_REQUESTED")
                                .header("Authorization", "Bearer " + SUPER_USER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apps\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(recipientRepository.count()).isZero();
    }

    /* 없는 유형은 404다 — 닫힌 집합이라 형식 오류가 아니라 없는 자원이다 */
    @Test
    void anUnknownTypeIs404() throws Exception {
        mockMvc.perform(
                        put("/v1/notifications/types/{type}", "NOT_A_TYPE")
                                .header("Authorization", "Bearer " + SUPER_USER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apps\": [\"ADMIN\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* SUPER가 없으면 조회도 수정도 403이다 — 시스템 전체의 정책이라 «내 알림»과 층이 다르다 */
    @Test
    void withoutSuperBothEndpointsAre403() throws Exception {
        mockMvc.perform(
                        get("/v1/notifications/types")
                                .header("Authorization", "Bearer " + PLAIN_USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(
                        put("/v1/notifications/types/{type}", "APPROVAL_REQUESTED")
                                .header("Authorization", "Bearer " + PLAIN_USER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apps\": [\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private Long notify(NotificationType type, NotificationApp app) {
        return notificationRepository
                .save(
                        NotificationEntity.create(
                                superUser,
                                type,
                                "승인 요청",
                                "박람회 · 담당 김도현 · 마감 2026-10-01",
                                app,
                                "/operations/sub-works/1",
                                NotificationTargetType.SUB_WORK,
                                1L,
                                null))
                .getId();
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
