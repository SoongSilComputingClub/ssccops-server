package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;

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
import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.RoleAuthorityRelationEntity;
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
 * 권한 트리 관리 API (#65 · ssccops#70).
 *
 * 확인의 중심은 **"화면 조작 한 번으로 인가가 무력화되지 않는다"**이다 — 시드된 권한은 지워지지도
 * 코드가 바뀌지도 않고, 트리를 고리로 만들 수도 없으며, 쓰이고 있는 권한은 회수 없이 사라지지
 * 않는다. 반대로 이름·설명·트리 위치는 시스템 권한이어도 바뀌어야 한다(운영이 정할 몫이다).
 *
 * 요청 주체를 요청마다 바꿔야 해서(권한 있는 최고운영자 · 없는 회원) JwtDecoder 스텁이 토큰
 * 문자열을 그대로 sub로 쓴다 — RequireAuthorityAspectTest와 같은 방식이다.
 *
 * 서비스가 예외를 던지면 참여 중인 테스트 트랜잭션이 rollback-only로 표시되므로, 실패를 보는
 * 테스트는 실패하는 요청 하나로 끝낸다. 실패 뒤에 이어서 성공을 확인해야 하는 흐름은 트랜잭션
 * 없이 도는 RoleAuthoritySelfLockTest가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthorityControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AuthorityControllerTest {

    private static final String AUTHORITIES = "/v1/authorities";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private UUID adminToken;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        adminToken = UUID.randomUUID();
        MemberEntity admin = saveMember(adminToken, "20260201", "최고운영자");
        grant(admin, AuthorityCode.ROLE_MANAGE);

        // ROLE_MANAGE가 없는 회원. 권한이 아예 없는 것이 아니라 '다른 권한만' 가진 쪽이어야
        // 403이 인증·가입이 아니라 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260202", "업무담당");
        grant(outsider, AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ 트리 조회

    /*
     * 시드된 권한 종이 children 중첩으로 내려온다. 평평한 목록에 upAuthrtCd만 실어 보내면
     * 화면이 트리를 다시 조립해야 하고, 그 조립이 서버의 펼침과 갈릴 여지가 생긴다.
     *
     * 최상위는 SUPER이고 EXECUTIVE가 그 자식이다 (#71). 이 부모-자식 간선이 SUPER가
     * "모든 권한"인 유일한 근거다 — 판정에 특별 취급이 없으므로 여기서 간선이 끊기면 인가가
     * 조용히 좁아진다. 그래서 트리 응답 테스트가 그 사실을 못 박는다.
     *
     * SUPER의 또 다른 자식 SUB_WORK_TYPE_MANAGE(#101)는 일부러 EXECUTIVE 밑이 아니다 —
     * 회장·부회장만 이 권한을 직접 부여받고 총무·국장은 EXECUTIVE·OPERATOR를 통해서도 닿지
     * 못해야 하므로, 자동 상속되는 자리(EXECUTIVE의 자식)에 둘 수 없다.
     */
    @Test
    void returnsSeededPermissionTreeAsNestedChildren() throws Exception {
        mockMvc.perform(authorized(get(AUTHORITIES), adminToken))
                .andExpect(status().isOk())
                // 최상위(부모 없는 노드)는 SUPER 하나뿐이다
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].authrtCd").value("SUPER"))
                .andExpect(jsonPath("$.data[0].upAuthrtCd").doesNotExist())
                .andExpect(jsonPath("$.data[0].sysYn").value(true))
                .andExpect(jsonPath("$.data[0].children", hasSize(2)))
                .andExpect(jsonPath("$.data[0].children[0].authrtCd").value("EXECUTIVE"))
                .andExpect(jsonPath("$.data[0].children[0].authrtNm").value("임원"))
                .andExpect(jsonPath("$.data[0].children[0].upAuthrtCd").value("SUPER"))
                .andExpect(jsonPath("$.data[0].children[1].authrtCd").value("SUB_WORK_TYPE_MANAGE"))
                .andExpect(jsonPath("$.data[0].children[1].upAuthrtCd").value("SUPER"))
                .andExpect(jsonPath("$.data[0].children[0].children", hasSize(4)))
                // 형제 순서는 indct_seqno다
                .andExpect(jsonPath("$.data[0].children[0].children[0].authrtCd").value("OPERATOR"))
                .andExpect(
                        jsonPath("$.data[0].children[0].children[3].authrtCd").value("ROLE_MANAGE"))
                // 증손자까지 중첩된다 — EXECUTIVE > OPERATOR > FORM_MANAGE > FORM_READ.
                // OPERATOR 자식 5개:
                // WORK_MANAGE·SUB_WORK_TYPE_READ·FORM_MANAGE·RESPONSE_REVIEW·MEETING_MANAGE(#83)
                .andExpect(jsonPath("$.data[0].children[0].children[0].children", hasSize(5)))
                .andExpect(
                        jsonPath("$.data[0].children[0].children[0].children[2].authrtCd")
                                .value("FORM_MANAGE"))
                .andExpect(
                        jsonPath(
                                "$.data[0].children[0].children[0].children[2].children",
                                hasSize(3)))
                .andExpect(
                        jsonPath(
                                        "$.data[0].children[0].children[0].children[2].children[0].authrtCd")
                                .value("FORM_READ"))
                // 잎 노드의 children은 null이 아니라 빈 배열이다
                .andExpect(
                        jsonPath(
                                        "$.data[0].children[0].children[0].children[2]"
                                                + ".children[0].children")
                                .isEmpty());
    }

    // 권한 관리 자체가 ROLE_MANAGE를 요구한다 (VR-M12) — 조회도 예외가 아니다
    @Test
    void callerWithoutRoleManageIsForbidden() throws Exception {
        mockMvc.perform(authorized(get(AUTHORITIES), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 생성

    @Test
    void createsCustomBundleAuthorityAsNonSystem() throws Exception {
        mockMvc.perform(
                        authorized(post(AUTHORITIES), adminToken)
                                .content(
                                        """
                                        {
                                          "authrtCd": "PR_MANAGE",
                                          "authrtNm": "홍보 관리",
                                          "upAuthrtCd": "EXECUTIVE",
                                          "authrtExpln": "홍보 업무 묶음",
                                          "indctSeqno": 6
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.authrtCd").value("PR_MANAGE"))
                .andExpect(jsonPath("$.data.upAuthrtCd").value("EXECUTIVE"))
                // 화면에서 만든 권한은 언제나 sys_yn = false다 (BR-M32) — 요청으로 받지 않는다
                .andExpect(jsonPath("$.data.sysYn").value(false));

        flushAndClear();
        assertThat(authorityRepository.findById("PR_MANAGE")).isPresent();
    }

    @Test
    void rejectsDuplicatedAuthorityCode() throws Exception {
        mockMvc.perform(
                        authorized(post(AUTHORITIES), adminToken)
                                .content(createBody("ROLE_MANAGE", "역할 관리 사칭")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTHORITY_CODE_DUPLICATED"));
    }

    // 코드 표기(UPPER_SNAKE_CASE)가 어긋나면 화면에 두 벌의 어휘가 생긴다
    @Test
    void rejectsMalformedAuthorityCode() throws Exception {
        mockMvc.perform(
                        authorized(post(AUTHORITIES), adminToken)
                                .content(createBody("pr-manage", "홍보 관리")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsCreationUnderUnknownParent() throws Exception {
        mockMvc.perform(
                        authorized(post(AUTHORITIES), adminToken)
                                .content(
                                        """
                                        {"authrtCd": "PR_MANAGE", "authrtNm": "홍보 관리",
                                         "upAuthrtCd": "NO_SUCH_AUTHORITY"}
                                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTHORITY_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 수정

    /*
     * sys_yn = true여도 이름·설명은 바뀐다 (BR-M33). 조직이 부르는 이름은 바뀌는데 코드가
     * 참조하는 값만 그대로여야 한다 — 둘을 함께 막으면 화면에서 고칠 수 있는 것이 없어진다.
     */
    @Test
    void systemAuthorityCanBeRenamed() throws Exception {
        mockMvc.perform(
                        authorized(patch(AUTHORITIES + "/ROLE_MANAGE"), adminToken)
                                .content(
                                        """
                                        {"authrtNm": "권한 관리", "upAuthrtCd": "EXECUTIVE",
                                         "authrtExpln": "역할별 권한 지정"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authrtNm").value("권한 관리"))
                .andExpect(jsonPath("$.data.sysYn").value(true));

        flushAndClear();
        assertThat(authorityRepository.findById("ROLE_MANAGE").orElseThrow().getName())
                .isEqualTo("권한 관리");
    }

    // 시스템 권한의 코드가 바뀌면 그 코드를 요구하는 엔드포인트가 통째로 막힌다
    @Test
    void systemAuthorityCodeChangeIsRejected() throws Exception {
        mockMvc.perform(
                        authorized(patch(AUTHORITIES + "/ROLE_MANAGE"), adminToken)
                                .content(
                                        """
                                        {"authrtCd": "ROLE_ADMIN", "authrtNm": "역할·권한 관리",
                                         "upAuthrtCd": "EXECUTIVE"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_AUTHORITY_IMMUTABLE"));
    }

    // 트리 위치는 시스템 권한도 옮길 수 있다 — 막히는 것은 삭제와 코드뿐이다
    @Test
    void systemAuthorityCanBeMovedInTheTree() throws Exception {
        mockMvc.perform(
                        authorized(patch(AUTHORITIES + "/MEMBER_MANAGE"), adminToken)
                                .content(
                                        """
                                        {"authrtNm": "회원 관리", "upAuthrtCd": "OPERATOR"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upAuthrtCd").value("OPERATOR"));

        flushAndClear();
        assertThat(
                        authorityRepository
                                .findById("MEMBER_MANAGE")
                                .orElseThrow()
                                .getParent()
                                .getCode())
                .isEqualTo("OPERATOR");
    }

    @Test
    void rejectsSelfAsParent() throws Exception {
        mockMvc.perform(
                        authorized(patch(AUTHORITIES + "/OPERATOR"), adminToken)
                                .content(
                                        """
                                        {"authrtNm": "운영자", "upAuthrtCd": "OPERATOR"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTHORITY_CYCLE_DETECTED"));
    }

    /*
     * 자기 자손을 상위로 두면 고리가 된다. EXECUTIVE > OPERATOR > FORM_MANAGE > FORM_READ이므로
     * 손자보다 더 아래를 골라 조상 탐색이 한 단계가 아니라 끝까지 올라가는지 본다.
     */
    @Test
    void rejectsDescendantAsParent() throws Exception {
        mockMvc.perform(
                        authorized(patch(AUTHORITIES + "/EXECUTIVE"), adminToken)
                                .content(
                                        """
                                        {"authrtNm": "임원", "upAuthrtCd": "FORM_READ"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTHORITY_CYCLE_DETECTED"));
    }

    @Test
    void unknownAuthorityReturnsNotFound() throws Exception {
        mockMvc.perform(
                        authorized(patch(AUTHORITIES + "/NO_SUCH_AUTHORITY"), adminToken)
                                .content(
                                        """
                                        {"authrtNm": "없는 권한"}
                                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTHORITY_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    void deletesUnassignedCustomAuthority() throws Exception {
        saveCustomAuthority("PR_MANAGE", null);

        mockMvc.perform(authorized(delete(AUTHORITIES + "/PR_MANAGE"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        flushAndClear();
        assertThat(authorityRepository.findById("PR_MANAGE")).isEmpty();
    }

    // 시스템 권한이 지워지면 그 코드를 요구하는 엔드포인트가 아무도 통과하지 못한다
    @Test
    void systemAuthorityDeleteIsRejected() throws Exception {
        mockMvc.perform(authorized(delete(AUTHORITIES + "/WORK_MANAGE"), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_AUTHORITY_IMMUTABLE"));
    }

    // 부여된 권한은 회수를 먼저 하게 한다 — 삭제 한 번으로 여러 역할의 범위가 조용히 줄지 않는다
    @Test
    void assignedAuthorityCannotBeDeleted() throws Exception {
        AuthorityEntity custom = saveCustomAuthority("PR_MANAGE", null);
        MemberRoleEntity role =
                AuthorityFixture.grantRoleWithoutAuthority(
                        memberRoleRepository,
                        memberRoleClassificationRepository,
                        memberRoleAssignmentRepository,
                        saveMember(UUID.randomUUID(), "20260203", "홍보국장"),
                        "홍보국장");
        roleAuthorityRelationRepository.saveAndFlush(
                RoleAuthorityRelationEntity.create(role, custom));

        mockMvc.perform(authorized(delete(AUTHORITIES + "/PR_MANAGE"), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTHORITY_IN_USE"));
    }

    // 부모를 지우면 자식이 갈 곳을 잃는다 — 조부모로 조용히 옮기지 않고 거절한다
    @Test
    void authorityWithChildrenCannotBeDeleted() throws Exception {
        AuthorityEntity parent = saveCustomAuthority("PR_MANAGE", null);
        saveCustomAuthority("PR_POST_WRITE", parent);

        mockMvc.perform(authorized(delete(AUTHORITIES + "/PR_MANAGE"), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTHORITY_IN_USE"));
    }

    // ------------------------------------------------------------------ 헬퍼

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

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private AuthorityEntity saveCustomAuthority(String code, AuthorityEntity parent) {
        AuthorityEntity authority =
                AuthorityEntity.create(code, code, parent, null, false, (short) 99);
        entityManager.persist(authority);
        entityManager.flush();
        return authority;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static String createBody(String code, String name) {
        return "{\"authrtCd\": \"%s\", \"authrtNm\": \"%s\"}".formatted(code, name);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
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
