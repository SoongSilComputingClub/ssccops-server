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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 회원 정보 수정 API (#77) — 운영진 경로(PATCH /v1/members/{mbrId})와 본인 경로(PATCH
 * /v1/members/me).
 *
 * 이 클래스가 못 박는 것은 네 가지다:
 *  1. 두 경로가 고칠 수 있는 필드가 다르다 (본인은 기수·이메일·학번을 못 바꾼다).
 *  2. **어느 경로로도 등급·상태는 바뀌지 않는다** — 요청 본문에 넣어도 필드가 없어 무시된다.
 *  3. **학번은 운영진 경로에서만 바뀐다** (#226). 중복은 409, 재학 회원 비우기는 400,
 *     졸업 회원 비우기는 NULL 저장이다.
 *  4. 인가 계단이 경로마다 다르다 (타인 수정은 MEMBER_MANAGE, 본인 수정은 가입만).
 *
 * 이력이 실제로 남는지는 MemberProfileChangeHistoryTest가 따로 본다 — 이 클래스는 무엇이
 * 바뀌는가를, 그쪽은 무엇이 기록되는가를 맡는다.
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
@Import(TestJwtDecoderConfig.class)
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
     * 아홉 필드가 그대로 반영되고 mdfcn_dt가 갱신된다 (#226에서 학번이 아홉 번째로 늘었다).
     *
     * 응답의 updatedAt이 수정 전 값이면 서비스가 flush를 미룬 것이다 — 트랜잭션이 끝나야
     * UPDATE가 나가면 auditing이 값을 채우기 전의 엔티티로 응답을 조립하게 된다.
     */
    @Test
    void managerUpdatesNineFields() throws Exception {
        Instant before = target.getUpdatedAt();

        mockMvc.perform(
                        authorized(
                                patchJson(MEMBERS + "/" + target.getId(), fullUpdateBody()),
                                MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(target.getId()))
                .andExpect(jsonPath("$.data.generationNumber").value(31))
                // 동아리 가입 연·월은 운영진만 고칠 수 있다 (#204)
                .andExpect(jsonPath("$.data.clubJoinYear").value(2019))
                .andExpect(jsonPath("$.data.clubJoinMonth").value(3))
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
     * 등급·상태는 요청에 넣어도 바뀌지 않는다. 요청 DTO에 필드 자체가 없어 조용히 버려지며,
     * **바뀌지 않는다는 것이 이 API의 계약이다** — 이력을 함께 남기는 전용 API(#78)가 지킨다.
     *
     * 학번은 이 목록을 떠났다 (#226). 같은 요청에 실린 학번은 이제 실제로 반영된다.
     */
    @Test
    void gradeAndStatusAreNotChangeable() throws Exception {
        String body =
                """
                {
                  "name": "박준호",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 2,
                  "membershipGradeCode": "FULL",
                  "membershipStatusCode": "GRADUATED",
                  "studentNumber": "20991234",
                  "systemJoinDate": "2000-01-01"
                }
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipGradeCode").value("TEMP"))
                .andExpect(jsonPath("$.data.membershipStatusCode").value("ENROLLED"))
                .andExpect(jsonPath("$.data.studentNumber").value("20991234"));

        assertThat(target.getMembershipGrade().getCode()).isEqualTo("TEMP");
        assertThat(target.getMembershipStatus().getCode()).isEqualTo("ENROLLED");
        assertThat(target.getStudentNumber()).isEqualTo("20991234");
    }

    /* ── 학번 (#226) ─────────────────────────────────────── */

    /*
     * 오타로 들어온 학번을 고친다 — 이 이슈가 존재하는 이유 그 자체다. updatable = false가
     * 풀렸다는 것이 여기서 확인된다.
     */
    @Test
    void managerFixesStudentNumber() throws Exception {
        String body =
                """
                {
                  "studentNumber": "20200099",
                  "name": "박준호",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 2
                }
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentNumber").value("20200099"));

        assertThat(target.getStudentNumber()).isEqualTo("20200099");
    }

    /*
     * 다른 회원이 쓰는 학번은 409다. 선조회로 걸리는 경로이며, 동시 요청은 UNIQUE 위반으로만
     * 드러나 같은 코드로 옮겨진다(그 경로는 서비스의 flush 번역이 맡는다).
     */
    @Test
    void duplicatedStudentNumberIs409() throws Exception {
        String body =
                """
                {
                  "studentNumber": "20200002",
                  "name": "박준호",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 2
                }
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STUDENT_NUMBER_DUPLICATED"));
    }

    /*
     * 자기 학번을 그대로 다시 보내는 것은 중복이 아니다. 전체 교체 API라 바꾸지 않는 저장에도
     * 지금 값이 실려 오므로, 이것을 막으면 이름만 고치는 저장이 통째로 409가 된다.
     */
    @Test
    void resavingOwnStudentNumberIsNotDuplicate() throws Exception {
        mockMvc.perform(
                        authorized(
                                patchJson(MEMBERS + "/" + target.getId(), fullUpdateBody()),
                                MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentNumber").value("20200003"));
    }

    // 재학 회원은 학번이 필수다 — 비우면 400이며, 판정은 가입·이관과 같은 AcademicProfilePolicy다
    @Test
    void clearingStudentNumberOfEnrolledMemberIs400() throws Exception {
        String body =
                """
                {"name": "박준호", "departmentName": "컴퓨터학부", "academicYear": 2}
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /*
     * 졸업 회원의 학번은 비울 수 있고, 그때 저장되는 값은 빈 문자열이 아니라 **NULL**이다.
     * 빈 문자열이면 두 번째 졸업 회원부터 uk_mbr_student_number 충돌이 난다.
     */
    @Test
    void clearingStudentNumberOfGraduatedMemberStoresNull() throws Exception {
        MemberEntity graduated =
                saveMember(UUID.randomUUID(), "20150002", "졸업생", MemberStatusCode.GRADUATED);

        String body =
                """
                {"studentNumber": "   ", "name": "졸업생"}
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + graduated.getId(), body), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentNumber").isEmpty());

        assertThat(graduated.getStudentNumber()).isNull();
    }

    /*
     * 월을 모르면 연도만 채운다 — 그것이 이 컬럼을 DATE 하나가 아니라 연·월 둘로 나눈 이유다.
     * 모르는 일(日)을 1일로 지어내지 않듯 모르는 월도 비워 둔다.
     */
    @Test
    void managerMayFillYearWithoutMonth() throws Exception {
        String body =
                """
                {
                  "studentNumber": "20200003",
                  "name": "박준호",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 4,
                  "clubJoinYear": 2019
                }
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubJoinYear").value(2019))
                .andExpect(jsonPath("$.data.clubJoinMonth").isEmpty());

        assertThat(target.getClubJoinYear()).isEqualTo(2019);
        assertThat(target.getClubJoinMonth()).isNull();
    }

    /*
     * 동아리 가입 연·월은 NULL 허용이라 '비운다'가 성립한다 — 생략하면 null이 되며, 그것이 이
     * DTO의 전체 교체 규칙이다. gen_no가 null을 미배정(0)으로 받는 것과 갈리는 지점이다.
     */
    @Test
    void managerClearsClubJoinPeriod() throws Exception {
        mockMvc.perform(
                        authorized(
                                patchJson(MEMBERS + "/" + target.getId(), fullUpdateBody()),
                                MANAGER))
                .andExpect(status().isOk());

        String body =
                """
                {
                  "studentNumber": "20200003",
                  "name": "박준호",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 4
                }
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubJoinYear").isEmpty())
                .andExpect(jsonPath("$.data.clubJoinMonth").isEmpty())
                // 기수는 같은 요청에서도 0으로 돌아간다 — NOT NULL이라 지울 자리가 없다
                .andExpect(jsonPath("$.data.generationNumber").value(0));

        assertThat(target.getClubJoinYear()).isNull();
        assertThat(target.getClubJoinMonth()).isNull();
    }

    // 범위 밖의 월은 400이다. 오타를 컬럼까지 흘려보내지 않는다
    @Test
    void clubJoinMonthOutOfRangeIs400() throws Exception {
        String body =
                """
                {
                  "studentNumber": "20200003",
                  "name": "박준호",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 4,
                  "clubJoinYear": 2019,
                  "clubJoinMonth": 13
                }
                """;

        mockMvc.perform(authorized(patchJson(MEMBERS + "/" + target.getId(), body), MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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

    /*
     * 동아리 가입 연·월은 본인 경로의 DTO에 없다. 근거는 기수와 같다 — 연도가 기수의 근거라
     * 본인이 고칠 수 있으면 기수를 우회해서 정하는 셈이 된다. 응답(MemberProfileResponse)에도
     * 키가 없다: 본인이 다룰 값이 아니다.
     */
    @Test
    void selfUpdateCannotChangeClubJoinPeriod() throws Exception {
        // 운영진이 먼저 채워 둔다 — 본인 요청이 이 값을 덮어쓰지 못한다는 것이 확인할 점이다
        String managerBody =
                """
                {
                  "studentNumber": "20200002",
                  "name": "이서연",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3,
                  "clubJoinYear": 2019,
                  "clubJoinMonth": 3
                }
                """;
        mockMvc.perform(
                        authorized(
                                patchJson(MEMBERS + "/" + plainMember.getId(), managerBody),
                                MANAGER))
                .andExpect(status().isOk());

        String body =
                """
                {
                  "name": "이서연",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3,
                  "clubJoinYear": 1999,
                  "clubJoinMonth": 12
                }
                """;

        mockMvc.perform(authorized(patchJson(ME, body), PLAIN_MEMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clubJoinYear").doesNotExist())
                .andExpect(jsonPath("$.data.clubJoinMonth").doesNotExist());

        assertThat(plainMember.getClubJoinYear()).isEqualTo(2019);
        assertThat(plainMember.getClubJoinMonth()).isEqualTo(3);
    }

    /*
     * 본인 경로에는 학번 칸 자체가 없다 (#226). 본문에 실어 보내도 무시되며, 그것이 이 DTO를
     * 나눈 이유다 — 학번은 자기소개가 아니라 신원 식별자이고 계정 연결 판정의 재료다.
     */
    @Test
    void selfUpdateCannotChangeStudentNumber() throws Exception {
        String body =
                """
                {
                  "studentNumber": "29999999",
                  "name": "이서연",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3
                }
                """;

        mockMvc.perform(authorized(patchJson(ME, body), PLAIN_MEMBER)).andExpect(status().isOk());

        assertThat(plainMember.getStudentNumber()).isEqualTo("20200002");
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
                  "studentNumber": "20200003",
                  "generationNumber": 31,
                  "clubJoinYear": 2019,
                  "clubJoinMonth": 3,
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
}
