package org.sscc.ssccopsserver.domain.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hamcrest.Matchers;
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
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
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
 * 회원 조회 API의 인가 계단과 밖으로 나가는 필드를 확인한다 (#76).
 *
 * 목록·단건이 MEMBER_MANAGE를 요구하는 반면 담당자 후보·기준 코드는 인증만으로 통과해야
 * 한다는 것이 이 클래스의 요점이다 — @RequireAuthority를 클래스에 걸었다면 뒤의 셋이 함께
 * 막혀 여기서 드러난다.
 *
 * 검색·정렬·커서 같은 조회 규칙은 MemberQueryServiceTest가 다룬다. 여기서는 필터체인·
 * 애스펙트·예외 핸들러를 통째로 지나온 뒤의 상태 코드와 응답 모양만 본다.
 *
 * 스텁 JwtDecoder가 토큰 문자열을 그대로 sub로 쓴다 — 한 클래스 안에서 권한 있는 회원·
 * 권한 없는 회원·미가입 주체를 번갈아 흉내 내야 하기 때문이다 (RequireAuthorityAspectTest와
 * 같은 방식).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberQueryControllerTest.StubJwtDecoderConfig.class)
@Transactional
class MemberQueryControllerTest {

    private static final String MEMBERS = "/v1/members";
    private static final String ASSIGNABLE = "/v1/members/assignable";
    private static final String GRADES = "/v1/member-grades";
    private static final String STATUSES = "/v1/member-statuses";

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

    private Long targetMemberId;

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
        assignRole(target, "홍보국장", true);
    }

    /* ── 인가 계단 ───────────────────────────────────────── */

    /*
     * MEMBER_MANAGE 없는 회원은 목록·단건 어느 쪽도 볼 수 없다. 두 요청 모두 애스펙트가
     * 핸들러 호출 전에 끊으므로 서비스 트랜잭션이 열리지 않는다 — 한 테스트에서 두 번
     * 호출해도 rollback-only에 걸리지 않는다.
     */
    @Test
    void listAndDetailRequireMemberManage() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS), PLAIN_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(authorized(get(MEMBERS + "/" + targetMemberId), PLAIN_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 같은 회원이 담당자 후보와 기준 코드는 볼 수 있다. 담당자를 고르는 일과 기준 코드를
     * 읽는 일은 회원 관리와 다른 축이며, @RequireAuthority를 클래스에 걸었다면 여기서
     * 403이 떨어진다.
     */
    @Test
    void assignableAndCodeListsPassWithoutMemberManage() throws Exception {
        mockMvc.perform(authorized(get(ASSIGNABLE), PLAIN_MEMBER)).andExpect(status().isOk());
        mockMvc.perform(authorized(get(GRADES), PLAIN_MEMBER)).andExpect(status().isOk());
        mockMvc.perform(authorized(get(STATUSES), PLAIN_MEMBER)).andExpect(status().isOk());
    }

    /*
     * 인증은 됐지만 mbr 행이 없는 주체. 권한 부족과 상태 코드는 같지만 코드 문자열이 달라야
     * 한다 — 프론트가 한쪽은 가입 화면으로, 다른 쪽은 "권한 없음"으로 안내한다.
     */
    @Test
    void notSignedUpSubjectGets403SignupRequired() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS), UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    /*
     * 기준 코드는 회원가입 화면이 상태 셀렉트를 채우는 데 쓴다 — 그 시점의 주체는 아직
     * 회원이 아니므로 미가입이라고 끊으면 가입 화면이 성립하지 않는다.
     */
    @Test
    void codeListsAreOpenToNotSignedUpSubject() throws Exception {
        UUID stranger = UUID.randomUUID();

        mockMvc.perform(authorized(get(GRADES), stranger)).andExpect(status().isOk());
        mockMvc.perform(authorized(get(STATUSES), stranger)).andExpect(status().isOk());
    }

    @Test
    void requestWithoutTokenIs401() throws Exception {
        mockMvc.perform(get(MEMBERS)).andExpect(status().isUnauthorized());
    }

    /* ── 담당자 후보에 실리는 것 ─────────────────────────── */

    /*
     * **연락처·이메일·학번이 없어야 한다.** 권한 없이 부르는 목록이라 이 셋이 실리면 회원
     * 명부가 그대로 새어 나간다. 값이 null인 것으로는 부족하고 **키 자체가 없어야** 한다 —
     * 필드가 있으면 값이 채워지는 것은 데이터 한 줄 차이다.
     */
    @Test
    void assignableOmitsContactInformation() throws Exception {
        mockMvc.perform(authorized(get(ASSIGNABLE), PLAIN_MEMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[0].phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.data[0].email").doesNotExist())
                .andExpect(jsonPath("$.data[0].studentNumber").doesNotExist())
                // 사람을 고르는 데 필요한 것만 남는다
                .andExpect(jsonPath("$.data[0].memberId").isNumber())
                .andExpect(jsonPath("$.data[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data[0].membershipGradeCode").isNotEmpty())
                .andExpect(jsonPath("$.data[0].membershipGradeName").isNotEmpty());
    }

    // 탈퇴·제명 회원은 후보에서 빠진다. 단건 판정(findAssignableMember)과 같은 규칙이다
    @Test
    void assignableExcludesWithdrawnAndExpelledMembers() throws Exception {
        saveMember(UUID.randomUUID(), "20200004", "탈퇴회원", MemberStatusCode.WITHDRAWN);
        saveMember(UUID.randomUUID(), "20200005", "제명회원", MemberStatusCode.EXPELLED);

        mockMvc.perform(authorized(get(ASSIGNABLE), PLAIN_MEMBER))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].name")
                                .value(Matchers.not(Matchers.hasItems("탈퇴회원", "제명회원"))))
                .andExpect(jsonPath("$.data[*].name").value(Matchers.hasItem("박준호")));
    }

    /* ── 목록·단건에 실리는 것 ───────────────────────────── */

    /*
     * 목록은 data 배열과 page 봉투 두 갈래다 (AP-11). 등급·상태는 코드와 명칭을 함께 내리고,
     * 계정 연결 여부(linkedAccount)도 함께 싣는다 — 이관만 되고 아직 로그인하지 않은 회원을
     * 명부에서 가려내야 한다(#85).
     */
    @Test
    void listReturnsProfileWithCodeNamesAndPageEnvelope() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS).param("q", "박준호"), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("박준호"))
                .andExpect(jsonPath("$.data[0].studentNumber").value("20200003"))
                .andExpect(jsonPath("$.data[0].membershipGradeCode").value("TEMP"))
                .andExpect(jsonPath("$.data[0].membershipGradeName").value("임시회원"))
                .andExpect(jsonPath("$.data[0].membershipStatusCode").value("ENROLLED"))
                .andExpect(jsonPath("$.data[0].membershipStatusName").value("재학"))
                .andExpect(jsonPath("$.data[0].linkedAccount").value(true))
                .andExpect(jsonPath("$.data[0].roles[0].roleName").value("홍보국장"))
                // 남의 권한은 목록에 실리지 않는다 — capabilities는 본인 세션에만 있는 값이다
                .andExpect(jsonPath("$.data[0].capabilities").doesNotExist())
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.sort").value("mbrNm"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.totalCount").value(1));
    }

    @Test
    void detailReturnsCurrentRolesAndRecentChanges() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS + "/" + targetMemberId), MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(targetMemberId))
                .andExpect(jsonPath("$.data.name").value("박준호"))
                .andExpect(jsonPath("$.data.roles[0].roleName").value("홍보국장"))
                .andExpect(jsonPath("$.data.roles[0].representative").value(true))
                // 이력이 없는 회원도 배열은 있어야 한다 (null이면 화면이 분기를 하나 더 둔다)
                .andExpect(jsonPath("$.data.recentChanges").isArray())
                .andExpect(jsonPath("$.data.capabilities").doesNotExist());
    }

    /*
     * 없는 회원은 404다. 404로 감추지 않는다는 규칙(VR-M10)은 권한 부족을 두고 하는 말이고,
     * 정말로 없는 자원은 그대로 404다.
     *
     * 서비스 트랜잭션 안에서 예외가 나 이 테스트의 트랜잭션이 rollback-only가 되므로
     * **요청 하나로 끝낸다** (RoleAuthoritySelfLockTest와 같은 이유).
     */
    @Test
    void unknownMemberIs404() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS + "/" + (targetMemberId + 9999)), MANAGER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 기준 코드 밖의 필터 값. 형식 오류(VALIDATION_FAILED)와 나눠 안내할 수 있어야 한다
    @Test
    void filterOutsideCodeTableIs400InvalidCodeValue() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS).param("mbrGrdCd", "ALUMNI"), MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    // 알 수 없는 정렬 표기도 조용히 기본값으로 떨어뜨리지 않는다
    @Test
    void unknownSortIs400InvalidCodeValue() throws Exception {
        mockMvc.perform(authorized(get(MEMBERS).param("sort", "createdAt"), MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    /* ── 기준 코드 ───────────────────────────────────────── */

    @Test
    void gradeCodesAreOrderedByDisplayOrder() throws Exception {
        mockMvc.perform(authorized(get(GRADES), PLAIN_MEMBER))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].code")
                                .value(Matchers.contains("TEMP", "ASSOC", "ACTIVE", "FULL")))
                .andExpect(jsonPath("$.data[0].name").value("임시회원"));
    }

    @Test
    void statusCodesAreOrderedByDisplayOrder() throws Exception {
        mockMvc.perform(authorized(get(STATUSES), PLAIN_MEMBER))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].code")
                                .value(
                                        Matchers.contains(
                                                "ENROLLED",
                                                "LEAVE",
                                                "MIL_LEAVE",
                                                "GRADUATED",
                                                "WITHDRAWN",
                                                "EXPELLED")))
                .andExpect(jsonPath("$.data[0].name").value("재학"));
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

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

    /*
     * 권한이 붙지 않은 역할을 대표 역할로 부여한다. 여기서 보고 싶은 것은 인가가 아니라
     * 역할명이 목록·상세·담당자 후보에 실리는지다.
     */
    private void assignRole(MemberEntity member, String roleName, boolean representative) {
        MemberRoleClassificationEntity position =
                memberRoleClassificationRepository.findById("POSITION").orElseThrow();
        MemberRoleEntity role =
                memberRoleRepository.save(MemberRoleEntity.create(99, roleName, position));
        memberRoleAssignmentRepository.save(
                MemberRoleAssignmentEntity.create(
                        member, role, LocalDate.now().minusYears(1), representative));
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
