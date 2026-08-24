package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
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
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseReviewHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * 처리 이력 저장이 실패하면 응답 상태도 함께 되돌아가야 한다 (#141).
 *
 * "심사한다"와 "그 사실을 남긴다"가 나눌 수 없는 한 건이라는 것이 이 이슈의 전제다. 상태만
 * 바뀌고 이력이 없으면 그 승인·반려는 근거를 잃고, 이력 행은 updatable = false로 잠겨 있어
 * 나중에 채워 넣을 경로도 없다 — 반려 사유가 없는 반려는 이 이슈 이전 상태 그대로다.
 * 선례는 MemberChangeRollbackTest(#78)다.
 *
 * ── @Transactional을 걸 수 없다 ────────────────────────────────
 * 테스트에 트랜잭션을 걸면 실제 커밋·롤백이 일어나지 않아 이 규칙을 검증할 수 없다. 롤백된 척만
 * 하는 영속성 컨텍스트에는 바뀐 상태가 그대로 남아 있어 테스트가 초록인 채 결함이 살아 있게 된다
 * (MemberSignupRollbackTest·RoleAuthoritySelfLockTest와 같은 이유).
 *
 * ── 그래서 DB를 따로 쓴다 ──────────────────────────────────────
 * 커밋한 회원·폼·응답이 남으므로 공용 H2(testdb)를 쓰면 "회원이 한 명도 없는 상태"를 전제하는
 * 부트스트랩 테스트가 실행 순서에 따라 깨진다. URL을 바꿔 이 클래스만의 DB를 띄운다
 * (MemberChangeRollbackTest와 같은 방식).
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:form-response-review-rollback;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FormResponseReviewRollbackTest.StubJwtDecoderConfig.class)
class FormResponseReviewRollbackTest {

    private static final UUID REVIEWER = UUID.randomUUID();

    private static final String SAMPLE_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기본 정보", "pageDescCn": null}],
              "qitems": [
                {
                  "qitemId": "q1", "qitemLblNm": "지원 동기", "qitemTypeCd": "LONG_TEXT",
                  "reqYn": true, "pageSeq": 0, "optionList": []
                }
              ]
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    @MockitoBean private FormResponseReviewHistoryRepository formResponseReviewHistoryRepository;

    private Long formId;
    private Long formResponseId;

    @BeforeEach
    void setUp() throws Exception {
        MemberEntity reviewer = saveMember(REVIEWER, "20200001", "김운영", "reviewer@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                reviewer,
                MemberRoleFixture.DIRECTOR);

        QuestionCompositionContent composition =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                reviewer,
                                "2026 신규모집 지원서",
                                composition,
                                null,
                                null,
                                FormStatus.OPEN));
        formId = form.getId();

        MemberEntity respondent =
                saveMember(UUID.randomUUID(), "20260001", "이서연", "applicant@sscc.org");
        formResponseId =
                formResponseHistoryRepository
                        .saveAndFlush(
                                FormResponseHistoryEntity.createSubmitted(
                                        form,
                                        respondent,
                                        ResponseContent.of(Map.of("q1", "잘 부탁드립니다.")),
                                        Instant.now()))
                        .getId();
    }

    @Test
    void reviewIsRolledBackWhenHistorySaveFails() throws Exception {
        given(formResponseReviewHistoryRepository.save(any()))
                .willThrow(new IllegalStateException("검토 이력 저장 실패"));

        mockMvc.perform(
                        post("/v1/forms/" + formId + "/responses/" + formResponseId + "/reviews")
                                .header("Authorization", "Bearer " + REVIEWER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"rspnsSttsCd\": \"REJECTED\","
                                                + " \"rvwOpnnCn\": \"지원 자격을 충족하지 않습니다.\"}"))
                .andExpect(status().isInternalServerError());

        /*
         * 이력이 없으면 상태도 바뀌지 않은 것이어야 한다 — 사유 없는 반려가 남지 않는다.
         * 트랜잭션 밖에서 다시 읽는다.
         */
        assertThat(formResponseHistoryRepository.findById(formResponseId).orElseThrow().getStatus())
                .isEqualTo(ResponseStatus.SUBMITTED);
    }

    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                email);
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
