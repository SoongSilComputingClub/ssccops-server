package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
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
 * 응답 자동 저장(DRAFT) API(#36) 통합 검증.
 *
 * 이 이슈에서 지켜야 할 것은 두 가지다. 하나는 "작성 중에는 검증하지 않는다" — 필수 누락·형식
 * 불일치·최대 선택 초과가 저장을 막지 않아야 자동 저장이 쓸모가 있다. 다른 하나는 "DRAFT는
 * 집계에 잡히지 않는다" — 목록의 응답 건수가 3인데 실제 응답은 1건인 상태를 만들지 않는 것이다.
 *
 * JwtDecoder를 대체하되 토큰 문자열을 그대로 subject로 쓴다. 회원마다 토큰을 따로 만들 수 있어야
 * "남의 작성 중 응답은 보이지 않는다"를 실제 요청으로 확인할 수 있기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FormResponseDraftControllerTest.StubJwtDecoderConfig.class)
@Transactional
class FormResponseDraftControllerTest {

    /** 고정 기준 시각 (2026-03-15 00:00 KST). 접수 기간 표본은 이 값을 사이에 두고 앞뒤로 잡는다 */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    /** 위 시각을 서비스 표준 시간대(AP-12)로 표기한 값. 제출 일시 응답이 이 문자열이어야 한다 */
    private static final String NOW_IN_SERVICE_ZONE = "2026-03-15T00:00:00+09:00";

    /*
     * 표본 폼. 필수(q1)·정규식(q1)·최대 선택 수(q2)를 한 폼에 담아 두면 "자동 저장은 이 셋을
     * 보지 않는다"를 요청 한 번으로 확인할 수 있다.
     */
    private static final String SAMPLE_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기본 정보", "pageDescCn": null}],
              "qitems": [
                {
                  "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                  "reqYn": true, "pageSeq": 0, "optionList": [],
                  "ptrnCn": "^[가-힣]{2,5}$", "ptrnNm": "한글 이름", "ptrnMsgCn": "한글 2~5자"
                },
                {
                  "qitemId": "q2", "qitemLblNm": "관심 분야", "qitemTypeCd": "MULTI_CHOICE",
                  "reqYn": false, "pageSeq": 0,
                  "optionList": ["백엔드", "프론트엔드", "디자인"], "maxSlctCnt": 2
                },
                {
                  "qitemId": "q3", "qitemLblNm": "자기소개", "qitemTypeCd": "LONG_TEXT",
                  "reqYn": false, "pageSeq": 0, "optionList": []
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
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    private MemberEntity respondent;

    @BeforeEach
    void setUp() {
        respondent = saveMember("20260001", "이서연", "actor@sscc.org");
    }

    /* ── 저장(upsert) ──────────────────────────────────────── */

    /*
     * 첫 저장은 DRAFT 행을 만든다. 제출 일시가 NULL인 것이 이 상태의 정의다 — 값이 채워져 있으면
     * "낸 적 없는데 제출된" 행이 되어 마감 후 집계가 거짓말을 한다.
     */
    @Test
    void firstSaveCreatesDraftRowWithoutSubmittedAt() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, """
                  {"q1": "홍길동"}
                  """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formRspnsId").isNumber())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("홍길동"))
                .andExpect(jsonPath("$.data.mdfcnDt").isNotEmpty());

        FormResponseHistoryEntity saved = onlyResponse();
        assertThat(saved.getStatus()).isEqualTo(ResponseStatus.DRAFT);
        assertThat(saved.getSubmittedAt()).isNull();
        assertThat(saved.getMember().getId()).isEqualTo(respondent.getId());
    }

    /*
     * 두 번째 저장은 같은 행을 갈아 끼운다. 자동 저장은 타이핑마다 도는 요청이라, 여기서 행이
     * 하나씩 늘면 응답 한 건이 수백 행이 되고 (form_id, mbr_id) UNIQUE도 곧바로 깨진다.
     */
    @Test
    void secondSaveUpdatesTheSameRow() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        Long firstId =
                savedDraftId(
                        formId,
                        """
                                    {"q1": "홍"}
                                    """);
        Long secondId =
                savedDraftId(
                        formId,
                        """
                        {"q1": "홍길동", "q3": "잘 부탁드립니다."}
                        """);

        assertThat(secondId).isEqualTo(firstId);
        assertThat(formResponseHistoryRepository.count()).isEqualTo(1);
        assertThat(onlyResponse().getContent().answers())
                .containsExactly(Map.entry("q1", "홍길동"), Map.entry("q3", "잘 부탁드립니다."));
    }

    /*
     * PUT은 부분 갱신이 아니라 통째로 대체다. 웹은 작성 중인 폼 상태 전체를 들고 있다가 그대로
     * 보내므로, 본문에 없는 답은 "안 바뀐 것"이 아니라 "지운 것"이어야 화면과 저장된 값이 같다.
     */
    @Test
    void saveReplacesPreviousAnswersEntirely() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, """
                  {"q1": "홍길동", "q3": "지울 내용"}
                  """)
                .andExpect(status().isOk());
        saveDraft(formId, """
                  {"q1": "홍길동"}
                  """)
                .andExpect(status().isOk());

        assertThat(onlyResponse().getContent().answers()).containsOnlyKeys("q1");
    }

    // 저장 형태는 제출과 같은 규칙을 쓴다 — 빈 값인 key는 빼고 단일선택 배열은 벗긴다
    @Test
    void saveDropsEmptyValues() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(
                        formId,
                        """
                  {"q1": "홍길동", "q2": [], "q3": ""}
                  """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsCn.q2").doesNotExist())
                .andExpect(jsonPath("$.data.rspnsCn.q3").doesNotExist());

        assertThat(onlyResponse().getContent().answers()).containsOnlyKeys("q1");
    }

    // 아직 아무것도 치지 않은 시점에도 저장할 자리가 있어야 웹이 '마지막 저장 시각'을 표시한다
    @Test
    void saveWithEmptyAnswersIsAllowed() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("DRAFT"));

        assertThat(onlyResponse().getContent().answers()).isEmpty();
    }

    /* ── 자동 저장은 검증하지 않는다 ────────────────────────── */

    /*
     * 이 이슈의 핵심 규칙이다. 필수 문항이 비어 있는 것은 작성 중에 당연한 상태이고, 그 상태를
     * 거절하면 응답자는 폼을 다 채우기 전까지 아무것도 저장하지 못한다 — 자동 저장이 있으나 마나다.
     */
    @Test
    void saveDoesNotRejectMissingRequiredAnswer() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, """
                  {"q3": "이름은 아직 안 썼습니다"}
                  """)
                .andExpect(status().isOk());

        assertThat(onlyResponse().getContent().answers()).containsOnlyKeys("q3");
    }

    // 형식(정규식)은 다 쓴 답에나 맞는다 — "홍"까지 친 순간 저장이 멈추면 안 된다
    @Test
    void saveDoesNotRejectPatternMismatch() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, """
                  {"q1": "Hong Gil Dong"}
                  """)
                .andExpect(status().isOk());

        assertThat(onlyResponse().getContent().answers()).containsEntry("q1", "Hong Gil Dong");
    }

    // 셋 중 둘을 고르라는 문항에서 셋을 고른 뒤 하나를 지우는 순서는 흔하다. 그 중간 상태도 저장된다
    @Test
    void saveDoesNotRejectSelectionOverflow() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(
                        formId,
                        """
                        {"q2": ["백엔드", "프론트엔드", "디자인"]}
                        """)
                .andExpect(status().isOk());

        assertThat(onlyResponse().getContent().answers())
                .containsEntry("q2", List.of("백엔드", "프론트엔드", "디자인"));
    }

    /*
     * 검증하지 않는다는 것이 무엇이든 받는다는 뜻은 아니다. 폼에 없는 qitemId는 자동 저장에서도
     * 거절한다 — 자동 저장은 반복 호출이라 한 번 통과시키면 그 key가 매 저장마다 다시 실려 오며
     * 지울 계기가 없다.
     */
    @Test
    void saveWithUnknownQuestionItemReturns400() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(
                        formId,
                        """
                  {"q1": "홍길동", "qDeleted": "지워진 문항의 답"}
                  """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_QUESTION_ITEM"));

        assertThat(formResponseHistoryRepository.count()).isZero();
    }

    // 저장 형태가 깨진 행은 복원도 제출도 되지 않으므로 모양은 자동 저장에서도 본다
    @Test
    void saveWithWrongShapeReturns400() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, """
                  {"q2": "백엔드"}
                  """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ANSWER_VALUE"));
    }

    /*
     * 본문 크기 상한. 자동 저장은 타이핑마다 같은 본문을 통째로 다시 보내므로, 상한이 없으면
     * 붙여넣기 한 번이 행 하나를 무한정 키운다.
     */
    @Test
    void saveWithOversizedContentReturns413() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, "{\"q3\": \"" + "가".repeat(100_001) + "\"}")
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("RESPONSE_CONTENT_TOO_LARGE"));

        assertThat(formResponseHistoryRepository.count()).isZero();
    }

    /* ── 복원 ──────────────────────────────────────────────── */

    @Test
    void getRestoresSavedDraft() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);
        saveDraft(
                        formId,
                        """
                        {"q1": "홍길동", "q2": ["백엔드"], "q3": "이어서 쓰겠습니다"}
                        """)
                .andExpect(status().isOk());

        getDraft(formId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("홍길동"))
                .andExpect(jsonPath("$.data.rspnsCn.q2[0]").value("백엔드"))
                .andExpect(jsonPath("$.data.rspnsCn.q3").value("이어서 쓰겠습니다"));
    }

    /*
     * 작성 중인 것이 없으면 204가 아니라 data가 null인 200이다. 봉투(ApiResponse)를 쓰는 다른
     * 조회와 같은 모양이어야 웹의 공통 응답 처리가 이 엔드포인트만 예외로 다루지 않는다.
     */
    @Test
    void getWithoutDraftReturnsNullData() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);

        getDraft(formId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    // 제출을 마친 응답은 작성 중이 아니다. 오류가 아니라 '작성 중인 것이 없다'로 답한다
    @Test
    void getAfterSubmitReturnsNullData() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        getDraft(formId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    /*
     * 대상은 언제나 인증 주체 본인이다. 경로에 mbrId가 없으니 남의 초안을 지목할 방법 자체가
     * 없지만, 같은 폼·다른 회원의 행이 섞여 나오지 않는지는 실제 요청으로 고정해 둔다.
     */
    @Test
    void getDoesNotReturnAnotherMembersDraft() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);
        MemberEntity other = saveMember("20260002", "박민수", "other@sscc.org");
        mockMvc.perform(
                        put("/v1/forms/" + formId + "/responses/draft")
                                .header("Authorization", "Bearer " + other.getAuthUserId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                         {"rspnsCn": {"q1": "박민수"}}
                                         """))
                .andExpect(status().isOk());

        getDraft(formId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    /* ── 제출로의 전환 ─────────────────────────────────────── */

    /*
     * 제출은 새 행을 만드는 것이 아니라 같은 행의 상태를 바꾸는 일이다(#35). 두 행으로 갈리면
     * 한 사람의 응답이 DRAFT 1건 + SUBMITTED 1건이 되어 어느 쪽이 진짜인지 알 수 없다.
     */
    @Test
    void submitTurnsTheDraftRowIntoSubmission() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);
        Long draftId =
                savedDraftId(
                        formId,
                        """
                                     {"q1": "홍"}
                                     """);

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formRspnsId").value(draftId))
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.sbmsnDt").value(NOW_IN_SERVICE_ZONE));

        assertThat(formResponseHistoryRepository.count()).isEqualTo(1);
        FormResponseHistoryEntity saved = onlyResponse();
        assertThat(saved.getStatus()).isEqualTo(ResponseStatus.SUBMITTED);
        assertThat(saved.getSubmittedAt()).isEqualTo(NOW);
        assertThat(saved.getContent().answers()).containsEntry("q1", "홍길동");
    }

    /*
     * 제출 뒤에도 자동 저장이 통하면 운영진이 심사한 내용과 응답자가 들고 있는 화면이 소리 없이
     * 갈라진다. 수정 제출은 응답 상태 변경(#37)이 정할 규칙이지 자동 저장이 열 문이 아니다.
     */
    @Test
    void saveAfterSubmitReturns409() throws Exception {
        Long formId = saveForm("자동 저장 폼", FormStatus.OPEN, null, null);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        saveDraft(formId, """
                  {"q1": "김철수"}
                  """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESPONSE_ALREADY_SUBMITTED"));

        assertThat(onlyResponse().getContent().answers()).containsEntry("q1", "홍길동");
    }

    /* ── 접수 가능 여부 ────────────────────────────────────── */

    // 판정은 FormReceiptPolicy 하나가 한다 (#33) — 자동 저장이 그 판정을 다시 옮겨 적지 않는다
    @Test
    void saveOnDraftFormReturns409() throws Exception {
        Long formId = saveForm("작성 중인 폼", FormStatus.DRAFT, null, null);

        saveDraft(formId, """
                  {"q1": "홍길동"}
                  """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"));

        assertThat(formResponseHistoryRepository.count()).isZero();
    }

    /*
     * 접수 기간이 끝나도 form_stts_cd는 OPEN으로 남는다 (자동 마감 배치를 두지 않기로 한 #33).
     * 상태만 보는 판정이었다면 마감된 폼에 자동 저장이 계속 들어온다.
     */
    @Test
    void saveAfterReceiptPeriodReturns409() throws Exception {
        Long formId =
                saveForm(
                        "기간이 끝난 폼",
                        FormStatus.OPEN,
                        NOW.minusSeconds(864000),
                        NOW.minusSeconds(86400));

        saveDraft(formId, """
                  {"q1": "홍길동"}
                  """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"));
    }

    /*
     * 조회도 같은 판정을 쓴다. 한쪽은 "지금은 쓸 수 없는 폼"이라 하고 다른 쪽은 작성 중인 내용을
     * 돌려주면, 화면은 복원은 되지만 제출은 되지 않는 상태에 놓인다.
     */
    @Test
    void getOnNonAcceptingFormReturns409() throws Exception {
        Long formId = saveForm("마감한 폼", FormStatus.CLOSED, null, null);

        getDraft(formId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"));
    }

    @Test
    void draftRequestsOnUnknownFormReturn404() throws Exception {
        getDraft(999999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        saveDraft(999999L, """
                  {"q1": "홍길동"}
                  """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 집계 (놓치기 쉬운 지점) ───────────────────────────── */

    /*
     * 이 이슈에서 가장 놓치기 쉬운 회귀다. DRAFT가 응답 건수에 잡히면 운영진은 "응답 3건"을 보고
     * 목록을 열었는데 1건만 있는 상태를 만나게 되고, 그 어긋남은 마감 판단까지 흔든다.
     *
     * 집계 기준은 #32가 이미 그렇게 정해 두었지만, 그때는 DRAFT 행을 만들 경로가 없었다.
     * 자동 저장이 그 경로를 여는 지금 실제 API로 만든 DRAFT를 가지고 다시 고정한다.
     */
    @Test
    void draftIsNotCountedInFormResponseCounts() throws Exception {
        Long formId = saveForm("집계 확인 폼", FormStatus.OPEN, null, null);

        saveDraft(formId, """
                  {"q1": "홍길동"}
                  """)
                .andExpect(status().isOk());

        assertResponseCount(formId, 0);

        // 같은 행이 제출로 바뀌는 순간 비로소 세어진다 — 행이 늘어서가 아니라 상태가 바뀌어서다
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        assertResponseCount(formId, 1);
        assertThat(formResponseHistoryRepository.count()).isEqualTo(1);
    }

    /* ── 인증 ─────────────────────────────────────────────── */

    // 자동 저장은 응답자 본인의 개인정보를 다루는 경로다. permitAll에 걸리면 안 된다
    @Test
    void draftRequestsWithoutTokenReturn401() throws Exception {
        mockMvc.perform(get("/v1/forms/1/responses/draft")).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        put("/v1/forms/1/responses/draft")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rspnsCn\": {}}"))
                .andExpect(status().isUnauthorized());
    }

    /* ── 준비 ─────────────────────────────────────────────── */

    /** 목록과 상세가 같은 건수를 말하는지 함께 본다 — 둘이 갈리는 것이 이 회귀의 증상이다 */
    private void assertResponseCount(Long formId, int expected) throws Exception {
        mockMvc.perform(authorized(get("/v1/forms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].responseCount").value(expected));
        mockMvc.perform(authorized(get("/v1/forms/" + formId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseCount").value(expected));
    }

    private ResultActions getDraft(Long formId) throws Exception {
        return mockMvc.perform(authorized(get("/v1/forms/" + formId + "/responses/draft")));
    }

    private ResultActions saveDraft(Long formId, String answers) throws Exception {
        return mockMvc.perform(
                authorized(
                        put("/v1/forms/" + formId + "/responses/draft")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rspnsCn\": " + answers + "}")));
    }

    private ResultActions submit(Long formId, String answers) throws Exception {
        return mockMvc.perform(
                authorized(
                        post("/v1/forms/" + formId + "/responses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rspnsCn\": " + answers + "}")));
    }

    /** 저장 응답에서 행 식별자만 꺼낸다. 두 번째 저장이 같은 행을 갈았는지 비교하는 데 쓴다 */
    private Long savedDraftId(Long formId, String answers) throws Exception {
        return objectMapper
                .readTree(
                        saveDraft(formId, answers)
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("data")
                .path("formRspnsId")
                .asLong();
    }

    private FormResponseHistoryEntity onlyResponse() {
        List<FormResponseHistoryEntity> responses =
                formResponseHistoryRepository.findAll().stream()
                        .filter(response -> response.getMember().getId().equals(respondent.getId()))
                        .toList();
        assertThat(responses).hasSize(1);
        return responses.get(0);
    }

    private Long saveForm(
            String title, FormStatus status, Instant receiptBeginAt, Instant receiptEndAt)
            throws Exception {

        QuestionCompositionContent content =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        return formRepository
                .saveAndFlush(
                        FormEntity.create(
                                respondent, title, content, receiptBeginAt, receiptEndAt, status))
                .getId();
    }

    private MemberEntity saveMember(String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                name,
                email);
    }

    /** 요청 주체는 기본적으로 setUp의 응답자다. 다른 회원으로 보내야 할 때만 토큰을 직접 만든다 */
    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + respondent.getAuthUserId());
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        /*
         * 접수 기간 경계와 제출 일시가 주입된 Clock에서 오는지 확인해야 하므로 시각을 고정한다.
         * ClockConfig가 정의한 clock 빈과 이름이 겹치지 않게 다른 이름으로 둔다 (FormControllerTest 선례).
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }

        /*
         * 토큰 문자열을 그대로 subject로 쓴다. 회원마다 토큰을 따로 만들 수 있어야 "남의 작성 중
         * 응답은 보이지 않는다"를 실제 요청으로 확인할 수 있다.
         */
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
