package org.sscc.ssccopsserver.domain.operation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 운영 건 공유 링크(ssccops#200 · ADR-0016) 통합 검증.
 *
 * 확인하려는 것은 기능이 도는가가 아니라 **ADR이 정한 선을 코드가 지키는가**다.
 *  - 발급을 두 번 불러도 토큰이 쌓이지 않는가 (그러지 않으면 폐기가 의미를 잃는다)
 *  - 폐기한 링크가 정말 닫히는가, 그리고 **닫힌 것과 없는 것이 구별되지 않는가**
 *  - 미리보기에 **변하는 값이 섞이지 않는가** (메신저 카드는 한 번 굳는다)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class OperationShareLinkControllerTest {

    private static final String PUBLIC_SHARE = "/public/v1/share/";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private OperationRepository operationRepository;

    private UUID readerToken;
    private UUID outsiderToken;
    private Long operationId;

    @BeforeEach
    void setUp() {
        readerToken = UUID.randomUUID();
        MemberEntity reader = saveMember(readerToken, "20260301", "업무조회자");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                reader,
                MemberRoleFixture.DIRECTOR);

        // WORK_READ가 없는 회원. '다른 권한만' 가진 쪽이어야 403이 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260302", "행사담당");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                outsider,
                AuthorityCode.EVENT_MANAGE);

        operationId = saveOperation(reader, "정기 세션 준비");
    }

    /* ── 발급 ─────────────────────────────────────────────── */

    /*
     * **같은 건에서 두 번 눌러도 토큰이 하나다** (수용 기준 3). 누를 때마다 쌓이면 무엇을
     * 중지해야 할지 알 수 없어 폐기가 의미를 잃는다 — 이 Story에서 가장 중요한 단언이다.
     */
    @Test
    void issuingTwiceReturnsTheSameToken() throws Exception {
        String first = issueToken();
        String second = issueToken();

        assertThat(second).isEqualTo(first);
    }

    /*
     * 토큰은 URL에 그대로 박히므로 인코딩이 필요한 문자가 나오면 안 되고, 예측 가능하면
     * 이 설계의 근거가 무너진다 — 256비트 난수를 URL-safe Base64로 낸 43자다.
     */
    @Test
    void issuedTokenIsUrlSafeAndLongEnoughToBeUnguessable() throws Exception {
        String token = issueToken();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void issuingForUnknownOperationReturns404() throws Exception {
        mockMvc.perform(authorized(post("/v1/operations/999999/share"), readerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 미리보기 (익명) ──────────────────────────────────── */

    /*
     * **토큰만으로 열린다 — 인증 헤더가 없다.** 크롤러가 여는 자리이므로 그것이 정상이다.
     *
     * 그리고 **담기는 것은 제목과 종류뿐이다.** 메신저는 카드를 캐싱하고 갱신하지 않아 한 번
     * 굳으므로, 상태·진행률·마감일·담당자가 섞이면 몇 주 뒤에도 그때 값을 말하는 카드가
     * 방에 남는다. 응답 키를 통째로 못 박아 새 필드가 조용히 섞이는 것을 막는다.
     */
    @Test
    void anonymousPreviewCarriesOnlyTitleAndKind() throws Exception {
        String token = issueToken();

        mockMvc.perform(get(PUBLIC_SHARE + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.operTtl").value("정기 세션 준비"))
                .andExpect(jsonPath("$.data.operTypeCd").value("WORK"))
                // 변하는 값이 하나라도 섞이면 카드가 굳은 뒤 거짓말을 한다
                .andExpect(jsonPath("$.data.workStatus").doesNotExist())
                .andExpect(jsonPath("$.data.progressRate").doesNotExist())
                .andExpect(jsonPath("$.data.dueAt").doesNotExist())
                .andExpect(jsonPath("$.data.owner").doesNotExist())
                .andExpect(jsonPath("$.data.operId").doesNotExist());
    }

    /*
     * 없는 토큰과 폐기된 토큰이 **같은 404**여야 한다 — 나누면 그 차이가 곧 "그 토큰은
     * 있었다"는 정보가 되고, 이 응답은 익명에게 나간다.
     */
    @Test
    void revokedAndUnknownTokensAreIndistinguishable() throws Exception {
        String token = issueToken();
        revoke().andExpect(status().isNoContent());

        String revoked =
                mockMvc.perform(get(PUBLIC_SHARE + token))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String unknown =
                mockMvc.perform(get(PUBLIC_SHARE + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(revoked).isEqualTo(unknown);
    }

    /* ── 폐기 ─────────────────────────────────────────────── */

    /*
     * **유효한 링크가 없어도 204다** — 사용자가 원한 상태가 이미 성립하기 때문이다.
     * 404로 거절하면 '공유 중지'를 두 번 누른 것만으로 오류를 보게 되고 할 수 있는 일이 없다.
     */
    @Test
    void revokingIsIdempotent() throws Exception {
        issueToken();

        revoke().andExpect(status().isNoContent());
        revoke().andExpect(status().isNoContent());
        // 공유한 적이 없는 건도 마찬가지다
        Long neverShared = saveOperation(memberRepository.findAll().get(0), "공유한 적 없는 업무");
        mockMvc.perform(authorized(delete("/v1/operations/" + neverShared + "/share"), readerToken))
                .andExpect(status().isNoContent());
    }

    /*
     * 폐기 뒤 다시 발급하면 **새 토큰**이다 — 옛 토큰이 되살아나면 폐기가 되돌려진 셈이라
     * 이미 퍼진 링크가 다시 열린다.
     */
    @Test
    void reissuingAfterRevokeGivesANewToken() throws Exception {
        String first = issueToken();
        revoke().andExpect(status().isNoContent());

        String second = issueToken();

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get(PUBLIC_SHARE + first)).andExpect(status().isNotFound());
        mockMvc.perform(get(PUBLIC_SHARE + second)).andExpect(status().isOk());
    }

    /* ── 인증·인가 ───────────────────────────────────────── */

    @Test
    void issuingAndRevokingRequireWorkRead() throws Exception {
        mockMvc.perform(authorized(post(shareUrl()), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorized(delete(shareUrl()), outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void issuingAndRevokingRequireAToken() throws Exception {
        mockMvc.perform(post(shareUrl())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(shareUrl())).andExpect(status().isUnauthorized());
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

    private String shareUrl() {
        return "/v1/operations/" + operationId + "/share";
    }

    private String issueToken() throws Exception {
        String response =
                mockMvc.perform(authorized(post(shareUrl()), readerToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.shrTkn", String.class);
    }

    private org.springframework.test.web.servlet.ResultActions revoke() throws Exception {
        return mockMvc.perform(authorized(delete(shareUrl()), readerToken));
    }

    private Long saveOperation(MemberEntity owner, String title) {
        OperationEntity operation =
                OperationEntity.createForWork(
                        title,
                        owner,
                        owner,
                        Instant.parse("2026-03-01T00:00:00Z"),
                        Instant.parse("2026-03-31T00:00:00Z"),
                        OperationPriority.NORMAL);
        return operationRepository.saveAndFlush(operation).getId();
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
