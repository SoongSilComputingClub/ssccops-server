package org.sscc.ssccopsserver.domain.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;
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
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;

/*
 * 최초 가입자 SUPER 부트스트랩 (#71 · ssccops#71).
 *
 * 여기서만 mbr 테이블을 비워 둔 채 시작한다. 평상시 가입은 MemberControllerTest가 맡으며,
 * 그쪽은 무관한 회원을 하나 두어 이 창구를 닫고 시작한다 — 두 클래스가 각자 하나의 전제만
 * 다루게 해 두어야 "회원이 있는가 없는가"가 결과를 가른다는 사실이 테스트에 드러난다.
 *
 * 토큰 문자열이 곧 주체 UUID인 JwtDecoder를 쓴다. 최초 가입자와 그 다음 가입자를 한 클래스
 * 안에서 흉내 내려면 주체가 고정되어 있으면 안 되기 때문이다.
 *
 * 인가가 실제로 열렸는지는 응답의 capabilities가 아니라 **보호된 엔드포인트를 직접 호출해서**
 * 확인한다. 응답 필드와 애스펙트가 같은 AuthorityPolicy를 쓰지만, 그 사실 자체가 이 테스트가
 * 지켜야 할 대상이라 응답만 보고 통과시키면 규칙이 스스로를 증명하는 꼴이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberSignupBootstrapTest.MultiUserJwtDecoderConfig.class)
@Transactional
class MemberSignupBootstrapTest {

    private static final UUID FOUNDER = UUID.randomUUID();
    private static final UUID SECOND_MEMBER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @Test
    void firstMemberOfAnEmptySystemGetsTheBootstrapRole() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200001")))
                .andExpect(status().isCreated())
                // 등급은 여전히 임시회원이다 — 부트스트랩이 주는 것은 권한이지 등급이 아니다
                .andExpect(jsonPath("$.data.membershipGradeCode").value("TEMP"))
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(jsonPath("$.data.roles[0].roleName").value("최고관리자"))
                .andExpect(jsonPath("$.data.roles[0].representative").value(true));
    }

    /*
     * capabilities에 권한 코드 **전부**가 담겨야 한다.
     *
     * SUPER 하나만 부여했는데 14종이 전부 나오는 것이 이 설계의 핵심이다 — AuthorityPolicy에
     * SUPER를 특별 취급하는 분기는 없고, 트리에서 SUPER가 EXECUTIVE의 부모라는 사실 하나가
     * 나머지를 전부 끌고 온다(BR-M35). 코드를 하나 더 추가하고 트리에 매달지 않으면 여기서
     * 잡힌다.
     */
    @Test
    void bootstrapOpensEveryAuthorityCodeThroughTheTree() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200001")))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.data.capabilities")
                                .value(
                                        Matchers.containsInAnyOrder(
                                                Arrays.stream(AuthorityCode.values())
                                                        .map(AuthorityCode::code)
                                                        .toArray())));
    }

    /*
     * 재로그인 없이 통과해야 한다. 인가가 요청마다 DB를 보므로(#9) 방금 받은 역할이 같은
     * 토큰의 다음 요청부터 바로 반영된다 — GrantedAuthority로 굳혔다면 여기서 403이 났다.
     */
    @Test
    void firstMemberPassesAuthorityCheckWithoutSigningInAgain() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200001"))).andExpect(status().isCreated());

        mockMvc.perform(get("/v1/authorities").header("Authorization", "Bearer " + FOUNDER))
                .andExpect(status().isOk());
    }

    /*
     * 창구는 한 번만 열린다. 두 번째 가입자는 역할도 권한도 없이 들어오고, 보호된 엔드포인트는
     * 403으로 막힌다 — 최고관리자를 나눠 줄지는 운영진이 화면에서 정할 몫이다(#70).
     */
    @Test
    void secondMemberGetsNothingAndIsRejectedByAuthorityCheck() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200001"))).andExpect(status().isCreated());

        mockMvc.perform(signup(SECOND_MEMBER, body("이서연", "20200002")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roles").isEmpty())
                .andExpect(jsonPath("$.data.capabilities").isEmpty());

        mockMvc.perform(get("/v1/authorities").header("Authorization", "Bearer " + SECOND_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 시드가 깨졌으면 가입 자체를 실패시킨다 (VR-M15).
     *
     * 조용히 통과시키면 권한 없는 첫 회원이 남고, 그 순간 mbr이 더는 비어 있지 않아 **부트스트랩
     * 창구가 영영 닫힌다** — 복구는 수동 SQL뿐이다. 사용자 입력 문제가 아니므로 400이 아니라
     * 500으로 드러나야 한다(등급·상태 시드가 없을 때와 같은 취급).
     *
     * 부여 결과를 AuthorityPolicy로 되물어 확인하기 때문에 매핑만 지워도 잡힌다 — 역할은
     * 멀쩡히 배정되지만 그 역할이 아무 권한도 열지 못하는 상태다.
     *
     * 실패하는 요청 하나로 끝낸다. 트랜잭션이 rollback-only로 표시되므로 뒤에 요청을 더 보내면
     * UnexpectedRollbackException을 만난다 (AGENTS.md).
     */
    @Test
    void signupFailsWhenTheBootstrapRoleGrantsNothing() throws Exception {
        Long bootstrapRoleId = bootstrapRoleId();
        roleAuthorityRelationRepository.deleteAll(
                roleAuthorityRelationRepository.findAllByRoleId(bootstrapRoleId));

        mockMvc.perform(signup(FOUNDER, body("김도현", "20200001")))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void signupFailsWhenTheBootstrapRoleIsMissing() throws Exception {
        Long bootstrapRoleId = bootstrapRoleId();
        roleAuthorityRelationRepository.deleteAll(
                roleAuthorityRelationRepository.findAllByRoleId(bootstrapRoleId));
        memberRoleRepository.deleteById(bootstrapRoleId);

        mockMvc.perform(signup(FOUNDER, body("김도현", "20200001")))
                .andExpect(status().isInternalServerError());
    }

    private Long bootstrapRoleId() {
        return memberRoleRepository.findAllByNameForUpdate("최고관리자").stream()
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private static String body(String name, String studentNumber) {
        return """
                {
                  "name": "%s",
                  "phoneNumber": "010-1234-5678",
                  "memberStatusCode": "ENROLLED",
                  "studentNumber": "%s",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3
                }
                """
                .formatted(name, studentNumber);
    }

    private static MockHttpServletRequestBuilder signup(UUID authUserId, String body) {
        return post("/v1/members/signup")
                .header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @TestConfiguration
    static class MultiUserJwtDecoderConfig {

        /** 토큰 문자열을 그대로 주체(sub)로 쓴다 — 한 클래스에서 여러 사용자를 흉내 내기 위해서다 */
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", token + "@sscc.org")
                            .claim("user_metadata", Map.of("full_name", "테스트"))
                            .claim("app_metadata", Map.of("provider", "google"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
