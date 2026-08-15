package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
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
 * 자기 잠금 방지와 '회수 후 삭제'를 실제 커밋·롤백까지 확인한다 (#65 · VR-M13).
 *
 * **테스트에 @Transactional을 걸면 이 규칙을 검증할 수 없다.** 자기 잠금 방지는 교체를 적용한
 * 뒤 정책에게 다시 물어보고 예외로 되돌리는 구조인데, 테스트가 트랜잭션을 잡고 있으면 서비스의
 * 롤백이 참여 트랜잭션을 rollback-only로 표시하기만 하고 실제로 되돌아가지 않아 "거절은 됐지만
 * 부여는 사라진" 상태를 그대로 보게 된다 (MemberSignupRollbackTest와 같은 이유).
 *
 * 커밋이 실제로 일어나므로 이 클래스가 만든 데이터는 @AfterEach가 직접 치운다 — H2 인메모리 DB를
 * 다른 테스트 클래스와 공유하기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RoleAuthoritySelfLockTest.StubJwtDecoderConfig.class)
class RoleAuthoritySelfLockTest {

    private static final String CUSTOM_CODE = "PR_MANAGE";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private UUID adminToken;
    private Long adminRoleId;
    private Long targetRoleId;
    private final List<Long> createdMemberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        adminToken = UUID.randomUUID();
        MemberEntity admin = saveMember(adminToken, "20260401", "최고운영자");
        adminRoleId =
                AuthorityFixture.grant(
                                memberRoleRepository,
                                memberRoleClassificationRepository,
                                memberRoleAssignmentRepository,
                                authorityRepository,
                                roleAuthorityRelationRepository,
                                admin,
                                AuthorityCode.ROLE_MANAGE)
                        .getId();

        targetRoleId =
                AuthorityFixture.grantRoleWithoutAuthority(
                                memberRoleRepository,
                                memberRoleClassificationRepository,
                                memberRoleAssignmentRepository,
                                saveMember(UUID.randomUUID(), "20260402", "홍보국장"),
                                "홍보국장")
                        .getId();
    }

    /*
     * 마지막 ROLE_MANAGE 보유자가 스스로를 잠그는 조작은 거절된다. 거절만으로는 부족하고
     * **부여가 그대로 남아 있어야** 한다 — 되돌아가지 않으면 아무도 권한을 관리할 수 없어
     * DB를 직접 고쳐야 복구된다.
     */
    @Test
    void revokingOwnRoleManageIsRejectedAndLeavesTheGrantIntact() throws Exception {
        RoleAuthorityRelationEntity before =
                roleAuthorityRelationRepository.findAllByRoleId(adminRoleId).get(0);
        Long beforeId = before.getId();
        Instant beforeCreatedAt = before.getCreatedAt();

        mockMvc.perform(replace(adminRoleId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_REVOKE_OWN_ROLE_MANAGE"));

        List<RoleAuthorityRelationEntity> after =
                roleAuthorityRelationRepository.findAllByRoleId(adminRoleId);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getId()).isEqualTo(beforeId);
        assertThat(after.get(0).getCreatedAt()).isEqualTo(beforeCreatedAt);

        // 여전히 권한 관리 화면에 들어갈 수 있다
        mockMvc.perform(authorized(get("/v1/authorities"))).andExpect(status().isOk());
    }

    /*
     * 남의 역할에서 ROLE_MANAGE를 회수하는 것은 막지 않는다 — 회수한 쪽이 여전히 관리할 수 있어
     * 아무도 못 고치는 상태가 되지 않는다. 자기 자신인지가 기준이지 코드가 기준이 아니다.
     */
    @Test
    void revokingRoleManageFromAnotherRoleIsAllowed() throws Exception {
        mockMvc.perform(replace(targetRoleId, "ROLE_MANAGE")).andExpect(status().isOk());

        mockMvc.perform(replace(targetRoleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants", hasSize(0)));

        assertThat(roleAuthorityRelationRepository.findAllByRoleId(targetRoleId)).isEmpty();
    }

    /*
     * 부여된 권한은 회수를 먼저 해야 지워진다. 실패와 성공을 한 흐름으로 보는 것이 요점이라
     * (트랜잭션을 건 테스트에서는 실패 뒤 이어지는 요청이 rollback-only에 걸린다) 이 클래스에 둔다.
     */
    @Test
    void assignedAuthorityIsDeletableOnlyAfterRevoke() throws Exception {
        mockMvc.perform(
                        authorized(post("/v1/authorities"))
                                .content(
                                        """
                                        {"authrtCd": "PR_MANAGE", "authrtNm": "홍보 관리"}
                                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(replace(targetRoleId, CUSTOM_CODE)).andExpect(status().isOk());

        mockMvc.perform(authorized(delete("/v1/authorities/" + CUSTOM_CODE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTHORITY_IN_USE"));
        assertThat(authorityRepository.findById(CUSTOM_CODE)).isPresent();

        mockMvc.perform(replace(targetRoleId)).andExpect(status().isOk());

        mockMvc.perform(authorized(delete("/v1/authorities/" + CUSTOM_CODE)))
                .andExpect(status().isOk());
        assertThat(authorityRepository.findById(CUSTOM_CODE)).isEmpty();
    }

    /*
     * 커밋이 실제로 일어나므로 이 클래스가 만든 행을 직접 치운다. FK 순서대로 부여 → 배정 →
     * 역할 → 회원이며, 사용자 정의 권한은 테스트에 따라 이미 지워졌을 수 있다.
     */
    @AfterEach
    void tearDown() {
        for (Long roleId : List.of(adminRoleId, targetRoleId)) {
            roleAuthorityRelationRepository.deleteAll(
                    roleAuthorityRelationRepository.findAllByRoleId(roleId));
        }
        for (Long memberId : createdMemberIds) {
            List<MemberRoleAssignmentEntity> assignments =
                    memberRoleAssignmentRepository.findCurrentByMemberId(memberId);
            memberRoleAssignmentRepository.deleteAll(assignments);
        }
        memberRoleRepository.deleteAllById(List.of(adminRoleId, targetRoleId));
        memberRepository.deleteAllById(createdMemberIds);
        createdMemberIds.clear();
        authorityRepository.findById(CUSTOM_CODE).ifPresent(authorityRepository::delete);
    }

    // ------------------------------------------------------------------ 헬퍼

    private MockHttpServletRequestBuilder replace(Long roleId, String... codes) {
        String body =
                "{\"authrtCds\": [%s]}"
                        .formatted(
                                String.join(
                                        ", ",
                                        Arrays.stream(codes).map("\"%s\""::formatted).toList()));
        return authorized(put("/v1/roles/" + roleId + "/authorities")).content(body);
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        MemberEntity member =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        authUserId,
                        studentNumber,
                        name,
                        studentNumber + "@sscc.org");
        createdMemberIds.add(member.getId());
        return member;
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + adminToken)
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
