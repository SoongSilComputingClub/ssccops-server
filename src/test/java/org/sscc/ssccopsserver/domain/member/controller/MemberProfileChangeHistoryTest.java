package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberChangeField;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberChangeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberChangeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 회원 정보 변경 이력 (mbr_chg_hstry · #226).
 *
 * 학번 잠금을 푸는 대가가 이 표이므로, 여기서 못 박는 것은 **이력이 실제로 증거가 되는가**다:
 *  1. 바뀐 항목만, 바뀐 개수만큼 남는다 (같은 값 재저장은 한 줄도 남기지 않고 성공한다).
 *  2. 변경자는 요청 본문이 아니라 인증 주체다 — 운영진 수정은 그 운영진, 본인 수정은 본인.
 *  3. 두 수정 경로가 **같은 표**에 쌓인다. 경로로 이력을 가르면 "이 회원 정보가 언제 어떻게
 *     바뀌었는가"에 답할 수 없다.
 *  4. 통합 이력(GET .../histories)에 등급·상태와 한 타임라인으로 섞여 나온다.
 *
 * 무엇이 바뀌는가(학번 409·400·NULL 저장 등)는 MemberUpdateControllerTest가 맡는다.
 *
 * **거절되는 요청을 넣지 않는다.** 서비스 트랜잭션 안에서 예외가 나면 이 테스트의 트랜잭션이
 * rollback-only가 되어 뒤따르는 요청이 UnexpectedRollbackException을 만난다
 * (MemberUpdateControllerTest와 같은 이유) — 이 클래스는 성공한 저장이 남기는 것을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberProfileChangeHistoryTest.StubJwtDecoderConfig.class)
@Transactional
class MemberProfileChangeHistoryTest {

    private static final String MEMBERS = "/v1/members";
    private static final String ME = "/v1/members/me";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /*
     * 이 클래스의 회원이 공유하는 이메일. 학번에서 만들지 않고 상수로 두는 것은 운영진 수정
     * 요청이 전체 교체라 매번 이메일도 실어 보내야 하기 때문이다 — 회원마다 다른 값이면
     * "바꾸지 않았다"를 표현하려고 요청 본문이 회원별로 갈린다.
     */
    private static final String KEEP_EMAIL = "keep@sscc.org";

    /** MEMBER_MANAGE를 가진 주체 */
    private static final UUID MANAGER = UUID.randomUUID();

    /** 가입은 했으나 아무 권한도 없는 주체 */
    private static final UUID PLAIN_MEMBER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberChangeHistoryRepository memberChangeHistoryRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @PersistenceContext private EntityManager entityManager;

    private MemberEntity manager;
    private MemberEntity plainMember;
    private MemberEntity target;

    @BeforeEach
    void setUp() {
        manager = saveMember(MANAGER, "20200001", "김도현", MemberStatusCode.ENROLLED);
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.MEMBER_MANAGE);

        plainMember = saveMember(PLAIN_MEMBER, "20200002", "이서연", MemberStatusCode.ENROLLED);
        target = saveMember(UUID.randomUUID(), "20200003", "박준호", MemberStatusCode.ENROLLED);
    }

    /* ── 무엇이 남는가 ───────────────────────────────────── */

    /*
     * 학번 하나를 고치면 이력도 한 줄이다. 이 한 줄이 없으면 학번 잠금을 풀 수 없었다 —
     * "누가 언제 무엇을 무엇으로" 없이 바꿀 수 있게 하면 잠금이 지키던 것이 그냥 사라진다.
     */
    @Test
    void fixingStudentNumberLeavesOneRow() throws Exception {
        mockMvc.perform(
                        authorized(
                                patchJson(
                                        MEMBERS + "/" + target.getId(),
                                        managerBody("20200099", "박준호")),
                                MANAGER))
                .andExpect(status().isOk());

        assertThat(historiesOf(target))
                .extracting(
                        MemberChangeHistoryEntity::getChangeField,
                        MemberChangeHistoryEntity::getPreviousContent,
                        MemberChangeHistoryEntity::getNewContent)
                .containsExactly(tuple(MemberChangeField.STUDENT_NUMBER, "20200003", "20200099"));

        assertThat(historiesOf(target).get(0).getChangedBy().getId()).isEqualTo(manager.getId());
    }

    /*
     * 한 번에 여러 항목을 고치면 **바뀐 개수만큼** 행이 남는다. 행 순서는 MemberChangeField의
     * 선언 순서(= mbr의 컬럼 순서)이며, 그래야 한 저장이 남긴 줄들이 늘 같은 차례로 쌓인다.
     *
     * 값이 같은 항목(학과)은 요청에 실려 있어도 행을 만들지 않는다 — 전체 교체 API라 매번
     * 모든 필드가 실려 오므로, 같은 값에도 행을 만들면 이름 하나 고친 저장이 아홉 줄을 낳는다.
     */
    @Test
    void onlyChangedItemsProduceRows() throws Exception {
        String body =
                """
                {
                  "studentNumber": "20200077",
                  "generationNumber": 31,
                  "clubJoinYear": 2019,
                  "name": "박준호(수정)",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 4,
                  "phoneNumber": "010-9999-8888",
                  "email": "fixed@sscc.org"
                }
                """;

        // 학과·학년은 미리 같은 값으로 맞춰 둔다 — 그래야 '바뀌지 않은 항목'이 실제로 생긴다
        mockMvc.perform(
                        authorized(
                                patchJson(
                                        MEMBERS + "/" + target.getId(),
                                        managerBody("20200003", "박준호")),
                                MANAGER))
                .andExpect(status().isOk());
        memberChangeHistoryRepository.deleteAll();
        flushAndClear();

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isOk());

        assertThat(historiesOf(target))
                .extracting(
                        MemberChangeHistoryEntity::getChangeField,
                        MemberChangeHistoryEntity::getPreviousContent,
                        MemberChangeHistoryEntity::getNewContent)
                .containsExactly(
                        tuple(MemberChangeField.STUDENT_NUMBER, "20200003", "20200077"),
                        tuple(MemberChangeField.GENERATION_NUMBER, "0", "31"),
                        tuple(MemberChangeField.CLUB_JOIN_YEAR, null, "2019"),
                        tuple(MemberChangeField.MEMBER_NAME, "박준호", "박준호(수정)"),
                        tuple(MemberChangeField.ACADEMIC_YEAR, "2", "4"),
                        tuple(MemberChangeField.PHONE_NUMBER, null, "010-9999-8888"),
                        tuple(MemberChangeField.EMAIL, KEEP_EMAIL, "fixed@sscc.org"));
    }

    /*
     * 같은 값으로 다시 저장하면 이력이 남지 않고 **요청도 성공한다.** 등급·상태는 같은 값이면
     * 400 NO_CHANGE지만(#78) 그쪽은 사유가 필수인 '사건'이고 이쪽은 여러 필드를 한 번에 보내는
     * 폼 저장이라, 거절하면 이름만 고치는 저장이 통째로 막힌다.
     */
    @Test
    void resavingTheSameValuesRecordsNothingAndSucceeds() throws Exception {
        MockHttpServletRequestBuilder request =
                authorized(
                        patchJson(MEMBERS + "/" + target.getId(), managerBody("20200003", "박준호")),
                        MANAGER);
        mockMvc.perform(request).andExpect(status().isOk());
        memberChangeHistoryRepository.deleteAll();
        flushAndClear();

        mockMvc.perform(
                        authorized(
                                patchJson(
                                        MEMBERS + "/" + target.getId(),
                                        managerBody("20200003", "박준호")),
                                MANAGER))
                .andExpect(status().isOk());

        assertThat(historiesOf(target)).isEmpty();
    }

    /*
     * 지운 값은 빈 문자열이 아니라 NULL로 남는다. "지웠다"와 "빈 문자열로 바꿨다"가 같은 행으로
     * 보이면, 학번을 NULL로 저장한다는 규칙을 이력이 뒤집어 말하게 된다.
     */
    @Test
    void clearedValueIsRecordedAsNull() throws Exception {
        MemberEntity graduated =
                saveMember(UUID.randomUUID(), "20150003", "졸업생", MemberStatusCode.GRADUATED);

        mockMvc.perform(
                        authorized(
                                patchJson(
                                        MEMBERS + "/" + graduated.getId(),
                                        """
                                        {"name": "졸업생"}
                                        """),
                                MANAGER))
                .andExpect(status().isOk());

        assertThat(historiesOf(graduated))
                .extracting(
                        MemberChangeHistoryEntity::getChangeField,
                        MemberChangeHistoryEntity::getPreviousContent,
                        MemberChangeHistoryEntity::getNewContent)
                .contains(tuple(MemberChangeField.STUDENT_NUMBER, "20150003", null));
    }

    /* ── 누가 바꿨는가 ───────────────────────────────────── */

    /*
     * 본인 수정도 같은 표에 쌓이고, 그때 변경자는 본인이다 — "회원 정보가 언제 어떻게
     * 바뀌었는가"를 답하는 표라 경로로 가르지 않는다(ssccops#162 설계 노트).
     *
     * 학번 행이 없다는 것도 함께 드러난다. 본인 경로의 요청 DTO에 필드가 없으므로 값이 같고,
     * 값이 같으면 행이 만들어지지 않는다.
     */
    @Test
    void selfEditIsRecordedWithSelfAsChanger() throws Exception {
        String body =
                """
                {
                  "studentNumber": "29999999",
                  "name": "이서연(수정)",
                  "departmentName": "글로벌미디어학부",
                  "academicYear": 3,
                  "phoneNumber": "010-1111-2222"
                }
                """;

        mockMvc.perform(authorized(patchJson(ME, body), PLAIN_MEMBER)).andExpect(status().isOk());

        List<MemberChangeHistoryEntity> histories = historiesOf(plainMember);
        assertThat(histories)
                .extracting(MemberChangeHistoryEntity::getChangeField)
                .containsExactly(
                        MemberChangeField.MEMBER_NAME,
                        MemberChangeField.DEPARTMENT_NAME,
                        MemberChangeField.ACADEMIC_YEAR,
                        MemberChangeField.PHONE_NUMBER);
        assertThat(histories)
                .allSatisfy(
                        history ->
                                assertThat(history.getChangedBy().getId())
                                        .isEqualTo(plainMember.getId()));
    }

    /* ── 통합 이력에 합류한다 ────────────────────────────── */

    /*
     * 프로필 변경이 등급 이력과 한 타임라인에 시간 역순으로 섞인다 (#82의 목록에 합류).
     *
     * 값이 실리는 자리가 등급·상태와 갈리는 것이 이 응답의 계약이다 — 프로필 값에는 코드/명
     * 구분이 없어 previousName·newName만 채우고, 어느 항목인지는 changeField(코드)와
     * changeFieldName(표시명)이 답한다. appliedDate·changeReason은 그 컬럼을 두지 않아 null이다.
     *
     * 등급·상태·역할 줄에서는 반대로 changeField가 null이다 — 그쪽은 changeType이 곧 항목이다.
     */
    @Test
    void unifiedHistoryInterleavesProfileChanges() throws Exception {
        Long signupGrade =
                memberGradeHistoryRepository
                        .save(
                                MemberGradeHistoryEntity.create(
                                        target,
                                        null,
                                        memberGradeRepository
                                                .findById(MemberGradeCode.TEMP.code())
                                                .orElseThrow(),
                                        LocalDate.of(2025, 12, 1),
                                        "회원가입",
                                        target))
                        .getId();
        setCreatedAt(signupGrade, at(2025, 12, 1));

        mockMvc.perform(
                        authorized(
                                patchJson(
                                        MEMBERS + "/" + target.getId(),
                                        managerBody("20200099", "박준호(수정)")),
                                MANAGER))
                .andExpect(status().isOk());

        mockMvc.perform(histories(MANAGER, target.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                // 방금 남은 프로필 두 줄이 가장 위다 (기록 시각 역순)
                .andExpect(jsonPath("$.data[0].changeType").value("PROFILE"))
                .andExpect(jsonPath("$.data[0].changeField").value("MEMBER_NAME"))
                .andExpect(jsonPath("$.data[0].changeFieldName").value("회원명"))
                .andExpect(jsonPath("$.data[0].previousName").value("박준호"))
                .andExpect(jsonPath("$.data[0].newName").value("박준호(수정)"))
                .andExpect(jsonPath("$.data[0].previousCode").doesNotExist())
                .andExpect(jsonPath("$.data[0].newCode").doesNotExist())
                .andExpect(jsonPath("$.data[0].appliedDate").doesNotExist())
                .andExpect(jsonPath("$.data[0].changeReason").doesNotExist())
                .andExpect(jsonPath("$.data[0].changedByName").value("김도현"))
                .andExpect(jsonPath("$.data[1].changeType").value("PROFILE"))
                .andExpect(jsonPath("$.data[1].changeField").value("STUDENT_NUMBER"))
                .andExpect(jsonPath("$.data[1].changeFieldName").value("학번"))
                .andExpect(jsonPath("$.data[1].previousName").value("20200003"))
                .andExpect(jsonPath("$.data[1].newName").value("20200099"))
                // 등급 줄에는 항목 칸이 없다 — changeType이 곧 항목이기 때문이다
                .andExpect(jsonPath("$.data[2].changeType").value("GRADE"))
                .andExpect(jsonPath("$.data[2].changeField").doesNotExist())
                .andExpect(jsonPath("$.data[2].changeFieldName").doesNotExist())
                .andExpect(jsonPath("$.data[2].newCode").value("TEMP"));
    }

    // type 필터가 프로필만 고른다. 필터 어휘(PROFILE)는 표시 종류와 값이 같다
    @Test
    void typeFilterSelectsProfileOnly() throws Exception {
        memberGradeHistoryRepository.save(
                MemberGradeHistoryEntity.create(
                        target,
                        null,
                        memberGradeRepository.findById(MemberGradeCode.TEMP.code()).orElseThrow(),
                        LocalDate.of(2025, 12, 1),
                        "회원가입",
                        target));

        mockMvc.perform(
                        authorized(
                                patchJson(
                                        MEMBERS + "/" + target.getId(),
                                        managerBody("20200099", "박준호")),
                                MANAGER))
                .andExpect(status().isOk());

        mockMvc.perform(histories(MANAGER, target.getId()).param("type", "PROFILE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].changeType").value("PROFILE"))
                .andExpect(jsonPath("$.data[0].changeField").value("STUDENT_NUMBER"));

        mockMvc.perform(histories(MANAGER, target.getId()).param("type", "GRADE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].changeType").value("GRADE"));
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    /*
     * 운영진 수정 요청 한 벌. 전체 교체 API라 바꾸지 않는 값도 함께 실어야 하며, 학과·학년을
     * 늘 채워 보내는 것은 대상이 재학 회원이라 비우면 400이기 때문이다.
     */
    private static String managerBody(String studentNumber, String name) {
        return """
                {
                  "studentNumber": "%s",
                  "name": "%s",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 2,
                  "email": "keep@sscc.org"
                }
                """
                .formatted(studentNumber, name);
    }

    private List<MemberChangeHistoryEntity> historiesOf(MemberEntity member) {
        flushAndClear();
        return memberChangeHistoryRepository.findAll().stream()
                .filter(history -> history.getMember().getId().equals(member.getId()))
                .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                .toList();
    }

    private static MockHttpServletRequestBuilder histories(UUID subject, Long memberId) {
        return get(MEMBERS + "/" + memberId + "/histories")
                .header("Authorization", "Bearer " + subject);
    }

    private static MockHttpServletRequestBuilder patchJson(String path, String body) {
        return patch(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId);
    }

    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, MemberStatusCode statusCode) {
        MemberEntity member =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        authUserId,
                        studentNumber,
                        name,
                        KEEP_EMAIL,
                        statusCode);
        member.updateBasicInfo(0, null, null, name, "컴퓨터학부", 2, null, KEEP_EMAIL);
        return memberRepository.saveAndFlush(member);
    }

    private static Instant at(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(KST).toInstant();
    }

    // 감사 필드라 값을 손으로 정할 수 없어 저장 뒤에 갈아 둔다 (MemberHistoryControllerTest와 같다)
    private void setCreatedAt(Long id, Instant createdAt) {
        entityManager.flush();
        entityManager
                .createNativeQuery(
                        "update mbr_grd_hstry set crt_dt = ?1 where mbr_grd_hstry_id = ?2")
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
    static class StubJwtDecoderConfig {

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
    }
}
