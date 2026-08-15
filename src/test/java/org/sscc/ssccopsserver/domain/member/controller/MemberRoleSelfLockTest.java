package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 역할 종료가 자기 잠금 방지를 우회하지 못하는지 확인한다 (#81 · VR-M13).
 *
 * **#65의 가드는 권한 쪽 문만 지킨다.** 역할에 붙은 권한을 그대로 두고 사람에게서 그 역할을
 * 떼어도 결과는 같으므로, 종료·기간 단축이 두 번째 문이다. 두 문이 같은 RoleManageSelfLockGuard를
 * 부르는지가 여기서 실제로 드러난다 — 판정을 복제한 구현은 한쪽만 고쳐진 채 남는다.
 *
 * **테스트에 @Transactional을 걸면 이 규칙을 검증할 수 없다.** 가드는 변경을 적용한 뒤 정책에게
 * 다시 물어보고 예외로 되돌리는 구조인데, 테스트가 트랜잭션을 잡고 있으면 서비스의 롤백이
 * 참여 트랜잭션을 rollback-only로 표시하기만 하고 실제로 되돌아가지 않아 "거절은 됐지만 종료는
 * 남은" 상태를 그대로 보게 된다 (RoleAuthoritySelfLockTest와 같은 이유).
 *
 * 커밋이 실제로 일어나므로 이 클래스가 만든 데이터는 @AfterEach가 직접 치운다 — H2 인메모리 DB를
 * 다른 테스트 클래스와 공유하기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberRoleSelfLockTest.StubJwtDecoderConfig.class)
class MemberRoleSelfLockTest {

    /** ROLE_MANAGE를 요구하는 엔드포인트. 잠금 여부를 실제 통과 여부로 확인한다 */
    private static final String ROLES = "/v1/roles";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private UUID firstManagerToken;
    private UUID secondManagerToken;
    private Long firstManagerId;
    private Long firstAssignmentId;

    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        firstManagerToken = UUID.randomUUID();
        MemberEntity firstManager = saveMember(firstManagerToken, "20260601", "권한관리자");
        firstManagerId = firstManager.getId();
        createdRoleIds.add(grantRoleManage(firstManager));
        firstAssignmentId = onlyAssignmentOf(firstManagerId).getId();

        secondManagerToken = UUID.randomUUID();
        createdRoleIds.add(grantRoleManage(saveMember(secondManagerToken, "20260602", "다른관리자")));
    }

    /*
     * 스스로 자기 ROLE_MANAGE 역할을 끝내는 것은 거절된다. 거절만으로는 부족하고 **종료가 그대로
     * 되돌아가야** 한다 — 남으면 아무도 역할을 관리할 수 없어 DB를 직접 고쳐야 복구된다.
     */
    @Test
    void endingOwnRoleManageAssignmentIsRejectedAndRolledBack() throws Exception {
        mockMvc.perform(endOwnAssignment(firstManagerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_REVOKE_OWN_ROLE_MANAGE"));

        assertThat(
                        memberRoleAssignmentRepository
                                .findById(firstAssignmentId)
                                .orElseThrow()
                                .getRoleEndDate())
                .isNull();

        // 여전히 역할 관리 화면에 들어갈 수 있다
        mockMvc.perform(authorized(get(ROLES), firstManagerToken)).andExpect(status().isOk());
    }

    /*
     * **다른 사람이 끝내는 것은 막지 않는다.** 그 경우엔 끝낸 쪽이 여전히 관리할 수 있어 아무도
     * 되돌리지 못하는 상태가 되지 않는다. 기준은 '자기 자신인가'이지 '어떤 권한인가'가 아니다.
     *
     * 종료가 재로그인 없이 곧바로 인가에 닿는 것까지 함께 본다 (BR-M31) — 토큰은 그대로인데
     * 다음 요청부터 403이다.
     */
    @Test
    void anotherManagerMayEndTheSameAssignment() throws Exception {
        mockMvc.perform(endOwnAssignment(secondManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(false));

        assertThat(
                        memberRoleAssignmentRepository
                                .findById(firstAssignmentId)
                                .orElseThrow()
                                .getRoleEndDate())
                .isEqualTo(LocalDate.now().minusDays(1));

        mockMvc.perform(authorized(get(ROLES), firstManagerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 끝낸 쪽은 그대로다 — 관리 창구가 닫히지 않았다
        mockMvc.perform(authorized(get(ROLES), secondManagerToken)).andExpect(status().isOk());
    }

    /*
     * 커밋이 실제로 일어나므로 이 클래스가 만든 행을 직접 치운다. FK 순서대로 배정 → 부여 →
     * 역할 → 회원이다.
     */
    @AfterEach
    void tearDown() {
        for (Long memberId : createdMemberIds) {
            memberRoleAssignmentRepository.deleteAll(
                    memberRoleAssignmentRepository.findAllByMemberId(memberId));
        }
        for (Long roleId : createdRoleIds) {
            roleAuthorityRelationRepository.deleteAll(
                    roleAuthorityRelationRepository.findAllByRoleId(roleId));
        }
        memberRoleRepository.deleteAllById(createdRoleIds);
        memberRepository.deleteAllById(createdMemberIds);
        createdRoleIds.clear();
        createdMemberIds.clear();
    }

    // ------------------------------------------------------------------ 헬퍼

    /** 첫 관리자의 ROLE_MANAGE 배정을 어제로 끝내는 요청. 누가 부르는가만 달라진다 */
    private MockHttpServletRequestBuilder endOwnAssignment(UUID token) {
        return authorized(
                        patch("/v1/members/" + firstManagerId + "/roles/" + firstAssignmentId),
                        token)
                .content("{\"roleEndYmd\": \"%s\"}".formatted(LocalDate.now().minusDays(1)));
    }

    private MemberRoleAssignmentEntity onlyAssignmentOf(Long memberId) {
        List<MemberRoleAssignmentEntity> assignments =
                memberRoleAssignmentRepository.findAllByMemberId(memberId);
        assertThat(assignments).hasSize(1);
        return assignments.get(0);
    }

    private Long grantRoleManage(MemberEntity member) {
        return AuthorityFixture.grant(
                        memberRoleRepository,
                        memberRoleClassificationRepository,
                        memberRoleAssignmentRepository,
                        authorityRepository,
                        roleAuthorityRelationRepository,
                        member,
                        AuthorityCode.ROLE_MANAGE)
                .getId();
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
