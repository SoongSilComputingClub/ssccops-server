package org.sscc.ssccopsserver.global.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
import org.sscc.ssccopsserver.global.logging.EcsJsonEncoder;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/*
 * 감사 지점이 실제 요청 경로에서 남는가 (ssccops#299). 대표 세 자리 — 등급 변경(성공·커밋 뒤) ·
 * 인가 거절(실패·즉시) · 회원 상세 조회(남과 본인).
 *
 * **@Transactional을 걸지 않는다.** 성공 감사는 커밋 뒤에 쓰이는데 테스트 트랜잭션은 커밋이
 * 없다 — 그래서 전용 H2 DB에서 실제로 커밋한다(MemberChangeRollbackTest와 같은 이유).
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                        + "jdbc:h2:mem:audit-points;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditPointsTest {

    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID PLAIN = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final EcsJsonEncoder encoder = new EcsJsonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();
    private Long managerId;
    private Long targetId;

    /* 트랜잭션이 없어 픽스처가 DB에 남는다 — 클래스당 한 번만 세운다 */
    @BeforeAll
    void fixtures() {
        MemberEntity manager =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        MANAGER,
                        "20200001",
                        "김도현",
                        "manager@sscc.org");
        managerId = manager.getId();
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.MEMBER_MANAGE);
        MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                PLAIN,
                "20200002",
                "이서연",
                "plain@sscc.org");
        targetId =
                MemberFixture.save(
                                memberRepository,
                                memberGradeRepository,
                                memberStatusRepository,
                                UUID.randomUUID(),
                                "20200003",
                                "박준호",
                                "target@sscc.org")
                        .getId();
    }

    @BeforeEach
    void setUp() {
        captured.start();
        ((Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME)).addAppender(captured);
        encoder.setContext(
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME)).detachAppender(captured);
        captured.stop();
        encoder.stop();
    }

    @Test
    void gradeChangeIsAuditedAfterCommit() throws Exception {
        mockMvc.perform(
                        post("/v1/members/" + targetId + "/grade-changes")
                                .header("Authorization", "Bearer " + MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"aftrMbrGrdCd\": \"ASSOC\"}"))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyLine("member.grade.change");
        assertThat(section(line, "event")).containsEntry("outcome", "success");
        assertThat(section(line, "user")).containsEntry("id", String.valueOf(managerId));
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target")).containsEntry("id", String.valueOf(targetId));
        assertThat(section(audit, "change"))
                .containsEntry("before", "TEMP")
                .containsEntry("after", "ASSOC");
        // 요청 본문의 사유·이름은 어디에도 없다
        assertThat(line.toString()).doesNotContain("박준호").doesNotContain("김도현");
    }

    @Test
    void authorizationDenialIsAuditedAsFailure() throws Exception {
        mockMvc.perform(get("/v1/members/" + targetId).header("Authorization", "Bearer " + PLAIN))
                .andExpect(status().isForbidden());

        Map<String, Object> line = onlyLine("authz.deny");
        assertThat(section(line, "event")).containsEntry("outcome", "failure");
        assertThat(section(line, "error")).containsEntry("code", "FORBIDDEN"); // 응답의 code와 같다
        assertThat(section(line, "audit")).containsEntry("decision", "MEMBER_MANAGE");
    }

    /* 남의 상세는 남고, 본인 상세는 남지 않는다 */
    @Test
    void memberDetailReadIsAuditedExceptForSelf() throws Exception {
        mockMvc.perform(get("/v1/members/" + targetId).header("Authorization", "Bearer " + MANAGER))
                .andExpect(status().isOk());
        Map<String, Object> line = onlyLine("member.detail.read");
        assertThat(section(section(line, "audit"), "target"))
                .containsEntry("id", String.valueOf(targetId));

        captured.list.clear();
        mockMvc.perform(
                        get("/v1/members/" + managerId)
                                .header("Authorization", "Bearer " + MANAGER))
                .andExpect(status().isOk());
        assertThat(captured.list).isEmpty();
    }

    /* ── 헬퍼: 실제 인코더로 JSON을 만들어 Map으로 — Kibana가 보는 모양 그대로 ─────── */

    private Map<String, Object> onlyLine(String action) {
        List<Map<String, Object>> lines =
                captured.list.stream()
                        .map(this::asJson)
                        .filter(m -> action.equals(section(m, "event").get("action")))
                        .toList();
        assertThat(lines).as("audit lines for " + action).hasSize(1);
        return lines.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> line, String name) {
        Object value = line.get(name);
        assertThat(value).as(name).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asJson(ILoggingEvent event) {
        try {
            return mapper.readValue(encoder.encode(event), Map.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
