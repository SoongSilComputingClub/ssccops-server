package org.sscc.ssccopsserver.domain.form.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * 첫 저장이 동시에 도착했을 때의 결과만 확인한다 (#36).
 *
 * 자동 저장은 타이핑마다 도는 요청이라 두 탭이 열려 있거나 디바운스가 겹치면 첫 저장 두 건이
 * 동시에 도착할 수 있다. 둘 다 선조회에서 "행이 없다"를 보고 INSERT를 시도하면 하나는 반드시
 * (form_id, mbr_id) UNIQUE에 걸리는데, 그때 500이 나가면 웹은 서버가 고장난 것으로 읽는다.
 *
 * 실제 스레드를 두 개 띄우는 대신 리포지토리를 대체해 제약 위반만 재현한다 — 확인 대상은 경합의
 * 타이밍이 아니라 "제약 위반이 무엇으로 번역되는가" 한 가지이고, 스레드로 재현하면 타이밍에 따라
 * 통과와 실패를 오가는 테스트가 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FormResponseDraftConflictTest.StubJwtDecoderConfig.class)
@Transactional
class FormResponseDraftConflictTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    private static final String SAMPLE_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기본 정보", "pageDescCn": null}],
              "qitems": [
                {
                  "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
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
    @Autowired private FormRepository formRepository;

    @MockitoBean private FormResponseHistoryRepository formResponseHistoryRepository;

    /*
     * 이미 제출한 것이 아니라 같은 사람의 다른 임시저장과 부딪혔을 수 있으므로
     * RESPONSE_ALREADY_SUBMITTED가 아니라 RESPONSE_SAVE_CONFLICT다 — 잠깐 뒤 다시 보내면 된다는 뜻이다.
     */
    @Test
    void concurrentFirstSaveReturns409InsteadOf500() throws Exception {
        MemberEntity respondent =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        AUTH_USER_ID,
                        "20260001",
                        "이서연",
                        "actor@sscc.org");
        QuestionCompositionContent composition =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        Long formId =
                formRepository
                        .saveAndFlush(
                                FormEntity.create(
                                        respondent,
                                        "동시 저장 폼",
                                        composition,
                                        null,
                                        null,
                                        FormStatus.OPEN))
                        .getId();

        // 선조회는 통과시키고(행이 없다) 저장에서만 제약 위반을 일으킨다 — 경합의 결과가 이 모양이다
        given(formResponseHistoryRepository.findByFormAndMember(any(), any()))
                .willReturn(Optional.empty());
        given(formResponseHistoryRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uk_form_rspns_hstry_form_member"));

        mockMvc.perform(
                        put("/v1/forms/" + formId + "/responses/draft")
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                         {"rspnsCn": {"q1": "홍길동"}}
                                         """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESPONSE_SAVE_CONFLICT"));
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(AUTH_USER_ID.toString())
                            .claim("email", "actor@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
