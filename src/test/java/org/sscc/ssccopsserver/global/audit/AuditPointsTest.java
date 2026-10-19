package org.sscc.ssccopsserver.global.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.LeaderRole;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
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
import org.sscc.ssccopsserver.support.MemberRoleFixture;
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
    /* 알림 기준표는 SUPER 전용이라 신원을 따로 둔다 — MANAGER에 SUPER를 얹으면 다른 지점의 «권한 하나» 전제가 흐려진다 (#535) */
    private static final UUID ROUTER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private FormRepository formRepository;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final EcsJsonEncoder encoder = new EcsJsonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();
    private Long managerId;
    private Long targetId;
    private MemberEntity managerEntity;

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
        // 시스템 폼 지정(#520)은 FORM_STATUS_CHANGE를 재사용한다 — 접수를 여는 것과 같은 층의 조작
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.FORM_STATUS_CHANGE);
        // 상위 업무 전이(#622)는 WORK_MANAGE다 — 업무를 만드는 것도 같은 권한이다
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.WORK_MANAGE);
        managerEntity = manager;
        MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                PLAIN,
                "20200002",
                "이서연",
                "plain@sscc.org");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        ROUTER,
                        "20200004",
                        "최민서",
                        "router@sscc.org"),
                AuthorityCode.SUPER);
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
        /*
         * 앞 테스트의 줄을 비운다. PER_CLASS라 appender가 클래스에 하나이고 stop()은 목록을 비우지 않는다 —
         * 실패 감사는 즉시 쓰이므로 authz.deny를 남기는 테스트가 둘 이상이면 실행 순서에 따라 onlyLine이
         * 앞 테스트의 줄까지 센다(#694에서 폼 응답 거절 테스트를 더하며 드러났다).
         */
        captured.list.clear();
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

    /*
     * 전체 학술 프로그램 목록(mine 없음)의 거절도 같은 사건이다 (#688). 이 목록은 조건에 따라 갈려
     * @RequireAuthority를 쓰지 못하지만, 거절의 모양(403 FORBIDDEN · authz.deny · decision=요구 권한)은
     * 애노테이션과 같아야 Kibana에서 한 질의로 «누가 운영진 화면을 두드렸나»가 모인다. 요구 권한은
     * #694부터 학술 조회(ACADEMIC_PROGRAM_READ)다(ADR-0068).
     */
    @Test
    void unfilteredAcademicProgramListDenialIsAuditedLikeRequireAuthority() throws Exception {
        mockMvc.perform(get("/v1/academic-programs").header("Authorization", "Bearer " + PLAIN))
                .andExpect(status().isForbidden());

        Map<String, Object> line = onlyLine("authz.deny");
        assertThat(section(line, "event")).containsEntry("outcome", "failure");
        assertThat(section(line, "error")).containsEntry("code", "FORBIDDEN");
        assertThat(section(line, "audit")).containsEntry("decision", "ACADEMIC_PROGRAM_READ");
    }

    /*
     * 폼 응답 조회의 거절도 같은 사건이다 (#694). 클래스 레벨 @RequireAuthority(RESPONSE_REVIEW)를 걷고
     * FormResponseReadPolicy가 판정하게 됐지만, 남는 줄은 애노테이션 시절과 같아야 한다 — 남의 지원서를
     * 두드린 시도가 Kibana에서 경로에 따라 갈리지 않게.
     */
    @Test
    void formResponseReadDenialIsAuditedLikeRequireAuthority() throws Exception {
        mockMvc.perform(get("/v1/forms/1/responses").header("Authorization", "Bearer " + PLAIN))
                .andExpect(status().isForbidden());

        Map<String, Object> line = onlyLine("authz.deny");
        assertThat(section(line, "event")).containsEntry("outcome", "failure");
        assertThat(section(line, "error")).containsEntry("code", "FORBIDDEN");
        assertThat(section(line, "audit")).containsEntry("decision", "RESPONSE_REVIEW");
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

    /*
     * 회원명부 내려받기(#674)는 연도-학기·건수·옵션(코드값)만 남는다 — 파일에 실린 회원의 이름·학번·
     * 연락처는 어느 필드에도 없다. 회장이 있어야 파일이 나가므로 대상 회원에게 회장을 배정한다.
     */
    @Test
    void rosterExportIsAuditedWithoutMemberValues() throws Exception {
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                memberRepository.findById(targetId).orElseThrow(),
                MemberRoleFixture.PRESIDENT);

        mockMvc.perform(
                        get("/v1/members/roster-export")
                                .param("year", "2026")
                                .param("semester", "2")
                                .param("mbrSttsCd", "ENROLLED", "LEAVE")
                                .header("Authorization", "Bearer " + MANAGER))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyLine("member.roster.export");
        assertThat(section(line, "event")).containsEntry("outcome", "success");
        assertThat(section(line, "user")).containsEntry("id", String.valueOf(managerId));
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target")).containsEntry("id", "2026-2");
        assertThat(String.valueOf(audit.get("decision")))
                .contains("rows=")
                .contains("mbrSttsCd=ENROLLED,LEAVE")
                // 직책 표기법 옵션은 걷어냈다(#678) — 옵션은 포함할 상태 하나다
                .doesNotContain("positionNotation");
        assertThat(line.toString())
                .doesNotContain("박준호")
                .doesNotContain("20200003")
                .doesNotContain("target@sscc.org");
    }

    /*
     * 시스템 폼 지정 이동 (#520 · ADR-0044) — 대상은 새 폼, decision은 코드, change는 이전 폼 → 새 폼.
     * 익명 /join이 이 폼을 그리므로 «누가 언제 어느 폼으로»가 남아야 한다. 첫 지정은 before가 없고,
     * 같은 폼을 다시 지정하면(멱등) 아무 줄도 남지 않는다 — 바뀐 것이 없는 사건이 쌓이면 실제 이동
     * 시점을 못 찾는다.
     */
    @Test
    void systemFormDesignationIsAuditedWithPreviousFormId() throws Exception {
        Long first = saveForm("2026-1 신입회원 모집");
        Long second = saveForm("2026-2 신입회원 모집");

        designate(first);
        Map<String, Object> firstLine = onlyLine("form.system.designate");
        assertThat(section(firstLine, "event")).containsEntry("outcome", "success");
        assertThat(section(firstLine, "user")).containsEntry("id", String.valueOf(managerId));
        Map<String, Object> firstAudit = section(firstLine, "audit");
        assertThat(section(firstAudit, "target"))
                .containsEntry("type", "form")
                .containsEntry("id", String.valueOf(first));
        assertThat(firstAudit).containsEntry("decision", "RECRUIT");
        assertThat(section(firstAudit, "change"))
                .doesNotContainKey("before")
                .containsEntry("after", String.valueOf(first));

        captured.list.clear();
        designate(second);
        Map<String, Object> secondLine = onlyLine("form.system.designate");
        assertThat(section(section(secondLine, "audit"), "change"))
                .containsEntry("before", String.valueOf(first))
                .containsEntry("after", String.valueOf(second));

        captured.list.clear();
        designate(second);
        assertThat(captured.list).isEmpty();
    }

    /*
     * 알림 수신 앱 기준표 변경 (#535 · ADR-0047). 정책이 코드 리뷰를 거치지 않는 대가로 ADR이
     * 지목한 자리다 — 대상은 유형 코드, change는 이전 앱 목록 → 새 앱 목록이며 둘 다 코드값이다.
     * 미등록에서 출발하면 before가 NONE이다(«행이 없었다»와 «앱이 없다»를 같은 문자열로 적지 않게).
     */
    @Test
    void notificationTypeRouteChangeIsAudited() throws Exception {
        mockMvc.perform(
                        put("/v1/notifications/types/APPROVAL_REQUESTED")
                                .header("Authorization", "Bearer " + ROUTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apps\": [\"ADMIN\", \"LMS\"]}"))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyLine("notification.type.route");
        assertThat(section(line, "event")).containsEntry("outcome", "success");
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target"))
                .containsEntry("type", "noti_type_rcpn")
                .containsEntry("id", "APPROVAL_REQUESTED");
        assertThat(section(audit, "change"))
                .containsEntry("before", "NONE")
                .containsEntry("after", "ADMIN,LMS");
    }

    /*
     * 되돌리기도 같은 사건이다 (#537). after가 NONE인 것은 «앱이 하나도 없다»가 아니라 «기준표에서
     * 빠져 보낸 앱을 따른다»이며, 같은 상태를 PUT의 before와 다른 문자열로 적으면 한 유형의 이력에
     * 이름이 둘이 된다. 먼저 정해 두는 것은 before가 실제로 옮겨 담기는지 보기 위해서다.
     */
    @Test
    void notificationTypeRouteResetIsAudited() throws Exception {
        mockMvc.perform(
                        put("/v1/notifications/types/RESPONSE_ACCEPTED")
                                .header("Authorization", "Bearer " + ROUTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apps\": [\"WWW\"]}"))
                .andExpect(status().isOk());
        captured.list.clear();

        mockMvc.perform(
                        delete("/v1/notifications/types/RESPONSE_ACCEPTED")
                                .header("Authorization", "Bearer " + ROUTER))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyLine("notification.type.route");
        assertThat(section(line, "event")).containsEntry("outcome", "success");
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target"))
                .containsEntry("type", "noti_type_rcpn")
                .containsEntry("id", "RESPONSE_ACCEPTED");
        assertThat(section(audit, "change"))
                .containsEntry("before", "WWW")
                .containsEntry("after", "NONE");
    }

    /*
     * 상위 업무 전이 (#622 · ssccops#563). 상태 이력 표를 두지 않아 이 줄이 «누가 언제 옮겼나»의
     * 유일한 기록이다 — 대상은 업무, decision은 전이 이름, change는 상태 전후다.
     */
    @Test
    void workTransitionIsAuditedWithStatusChange() throws Exception {
        String created =
                mockMvc.perform(
                                post("/v1/works")
                                        .header("Authorization", "Bearer " + MANAGER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"title\": \"감사 확인용 업무\", \"itemType\":"
                                                        + " \"EVENT\", \"ownerId\": "
                                                        + managerId
                                                        + "}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long workId = mapper.readTree(created).path("data").path("workId").asLong();
        captured.list.clear();

        mockMvc.perform(
                        post("/v1/works/" + workId + "/transitions")
                                .header("Authorization", "Bearer " + MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"transition\": \"START\"}"))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyLine("work.transition");
        assertThat(section(line, "event")).containsEntry("outcome", "success");
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target"))
                .containsEntry("type", "work")
                .containsEntry("id", String.valueOf(workId));
        assertThat(audit).containsEntry("decision", "START");
        assertThat(section(audit, "change"))
                .containsEntry("before", "PLANNING")
                .containsEntry("after", "IN_PROGRESS");
    }

    /*
     * 리더 역할에 권한을 붙이려다 막힌 시도도 남는다 (#687 · ssccops#611). prod에서 스터디장 역할에
     * ACADEMIC_PROGRAM_MANAGE가 붙었는데 누가 언제 붙였는지 되짚을 수 없었다 — 이제는 성공이 불가능하니
     * 남는 것은 시도뿐이고, 그 시도가 «누가 리더를 운영진으로 만들려 했나»다. 롤백 경로라 즉시 쓰인다.
     */
    @Test
    void leaderRoleGrantAttemptIsAuditedAsFailure() throws Exception {
        Long studyLeaderId =
                memberRoleRepository
                        .findAllByNameOrderByIdAsc(LeaderRole.STUDY_LEADER.roleName())
                        .get(0)
                        .getId();

        mockMvc.perform(
                        put("/v1/roles/" + studyLeaderId + "/authorities")
                                .header("Authorization", "Bearer " + ROUTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"authrtCds\": [\"ACADEMIC_PROGRAM_MANAGE\"]}"))
                .andExpect(status().isConflict());

        Map<String, Object> line = onlyLine("role.authority.change");
        assertThat(section(line, "event")).containsEntry("outcome", "failure");
        assertThat(section(line, "error")).containsEntry("code", "LEADER_ROLE_AUTHORITY_FORBIDDEN");
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target")).containsEntry("id", String.valueOf(studyLeaderId));
        assertThat(section(audit, "change")).containsEntry("after", "ACADEMIC_PROGRAM_MANAGE");
        assertThat(roleAuthorityRelationRepository.findAllByRoleId(studyLeaderId)).isEmpty();
    }

    /*
     * 역할 이름 변경이 남는다 (#687). 기획안 승인이 리더 역할을 이름으로 찾으므로 이름이 인가의 열쇠인데
     * 그전에는 흔적이 없었다. 이 클래스는 커밋하는 DB라 시드 역할 대신 새 역할로 본다.
     */
    @Test
    void roleRenameIsAuditedWithNamesBeforeAndAfter() throws Exception {
        Long roleId =
                memberRoleRepository
                        .saveAndFlush(
                                MemberRoleEntity.create(
                                        99,
                                        "감사 확인용 역할",
                                        memberRoleClassificationRepository
                                                .findById("POSITION")
                                                .orElseThrow()))
                        .getId();

        mockMvc.perform(
                        patch("/v1/roles/" + roleId)
                                .header("Authorization", "Bearer " + ROUTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"roleNm\": \"감사 확인용 역할(개명)\"}"))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyLine("role.rename");
        assertThat(section(line, "event")).containsEntry("outcome", "success");
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target"))
                .containsEntry("type", "role")
                .containsEntry("id", String.valueOf(roleId));
        assertThat(section(audit, "change"))
                .containsEntry("before", "감사 확인용 역할")
                .containsEntry("after", "감사 확인용 역할(개명)");
    }

    /*
     * 권한 트리의 부모 이동이 남는다 (#687). 부모를 옮기면 역할 하나 건드리지 않고 그 아래가 다른 묶음
     * 권한에 펼쳐진다. 시드 권한을 옮기면 같은 클래스의 다른 지점(«권한 하나» 전제)이 흔들리므로 새 권한으로 본다.
     */
    @Test
    void authorityParentChangeIsAudited() throws Exception {
        // 트랜잭션 없는 클래스라 엔티티로 부모를 엮으면 지연 로딩이 세션 밖에서 터진다 — API로 만든다
        mockMvc.perform(
                        post("/v1/authorities")
                                .header("Authorization", "Bearer " + ROUTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"authrtCd\": \"AUDIT_PROBE\", \"authrtNm\": \"감사 확인용"
                                                + " 권한\", \"upAuthrtCd\": \"OPERATOR\"}"))
                .andExpect(status().isCreated());
        captured.list.clear();

        mockMvc.perform(
                        patch("/v1/authorities/AUDIT_PROBE")
                                .header("Authorization", "Bearer " + ROUTER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"authrtNm\": \"감사 확인용 권한\", \"upAuthrtCd\":"
                                                + " \"EXECUTIVE\"}"))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyLine("authority.parent.change");
        Map<String, Object> audit = section(line, "audit");
        assertThat(section(audit, "target"))
                .containsEntry("type", "authority")
                .containsEntry("id", "AUDIT_PROBE");
        assertThat(section(audit, "change"))
                .containsEntry("before", "OPERATOR")
                .containsEntry("after", "EXECUTIVE");
    }

    private void designate(Long formId) throws Exception {
        mockMvc.perform(
                        put("/v1/forms/system/RECRUIT")
                                .header("Authorization", "Bearer " + MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"formId\": " + formId + "}"))
                .andExpect(status().isOk());
    }

    private Long saveForm(String title) {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new QuestionCompositionContent.Page("기본 정보", null)),
                        List.of(
                                new QuestionCompositionContent.QuestionItem(
                                        "q1",
                                        "이름",
                                        QuestionItemType.SHORT_TEXT,
                                        true,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        return formRepository
                .saveAndFlush(
                        FormEntity.create(
                                managerEntity, title, composition, null, null, FormStatus.DRAFT))
                .getId();
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
