package org.sscc.ssccopsserver.domain.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.LeaderRole;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * **리더 역할은 운영진이 아니다** (#687 · ssccops#611).
 *
 * 2026-10-18 prod 제보: 스터디장 계정에 어드민 «학술» 메뉴 전부가 보였고 버튼도 다 켜졌다. 원인은
 * 스터디장 역할에 ACADEMIC_PROGRAM_MANAGE가 붙은 것이었고, 기획안 승인이 그 역할을 자동으로 내주므로
 * 승인된 리더 전원이 학술국장이 됐다.
 *
 * 그전까지 이 사실을 지키는 시험이 없었다 — 학술 시험의 «리더»는 `leadr_mbr_id`로만 가리킨 맨 회원이라
 * 시드 역할을 실제로 가진 회원으로 운영진 API를 두드려 본 적이 없다. 그래서 **시드된 리더 역할을 실제로
 * 배정받은 회원**으로 두 가지를 본다:
 *  1. 세션 응답의 capabilities가 비어 있다 — 어드민 메뉴와 화면 버튼이 그리는 값이다(ssccops-web nav.ts)
 *  2. 운영진 API가 전부 403이다 — 메뉴를 감추는 것은 안내이고 실제 차단은 서버다
 *
 * 리더가 자기 프로그램을 다루는 길(lms · 소유권 판정)은 여기서 보지 않는다 — 각 학술 컨트롤러 시험이
 * 리더 본인 200을 이미 본다. 이 시험이 지키는 것은 «역할이 운영진 권한을 실어 오지 않는다» 하나다.
 *
 * 역할에 권한을 붙이는 쪽의 차단은 RoleAuthorityControllerTest, 승인 쪽은
 * AcademicProgramApprovalEffectsServiceImplTest가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class LeaderRoleIsNotStaffTest {

    /*
     * 운영진 화면이 첫 조회로 부르는 GET들. 메뉴(ssccops-web nav.ts)의 `requires`가 가리키는 권한을
     * 도메인마다 하나씩 덮는다 — 학술(대시보드·회차 이력·승인 대기) · 운영 · 승인함 · 회원 · 역할 · 폼 · 행사.
     */
    private static final List<String> STAFF_ENDPOINTS =
            List.of(
                    "/v1/academic-programs/sessions",
                    "/v1/academic-programs/reviews/sessions",
                    "/v1/dashboard",
                    "/v1/works",
                    "/v1/approvals",
                    "/v1/members",
                    "/v1/roles",
                    "/v1/forms",
                    "/v1/events");

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @ParameterizedTest
    @EnumSource(LeaderRole.class)
    void leaderRoleCarriesNoCapability(LeaderRole leaderRole) throws Exception {
        UUID token = leaderWith(leaderRole);

        mockMvc.perform(get("/v1/auth/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.roles[0].roleName").value(leaderRole.roleName()))
                .andExpect(jsonPath("$.data.member.capabilities").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(LeaderRole.class)
    void leaderRoleIsForbiddenOnEveryStaffEndpoint(LeaderRole leaderRole) throws Exception {
        UUID token = leaderWith(leaderRole);

        for (String endpoint : STAFF_ENDPOINTS) {
            mockMvc.perform(get(endpoint).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // 시드된 그 역할을 배정한다 — 이름이 같은 새 역할을 만들면 시드가 붙인(붙이지 않은) 권한을 못 본다
    private UUID leaderWith(LeaderRole leaderRole) {
        UUID token = UUID.randomUUID();
        MemberEntity leader =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        token,
                        "2026" + (7000 + leaderRole.ordinal()),
                        leaderRole.roleName() + " 회원",
                        "leader" + leaderRole.ordinal() + "@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                leader,
                leaderRole.roleName());
        return token;
    }
}
