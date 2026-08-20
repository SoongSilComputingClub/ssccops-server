package org.sscc.ssccopsserver.domain.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 회원 변경 이력 통합 조회 API (#82).
 *
 * 확인하는 것은 네 가지다 — 세 출처가 **하나의 타임라인**에 시간 역순으로 섞이는가, type
 * 필터가 각각·복수로 동작하는가, 답할 수 없는 것(역할의 변경자)을 답하지 않는가, 표시 명칭이
 * 기준 코드 테이블을 따라오는가.
 *
 * 이력의 crt_dt는 감사 필드라 값을 손으로 정할 수 없어 저장 뒤에 직접 갈아 둔다 — 그러지
 * 않으면 네 행의 시각이 밀리초 안에서 겹쳐 순서가 흔들린다 (MemberQueryServiceTest와 같은
 * 방식). 역할 사건의 시각은 crt_dt가 아니라 role_bgng_ymd·role_end_ymd에서 오므로 손댈
 * 필요가 없고, 그 사실 자체가 이 테스트의 정렬 기대값에 드러난다.
 *
 * 역할 사건의 발생 시각이 주입된 Clock의 시간대로 계산되는지 봐야 하므로 Clock을 고정한다.
 *
 * 거절되는 요청은 **요청 하나로 끝낸다.** 서비스 트랜잭션 안에서 예외가 나면 이 테스트의
 * 트랜잭션이 rollback-only가 되어 뒤따르는 요청이 UnexpectedRollbackException을 만난다
 * (MemberChangeControllerTest와 같은 이유).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberHistoryControllerTest.HistoryTestConfig.class)
@Transactional
class MemberHistoryControllerTest {

    private static final String MEMBERS = "/v1/members";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /** 주입된 Clock이 말하는 오늘. 실제 시스템 날짜와 일부러 다른 값이다 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    /** MEMBER_MANAGE를 가진 주체 */
    private static final UUID MANAGER = UUID.randomUUID();

    /** 가입은 했으나 아무 권한도 없는 주체 */
    private static final UUID PLAIN_MEMBER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @PersistenceContext private EntityManager entityManager;

    private Long targetMemberId;
    private Long emptyMemberId;

    @BeforeEach
    void setUp() {
        MemberEntity manager = saveMember(MANAGER, "20200001", "김도현");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.MEMBER_MANAGE);

        saveMember(PLAIN_MEMBER, "20200002", "이서연");

        MemberEntity target = saveMember(UUID.randomUUID(), "20200003", "박준호");
        targetMemberId = target.getId();

        // 이력이 하나도 없는 회원. #78 이전에 만들어진 회원이 실제로 이 상태다
        emptyMemberId = saveMember(UUID.randomUUID(), "20200004", "최유진").getId();

        MemberGradeEntity temp = grade(MemberGradeCode.TEMP);
        MemberGradeEntity active = grade(MemberGradeCode.ACTIVE);
        MemberStatusEntity enrolled = memberStatus(MemberStatusCode.ENROLLED);
        MemberStatusEntity onLeave = memberStatus(MemberStatusCode.LEAVE);

        // 가입 이력 두 건. 상태 쪽 변경자를 비워 두는 것은 이관으로 들어온 이력을 흉내 낸 것이다
        Long signupGrade =
                memberGradeHistoryRepository
                        .save(
                                MemberGradeHistoryEntity.create(
                                        target,
                                        null,
                                        temp,
                                        LocalDate.of(2025, 12, 1),
                                        "회원가입",
                                        target))
                        .getId();
        Long signupStatus =
                memberStatusHistoryRepository
                        .save(
                                MemberStatusHistoryEntity.create(
                                        target,
                                        null,
                                        enrolled,
                                        LocalDate.of(2025, 12, 1),
                                        null,
                                        "회원가입",
                                        null))
                        .getId();
        Long promotion =
                memberGradeHistoryRepository
                        .save(
                                MemberGradeHistoryEntity.create(
                                        target,
                                        temp,
                                        active,
                                        LocalDate.of(2026, 8, 10),
                                        "정회원 승급",
                                        manager))
                        .getId();
        Long leave =
                memberStatusHistoryRepository
                        .save(
                                MemberStatusHistoryEntity.create(
                                        target,
                                        enrolled,
                                        onLeave,
                                        LocalDate.of(2026, 8, 15),
                                        LocalDate.of(2027, 3, 1),
                                        "휴학",
                                        manager))
                        .getId();

        setCreatedAt("mbr_grd_hstry", "mbr_grd_hstry_id", signupGrade, at(2025, 12, 1));
        setCreatedAt(
                "mbr_stts_hstry",
                "mbr_stts_hstry_id",
                signupStatus,
                at(2025, 12, 1).plusSeconds(3600));
        setCreatedAt("mbr_grd_hstry", "mbr_grd_hstry_id", promotion, at(2026, 8, 10));
        setCreatedAt("mbr_stts_hstry", "mbr_stts_hstry_id", leave, at(2026, 8, 15));

        // 끝난 임기 하나(부여 + 종료 두 사건)와 진행 중인 임기 하나(부여 한 사건)
        MemberRoleAssignmentEntity ended =
                MemberRoleAssignmentEntity.create(
                        target, role(11, "홍보국장"), LocalDate.of(2026, 1, 1), false);
        ended.end(LocalDate.of(2026, 6, 30));
        memberRoleAssignmentRepository.save(ended);
        memberRoleAssignmentRepository.save(
                MemberRoleAssignmentEntity.create(
                        target, role(12, "프로젝트장"), LocalDate.of(2026, 7, 1), true));

        flushAndClear();
    }

    /* ── 타임라인 ────────────────────────────────────────── */

    /*
     * 세 출처가 한 목록에 시간 역순으로 섞인다. 역할이 등급·상태 사이에 끼어 있는 것이
     * 이 API의 전부다 — 화면이 세 배열을 받아 스스로 합치면 이 순서가 서버와 갈린다.
     *
     * 역할 배정 두 건이 세 줄이 되는 것도 함께 드러난다. 끝난 임기는 부여와 종료라는 서로
     * 다른 시각의 사건 둘을 담고, 진행 중인 임기는 부여 하나뿐이다.
     */
    @Test
    void mergesGradeStatusAndRoleIntoOneTimeline() throws Exception {
        mockMvc.perform(histories(MANAGER, targetMemberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[0].changeType").value("STATUS"))
                .andExpect(jsonPath("$.data[0].newCode").value("LEAVE"))
                .andExpect(jsonPath("$.data[0].newName").value("일반휴학"))
                .andExpect(jsonPath("$.data[0].appliedDate").value("2026-08-15"))
                .andExpect(jsonPath("$.data[1].changeType").value("GRADE"))
                .andExpect(jsonPath("$.data[1].previousCode").value("TEMP"))
                .andExpect(jsonPath("$.data[1].newCode").value("ACTIVE"))
                .andExpect(jsonPath("$.data[2].changeType").value("ROLE_ASSIGNED"))
                .andExpect(jsonPath("$.data[2].newName").value("프로젝트장"))
                .andExpect(jsonPath("$.data[2].appliedDate").value("2026-07-01"))
                .andExpect(jsonPath("$.data[3].changeType").value("ROLE_ENDED"))
                .andExpect(jsonPath("$.data[3].previousName").value("홍보국장"))
                .andExpect(jsonPath("$.data[3].newName").doesNotExist())
                .andExpect(jsonPath("$.data[3].appliedDate").value("2026-06-30"))
                .andExpect(jsonPath("$.data[4].changeType").value("ROLE_ASSIGNED"))
                .andExpect(jsonPath("$.data[4].previousName").doesNotExist())
                .andExpect(jsonPath("$.data[4].newName").value("홍보국장"))
                .andExpect(jsonPath("$.data[4].appliedDate").value("2026-01-01"))
                .andExpect(jsonPath("$.data[5].changeType").value("STATUS"))
                .andExpect(jsonPath("$.data[5].changeReason").value("회원가입"))
                // 최초 부여는 이전 값이 비어 있다 — 그때는 등급도 상태도 없었다
                .andExpect(jsonPath("$.data[6].changeType").value("GRADE"))
                .andExpect(jsonPath("$.data[6].previousCode").doesNotExist())
                .andExpect(jsonPath("$.data[6].newCode").value("TEMP"));
    }

    /*
     * 변경자는 이름까지 실려 나온다. 그래야 화면이 이름을 얻으려 회원을 한 명씩 더 조회하지
     * 않는다. **역할 항목만은 null이다** — mbr_role_rel에 변경자 컬럼이 없어 "누가 부여했는가"를
     * 답할 근거가 데이터에 없고, 요청자나 본인을 대신 채우면 이력이 사실이 아닌 것을 말한다.
     */
    @Test
    void roleItemsCarryNoChangedBy() throws Exception {
        mockMvc.perform(histories(MANAGER, targetMemberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].changedByName").value("김도현"))
                .andExpect(jsonPath("$.data[1].changedByName").value("김도현"))
                .andExpect(jsonPath("$.data[2].changedByMemberId").doesNotExist())
                .andExpect(jsonPath("$.data[2].changedByName").doesNotExist())
                .andExpect(jsonPath("$.data[3].changedByMemberId").doesNotExist())
                .andExpect(jsonPath("$.data[3].changedByName").doesNotExist())
                .andExpect(jsonPath("$.data[4].changedByMemberId").doesNotExist())
                .andExpect(jsonPath("$.data[4].changedByName").doesNotExist())
                // 이관으로 들어온 이력에는 사람이 없다 — 그래도 줄은 내려간다
                .andExpect(jsonPath("$.data[5].changedByName").doesNotExist())
                .andExpect(jsonPath("$.data[6].changedByName").value("박준호"));
    }

    /*
     * 변경자 회원 자체가 사라진 이력은 MemberHistoryOrphanChangerTest가 맡는다 — 참조가
     * 끊긴 행을 만들려면 H2의 참조 무결성 검사를 내려야 하는데 그 SET 문이 암묵 커밋을
     * 일으켜 이 클래스의 @Transactional 안에서는 쓸 수 없다.
     */

    /* ── type 필터 ───────────────────────────────────────── */

    @Test
    void typeFilterSelectsOneSource() throws Exception {
        mockMvc.perform(histories(MANAGER, targetMemberId).param("type", "GRADE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].changeType").value("GRADE"))
                .andExpect(jsonPath("$.data[1].changeType").value("GRADE"));

        mockMvc.perform(histories(MANAGER, targetMemberId).param("type", "STATUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].changeType").value("STATUS"));
    }

    /*
     * ROLE 하나를 고르면 부여와 종료가 **함께** 나온다. 필터 어휘(ROLE)와 표시 어휘
     * (ROLE_ASSIGNED·ROLE_ENDED)를 나눠 둔 이유가 여기 있다 — 한 어휘로 합치면 임기 시작만
     * 보이고 종료가 사라지는 목록을 만들 수 있게 된다.
     */
    @Test
    void roleFilterKeepsBothAssignedAndEnded() throws Exception {
        mockMvc.perform(histories(MANAGER, targetMemberId).param("type", "ROLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].changeType").value("ROLE_ASSIGNED"))
                .andExpect(jsonPath("$.data[1].changeType").value("ROLE_ENDED"))
                .andExpect(jsonPath("$.data[2].changeType").value("ROLE_ASSIGNED"));
    }

    // 복수 지정. 고른 출처끼리도 시간 역순으로 섞인다
    @Test
    void typeFilterAcceptsMultipleValues() throws Exception {
        mockMvc.perform(
                        histories(MANAGER, targetMemberId)
                                .param("type", "GRADE")
                                .param("type", "ROLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].changeType").value("GRADE"))
                .andExpect(jsonPath("$.data[1].changeType").value("ROLE_ASSIGNED"))
                .andExpect(jsonPath("$.data[2].changeType").value("ROLE_ENDED"))
                .andExpect(jsonPath("$.data[3].changeType").value("ROLE_ASSIGNED"))
                .andExpect(jsonPath("$.data[4].changeType").value("GRADE"));
    }

    @Test
    void unknownTypeIs400() throws Exception {
        mockMvc.perform(histories(MANAGER, targetMemberId).param("type", "PHONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* ── 표시 명칭 ───────────────────────────────────────── */

    /*
     * 등급·상태의 표시 명칭은 기준 코드 테이블(mbr_grd·mbr_stts)에서 온다. 자바 코드에
     * '정회원'을 적어 두었다면 기준정보에서 이름을 바꾼 다음 날부터 이력 화면만 옛 이름을
     * 그리게 된다 — CSV 이관의 역매핑(#84)이 같은 이유로 명칭을 코드에 박지 않는다.
     */
    @Test
    void displayNamesFollowTheCodeTable() throws Exception {
        MemberGradeEntity active = grade(MemberGradeCode.ACTIVE);
        active.update("정회원(개칭)", active.getDisplayOrder());
        MemberStatusEntity onLeave = memberStatus(MemberStatusCode.LEAVE);
        onLeave.update("휴학(개칭)", onLeave.getDisplayOrder());
        flushAndClear();

        mockMvc.perform(histories(MANAGER, targetMemberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].newName").value("휴학(개칭)"))
                .andExpect(jsonPath("$.data[1].newName").value("정회원(개칭)"));
    }

    // 역할명도 마찬가지다 — 이력에 이름을 복사해 두지 않고 role 테이블을 따라간다
    @Test
    void roleNamesFollowTheRoleTable() throws Exception {
        memberRoleRepository.findAll().stream()
                .filter(role -> "홍보국장".equals(role.getName()))
                .forEach(
                        role ->
                                role.update(
                                        role.getDisplayOrder(),
                                        "홍보부장",
                                        role.getRoleClassification()));
        flushAndClear();

        mockMvc.perform(histories(MANAGER, targetMemberId).param("type", "ROLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].previousName").value("홍보부장"))
                .andExpect(jsonPath("$.data[2].newName").value("홍보부장"));
    }

    /* ── 빈 목록 · 인가 · 없는 회원 ──────────────────────── */

    /*
     * 이력이 하나도 없는 회원은 **빈 배열**이지 404가 아니다. #78 이전에 가입한 회원이
     * 실제로 이 상태이며, 404로 내리면 화면이 "회원이 없다"로 읽는다.
     */
    @Test
    void memberWithoutAnyHistoryGetsEmptyArray() throws Exception {
        mockMvc.perform(histories(MANAGER, emptyMemberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // MEMBER_MANAGE가 없으면 403이다. 404로 감추지 않는다 (VR-M10)
    @Test
    void withoutMemberManageIs403() throws Exception {
        mockMvc.perform(histories(PLAIN_MEMBER, targetMemberId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mockMvc.perform(get(MEMBERS + "/" + targetMemberId + "/histories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownMemberIs404() throws Exception {
        mockMvc.perform(histories(MANAGER, targetMemberId + 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private static MockHttpServletRequestBuilder histories(UUID subject, Long memberId) {
        return get(MEMBERS + "/" + memberId + "/histories")
                .header("Authorization", "Bearer " + subject);
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

    private MemberGradeEntity grade(MemberGradeCode code) {
        return memberGradeRepository.findById(code.code()).orElseThrow();
    }

    private MemberStatusEntity memberStatus(MemberStatusCode code) {
        return memberStatusRepository.findById(code.code()).orElseThrow();
    }

    // 권한이 붙지 않은 역할. 여기서 보고 싶은 것은 인가가 아니라 임기의 시작과 끝이다
    private MemberRoleEntity role(int displayOrder, String name) {
        MemberRoleClassificationEntity position =
                memberRoleClassificationRepository.findById("POSITION").orElseThrow();
        return memberRoleRepository.save(MemberRoleEntity.create(displayOrder, name, position));
    }

    private static Instant at(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(KST).toInstant();
    }

    private void setCreatedAt(String table, String idColumn, Long id, Instant createdAt) {
        entityManager.flush();
        entityManager
                .createNativeQuery(
                        "update " + table + " set crt_dt = ?1 where " + idColumn + " = ?2")
                .setParameter(1, Timestamp.from(createdAt))
                .setParameter(2, id)
                .executeUpdate();
        entityManager.clear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class HistoryTestConfig {

        /*
         * 토큰 문자열을 그대로 sub로 쓴다 — 한 클래스 안에서 권한 있는 회원과 권한 없는 회원을
         * 번갈아 흉내 내야 하기 때문이다 (MemberChangeControllerTest와 같은 방식).
         */
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", token + "@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }

        /* ClockConfig의 clock 빈과 이름이 겹치지 않게 다른 이름으로 둔다 */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        }
    }
}
