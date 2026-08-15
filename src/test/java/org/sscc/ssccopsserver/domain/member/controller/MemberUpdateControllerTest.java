package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

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
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
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

/*
 * 회원 정보 수정 API (#77) — 운영진 경로(PATCH /v1/members/{mbrId})와 본인 경로(PATCH
 * /v1/members/me).
 *
 * 이 클래스가 못 박는 것은 세 가지다:
 *  1. 두 경로가 고칠 수 있는 필드가 다르다 (본인은 기수·이메일을 못 바꾼다).
 *  2. **어느 경로로도 등급·상태·학번은 바뀌지 않는다** — 요청 본문에 넣어도 필드가 없어 무시된다.
 *  3. 인가 계단이 경로마다 다르다 (타인 수정은 MEMBER_MANAGE, 본인 수정은 가입만).
 *
 * MemberQueryControllerTest와 같은 방식으로 스텁 JwtDecoder가 토큰 문자열을 그대로 sub로 쓴다 —
 * 한 클래스에서 권한 있는 회원·권한 없는 회원·미가입 주체를 번갈아 흉내 내야 한다.
 *
 * **실패하는 요청은 테스트 하나에 하나뿐이다.** 서비스 트랜잭션 안에서 예외가 나면 이 테스트의
 * 트랜잭션이 rollback-only가 되어 뒤이은 요청이 UnexpectedRollbackException을 만난다
 * (RoleAuthoritySelfLockTest와 같은 이유). 애스펙트가 핸들러 호출 전에 끊는 403은 서비스
 * 트랜잭션을 열지 않으므로 예외다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberUpdateControllerTest.StubJwtDecoderConfig.class)
@Transactional
class MemberUpdateControllerTest {

    private static final String MEMBERS = "/v1/members";
    private static final String ME = "/v1/members/me";

    /** MEMBER_MANAGE를 가진 주체 */
    private static final UUID MANAGER = UUID.randomUUID();

    /** 가입은 했으나 아무 권한도 없는 주체 */
    private static final UUID PLAIN_MEMBER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private MemberEntity plainMember;
    private MemberEntity target;

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

        plainMember = saveMember(PLAIN_MEMBER, "20200002", "이서연");
        target = saveMember(UUID.randomUUID(), "20200003", "박준호");
    }

    /* ── 운영진 경로 ─────────────────────────────────────── */

    /*
     * 여섯 필드가 그대로 반영되고 mdfcn_dt가 갱신된다.
     *
     * 응답의 updatedAt이 수정 전 값이면 서비스가 flush를 미룬 것이다 — 트랜잭션이 끝나야
     * UPDATE가 나가면 auditing이 값을 채우기 전의 엔티티로 응답을 조립하게 된다.
     */
    @Test
    void managerUpdatesSixFields() throws Exception {
        Instant before = target.getUpdatedAt();

        mockMvc.perform(
                        authorized(
                                patchJson(MEMBERS + "/" + target.getId(), fullUpdateBody()),
                                MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(target.getId()))
                .andExpect(jsonPath("$.data.generationNumber").value(31))
                .andExpect(jsonPath("$.data.name").value("박준호(수정)"))
                .andExpect(jsonPath("$.data.departmentName").value("컴퓨터학부"))
                .andExpect(jsonPath("$.data.academicYear").value(4))
                .andExpect(jsonPath("$.data.phoneNumber").value("010-9999-8888"))
                .andExpect(jsonPath("$.data.email").value("fixed@sscc.org"))
                // 응답은 조회와 같은 상세 모양이라 화면이 저장 뒤 다시 조회하지 않아도 된다
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.recentChanges").isArray());

        assertThat(target.getUpdatedAt()).isAfter(before);
    }

    /*
     * 등급·상태·학번은 요청에 넣어도 바뀌지 않는다. 요청 DTO에 필드 자체가 없어 조용히
     * 버려지며, **바뀌지 않는다는 것이 이 API의 계약이다** — 등급·상태는 이력을 함께 남기는
     * 전용 API(#78)가, 학번은 updatable = false가 지킨다.
     */
    @Test
    void gradeStatusAndStudentNumberAreNotChangeable() throws Exception {
        String body =
                """
                {
                  "name": "박준호",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 2,
                  "membershipGradeCode": "FULL",
                  "membershipStatusCode": "GRADUATED",
                  "studentNumber": "20991234",
                  "joinDate": "2000-01-01"
                }
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipGradeCode").value("TEMP"))
                .andExpect(jsonPath("$.data.membershipStatusCode").value("ENROLLED"))
                .andExpect(jsonPath("$.data.studentNumber").value("20200003"));

        assertThat(target.getMembershipGrade().getCode()).isEqualTo("TEMP");
        assertThat(target.getMembershipStatus().getCode()).isEqualTo("ENROLLED");
        assertThat(target.getStudentNumber()).isEqualTo("20200003");
    }

    /*
     * 재학 회원의 학과·학년을 비우면 400이다. 가입에서 @AssertTrue가 막는 것과 같은 규칙을
     * 수정도 쓴다 — 규칙이 두 벌이었다면 가입에서 막힌 값이 여기서 통과한다.
     */
    @Test
    void clearingAcademicProfileOfEnrolledMemberIs400() throws Exception {
        String body =
                """
                {"name": "박준호", "phoneNumber": "010-0000-0000"}
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 졸업 회원은 학과·학년이 현재 사실이 아니므로 비울 수 있다 (가입과 같은 판단)
    @Test
    void graduatedMemberMayClearAcademicProfile() throws Exception {
        MemberEntity graduated =
                saveMember(UUID.randomUUID(), "20150001", "졸업생", MemberStatusCode.GRADUATED);
        String body =
                """
                {"name": "졸업생", "phoneNumber": "010-0000-0000"}
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + graduated.getId(), body), MANAGER))
                .andExpect(status().isOk())
                // 값이 비워졌다는 뜻이지 키가 사라진다는 뜻이 아니다 (응답 스키마는 그대로다)
                .andExpect(jsonPath("$.data.departmentName").isEmpty())
                .andExpect(jsonPath("$.data.academicYear").isEmpty());
    }

    @Test
    void unknownMemberIs404() throws Exception {
        mockMvc.perform(
                        authorized(
                                patchJson(
                                        MEMBERS + "/" + (target.getId() + 9999), fullUpdateBody()),
                                MANAGER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 이름은 mbr_nm이 NOT NULL이라 비울 수 없다. 길이 상한은 데이터사전 그대로 50이다
    @Test
    void blankNameIs400() throws Exception {
        String body =
                """
                {"name": "  ", "departmentName": "컴퓨터학부", "academicYear": 2}
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* ── 본인 경로 ───────────────────────────────────────── */

    /*
     * 본인 수정은 **자기 행만** 바꾼다. 다른 회원의 식별자를 넣을 자리가 경로에도 본문에도
     * 없다는 것이 그 보장이며, 본문에 memberId를 실어 보내도 무시된다.
     *
     * 응답이 세션 조회와 같은 MemberProfileResponse라 웹이 저장 뒤 세션을 다시 조회하지 않는다.
     */
    @Test
    void selfUpdateChangesOnlyOwnRow() throws Exception {
        String body =
                """
                {
                  "memberId": %d,
                  "name": "이서연(수정)",
                  "departmentName": "글로벌미디어학부",
                  "academicYear": 2,
                  "phoneNumber": "010-1111-2222"
                }
                """
                        .formatted(target.getId());

        mockMvc.perform(authorized(patchJson(ME, body), PLAIN_MEMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(plainMember.getId()))
                .andExpect(jsonPath("$.data.name").value("이서연(수정)"))
                .andExpect(jsonPath("$.data.departmentName").value("글로벌미디어학부"))
                .andExpect(jsonPath("$.data.academicYear").value(2))
                .andExpect(jsonPath("$.data.phoneNumber").value("010-1111-2222"))
                // 본인 응답에는 capabilities가 있다 (목록·상세와 갈리는 지점)
                .andExpect(jsonPath("$.data.capabilities").isArray());

        assertThat(target.getName()).isEqualTo("박준호");
        assertThat(target.getDepartmentName()).isNull();
        assertThat(target.getPhoneNumber()).isNull();
    }

    /*
     * 기수와 이메일은 본인 경로의 DTO에 없다. 기수는 운영진이 배정하는 값이고, 이메일은
     * 인증 계정에서 오므로 본인이 바꾸면 로그인 계정과 갈린다.
     */
    @Test
    void selfUpdateCannotChangeGenerationOrEmail() throws Exception {
        String body =
                """
                {
                  "name": "이서연",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3,
                  "generationNumber": 99,
                  "email": "hijack@evil.com"
                }
                """;

        mockMvc.perform(authorized(patchJson(ME, body), PLAIN_MEMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationNumber").value(0))
                .andExpect(jsonPath("$.data.email").value("20200002@sscc.org"));

        assertThat(plainMember.getGenerationNumber()).isZero();
        assertThat(plainMember.getEmail()).isEqualTo("20200002@sscc.org");
    }

    @Test
    void selfClearingAcademicProfileOfEnrolledMemberIs400() throws Exception {
        String body =
                """
                {"name": "이서연", "phoneNumber": "010-1111-2222"}
                """;

        mockMvc.perform(authorized(patchJson(ME, body), PLAIN_MEMBER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* ── 인가 계단 ───────────────────────────────────────── */

    /*
     * 권한 없는 회원은 남의 정보를 고칠 수 없지만 자기 정보는 고칠 수 있다. 이 한 쌍이
     * 두 경로를 나눈 이유 그 자체다 — 자기 연락처를 고치는 데 회원 관리 권한을 요구하면
     * 대부분의 회원은 자기 정보를 영영 고칠 수 없다.
     *
     * 앞의 403은 애스펙트가 핸들러 호출 전에 끊으므로 서비스 트랜잭션이 열리지 않는다.
     * 그래서 뒤의 요청을 같은 테스트에서 이어 보낼 수 있다.
     */
    @Test
    void otherMemberUpdateRequiresMemberManageButSelfUpdateDoesNot() throws Exception {
        mockMvc.perform(
                        authorized(
                                patchJson(MEMBERS + "/" + target.getId(), fullUpdateBody()),
                                PLAIN_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String selfBody =
                """
                {"name": "이서연", "departmentName": "컴퓨터학부", "academicYear": 3}
                """;
        mockMvc.perform(authorized(patchJson(ME, selfBody), PLAIN_MEMBER))
                .andExpect(status().isOk());

        // 403으로 끊긴 요청은 남의 행에 아무 자국도 남기지 않는다
        assertThat(target.getName()).isEqualTo("박준호");
    }

    /*
     * 인증은 됐지만 mbr 행이 없는 주체. 권한 부족과 상태 코드는 같지만 코드 문자열이 달라야
     * 프론트가 한쪽은 가입 화면으로, 다른 쪽은 "권한 없음"으로 안내한다.
     */
    @Test
    void notSignedUpSubjectGets403SignupRequired() throws Exception {
        String body =
                """
                {"name": "누구", "departmentName": "컴퓨터학부", "academicYear": 1}
                """;

        mockMvc.perform(authorized(patchJson(ME, body), UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    @Test
    void requestWithoutTokenIs401() throws Exception {
        mockMvc.perform(patchJson(ME, fullUpdateBody())).andExpect(status().isUnauthorized());
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private static String fullUpdateBody() {
        return """
                {
                  "generationNumber": 31,
                  "name": "박준호(수정)",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 4,
                  "phoneNumber": "010-9999-8888",
                  "email": "fixed@sscc.org"
                }
                """;
    }

    private static MockHttpServletRequestBuilder patchJson(String path, String body) {
        return patch(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId);
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return saveMember(authUserId, studentNumber, name, MemberStatusCode.ENROLLED);
    }

    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, MemberStatusCode statusCode) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org",
                statusCode);
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
