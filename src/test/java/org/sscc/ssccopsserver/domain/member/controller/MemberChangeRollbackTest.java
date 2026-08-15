package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
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
 * 이력 저장이 실패하면 등급 갱신도 함께 되돌아가야 한다 (#78).
 *
 * "등급을 바꾼다"와 "이력을 남긴다"가 나눌 수 없는 한 건이라는 것이 이 이슈의 전제다. 등급만
 * 바뀌고 이력이 없으면 그 승급은 근거를 잃고, 이력 행은 updatable = false로 잠겨 있어 나중에
 * 채워 넣을 경로도 없다.
 *
 * ── @Transactional을 걸 수 없다 ────────────────────────────────
 * 테스트에 트랜잭션을 걸면 실제 커밋·롤백이 일어나지 않아 이 규칙을 검증할 수 없다
 * (MemberSignupRollbackTest·RoleAuthoritySelfLockTest와 같은 이유).
 *
 * ── 그래서 DB를 따로 쓴다 ──────────────────────────────────────
 * 커밋한 회원이 남으므로 공용 H2(testdb)를 쓰면 "회원이 한 명도 없는 상태"를 전제하는
 * 부트스트랩 테스트가 실행 순서에 따라 깨진다. URL을 바꿔 이 클래스만의 DB를 띄운다
 * (MemberSignupBootstrapConcurrencyTest와 같은 방식) — 뒷정리에 기대는 대신 애초에 남 볼 일이
 * 없게 만드는 쪽이 순서 의존을 없앤다.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:member-change-rollback;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberChangeRollbackTest.StubJwtDecoderConfig.class)
class MemberChangeRollbackTest {

    private static final UUID MANAGER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @MockitoBean private MemberGradeHistoryRepository memberGradeHistoryRepository;

    private Long targetMemberId;

    @BeforeEach
    void setUp() {
        MemberEntity manager =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        MANAGER,
                        "20200001",
                        "김도현",
                        "manager@sscc.org");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.MEMBER_MANAGE);

        targetMemberId =
                MemberFixture.save(
                                memberRepository,
                                memberGradeRepository,
                                memberStatusRepository,
                                UUID.randomUUID(),
                                "20200002",
                                "박준호",
                                "target@sscc.org")
                        .getId();
    }

    @Test
    void gradeUpdateIsRolledBackWhenHistorySaveFails() throws Exception {
        given(memberGradeHistoryRepository.save(any()))
                .willThrow(new IllegalStateException("등급 이력 저장 실패"));

        mockMvc.perform(
                        post("/v1/members/" + targetMemberId + "/grade-changes")
                                .header("Authorization", "Bearer " + MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"aftrMbrGrdCd\": \"ASSOC\"}"))
                .andExpect(status().isInternalServerError());

        /*
         * 이력이 없으면 등급도 바뀌지 않은 것이어야 한다 — 반쪽 변경이 남지 않는다.
         * 트랜잭션 밖에서 읽으므로 등급을 함께 끌어오는 조회를 쓴다(findById는 프록시로 남긴다).
         */
        assertThat(memberRepository.findWithGradeAndStatusById(targetMemberId).orElseThrow())
                .extracting(member -> member.getMembershipGrade().getCode())
                .isEqualTo(MemberGradeCode.TEMP.code());
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
