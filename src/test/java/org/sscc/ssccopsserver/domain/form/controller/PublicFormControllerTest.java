package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * 공개 폼 조회·응답 제출 API(#35) 통합 검증.
 *
 * 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록 대체한다 (FormControllerTest와
 * 같은 방식). 접수 기간 경계와 제출 일시가 주입된 Clock에서 오는지 봐야 하므로 시각도 고정한다 —
 * 시스템 시각을 쓰면 '기간 전·후' 케이스가 달력에 따라 통과와 실패를 오간다.
 *
 * 폼은 API가 아니라 리포지토리로 직접 만든다. DRAFT·CLOSED·기간 밖 표본이 필요한데 그 상태 중
 * 일부는 정상 경로(생성 API + 상태 전이 API)로 만들려면 요청 두세 번이 필요해, 검증하려는 것이
 * 무엇인지가 준비 코드에 묻힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PublicFormControllerTest.StubJwtDecoderConfig.class)
@Transactional
class PublicFormControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    /** 고정 기준 시각 (2026-03-15 00:00 KST). 접수 기간 표본은 이 값을 사이에 두고 앞뒤로 잡는다 */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    /** 위 시각을 서비스 표준 시간대(AP-12)로 표기한 값. 제출 일시 응답이 이 문자열이어야 한다 */
    private static final String NOW_IN_SERVICE_ZONE = "2026-03-15T00:00:00+09:00";

    /*
     * 표본 폼. 정규식(q1)·최대 선택 수(q2)·두 번째 페이지 문항(q3)을 한 폼에 담아 두어야
     * 재검증 규칙을 하나씩 확인할 때마다 폼을 새로 만들지 않아도 된다.
     */
    private static final String SAMPLE_COMPOSITION =
            """
            {
              "pages": [
                {"pageTtl": "기본 정보", "pageDescCn": "지원자 정보를 입력해주세요."},
                {"pageTtl": "상세", "pageDescCn": null}
              ],
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
                  "reqYn": false, "pageSeq": 1, "optionList": []
                }
              ]
            }
            """;

    /*
     * 분기 표본. '아니오'를 고르면 2페이지로 건너뛰므로 1페이지의 필수 문항(qDetail)은
     * 응답자가 보지도 못한다 — 그 문항을 요구하면 이 폼은 어떤 답으로도 제출할 수 없다.
     */
    private static final String BRANCHING_COMPOSITION =
            """
            {
              "pages": [
                {"pageTtl": "선택", "pageDescCn": null},
                {"pageTtl": "상세 (해당자만)", "pageDescCn": null},
                {"pageTtl": "마무리", "pageDescCn": null}
              ],
              "qitems": [
                {
                  "qitemId": "qBranch", "qitemLblNm": "재학 중인가요?",
                  "qitemTypeCd": "SINGLE_CHOICE", "reqYn": true, "pageSeq": 0,
                  "optionList": ["예", "아니오"], "branchMap": {"아니오": 2}
                },
                {
                  "qitemId": "qDetail", "qitemLblNm": "학과", "qitemTypeCd": "SHORT_TEXT",
                  "reqYn": true, "pageSeq": 1, "optionList": []
                },
                {
                  "qitemId": "qLast", "qitemLblNm": "하고 싶은 말", "qitemTypeCd": "SHORT_TEXT",
                  "reqYn": true, "pageSeq": 2, "optionList": []
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
        respondent = saveMember(AUTH_USER_ID, "20260001", "이서연", "actor@sscc.org");
    }

    /* ── 응답자용 폼 조회 ───────────────────────────────────── */

    @Test
    void getPublicFormReturnsQuestionsWhileAccepting() throws Exception {
        Long formId = saveForm("2026 신규모집 지원서", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").value(formId))
                .andExpect(jsonPath("$.data.formTtlNm").value("2026 신규모집 지원서"))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(3))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemId").value("q1"))
                .andExpect(jsonPath("$.data.alreadySubmitted").value(false))
                .andExpect(jsonPath("$.data.submittedAt").value(Matchers.nullValue()));
    }

    /*
     * 응답자용 조회는 운영자용 상세와 스키마를 나눈다. 생성자·응답 집계·폼 상태 내부값은
     * 공개 링크로 나갈 이유가 없다.
     */
    @Test
    void getPublicFormDoesNotExposeOperatorOnlyFields() throws Exception {
        Long formId = saveForm("운영자 필드 확인용", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatrMbrId").doesNotExist())
                .andExpect(jsonPath("$.data.creatrMbrNm").doesNotExist())
                .andExpect(jsonPath("$.data.responseCount").doesNotExist())
                .andExpect(jsonPath("$.data.formSttsCd").doesNotExist());
    }

    /*
     * 이 이슈에서 가장 중요한 한 줄이다 — 아직 열지 않은 폼의 문항이 링크만으로 새어 나가면
     * 안 된다. 상태 코드뿐 아니라 본문에 문항이 실려 있지 않은지도 함께 본다.
     */
    @Test
    void getPublicFormOnDraftFormRejectsWithoutQuestions() throws Exception {
        assertPublicFormRejected(
                saveForm("작성 중인 폼", FormStatus.DRAFT, null, null, SAMPLE_COMPOSITION));
    }

    @Test
    void getPublicFormOnClosedFormRejectsWithoutQuestions() throws Exception {
        assertPublicFormRejected(
                saveForm("마감한 폼", FormStatus.CLOSED, null, null, SAMPLE_COMPOSITION));
    }

    // 상태는 OPEN이지만 시작 일시가 아직 오지 않은 폼. 판정은 FormReceiptPolicy가 시간까지 본다
    @Test
    void getPublicFormBeforeReceiptPeriodRejectsWithoutQuestions() throws Exception {
        assertPublicFormRejected(
                saveForm(
                        "곧 열릴 폼",
                        FormStatus.OPEN,
                        NOW.plusSeconds(86400),
                        NOW.plusSeconds(864000),
                        SAMPLE_COMPOSITION));
    }

    /*
     * 접수 기간이 끝나도 form_stts_cd는 OPEN으로 남는다 (자동 마감 배치를 두지 않기로 한 #33의
     * 결정). 상태만 보면 열려 있는 폼이므로 시간까지 보는 판정을 실제로 태우는지 확인한다.
     */
    @Test
    void getPublicFormAfterReceiptPeriodRejectsWithoutQuestions() throws Exception {
        assertPublicFormRejected(
                saveForm(
                        "기간이 끝난 폼",
                        FormStatus.OPEN,
                        NOW.minusSeconds(864000),
                        NOW.minusSeconds(86400),
                        SAMPLE_COMPOSITION));
    }

    @Test
    void getPublicFormOnUnknownFormReturns404() throws Exception {
        mockMvc.perform(authenticatedGet("/v1/forms/999999/public"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 제출을 마친 응답자에게는 웹이 작성 화면 대신 제출 내역 화면을 띄운다
    @Test
    void getPublicFormReportsAlreadySubmitted() throws Exception {
        Long formId = saveForm("제출 여부 확인용", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadySubmitted").value(true))
                .andExpect(jsonPath("$.data.submittedAt").value(NOW_IN_SERVICE_ZONE));
    }

    /* ── 응답 제출 ─────────────────────────────────────────── */

    /*
     * 응답자·상태·제출 일시는 전부 서버가 채운다. 본문으로 받으면 남의 이름으로 제출하거나
     * 마감 직전 시각을 조작한 응답을 만들 수 있고, 셋 다 사후에 되돌릴 수 없는 값이다.
     */
    @Test
    void submitResponseReturns201WithServerSetFields() throws Exception {
        Long formId = saveForm("제출 성공 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(
                        formId,
                        """
                        {"q1": "홍길동", "q2": ["백엔드", "디자인"], "q3": "잘 부탁드립니다."}
                        """)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.formRspnsId").isNumber())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.sbmsnDt").value(NOW_IN_SERVICE_ZONE));

        FormResponseHistoryEntity saved = onlyResponse();
        assertThat(saved.getMember().getId()).isEqualTo(respondent.getId());
        assertThat(saved.getStatus()).isEqualTo(ResponseStatus.SUBMITTED);
        assertThat(saved.getSubmittedAt()).isEqualTo(NOW);
        assertThat(saved.getContent().answers())
                .containsExactly(
                        Map.entry("q1", "홍길동"),
                        Map.entry("q2", List.of("백엔드", "디자인")),
                        Map.entry("q3", "잘 부탁드립니다."));
    }

    /*
     * 웹은 단일선택도 배열 한 칸에 담아 보낸다(pickChoice). 저장 계약은 문자열이므로 서버가
     * 벗겨 굳혀야 응답 조회·집계가 문항마다 어느 모양인지 따지지 않아도 된다.
     */
    @Test
    void submitResponseUnwrapsSingleChoiceArray() throws Exception {
        Long formId = saveForm("단일선택 폼", FormStatus.OPEN, null, null, BRANCHING_COMPOSITION);

        submit(
                        formId,
                        """
                        {"qBranch": ["아니오"], "qLast": "감사합니다"}
                        """)
                .andExpect(status().isCreated());

        assertThat(onlyResponse().getContent().answers()).containsEntry("qBranch", "아니오");
    }

    /*
     * 빈 값인 key는 저장하지 않는다 (웹 use-public-submit과 같은 규칙). 저장되고 나면
     * "빈 문자열로 답했다"와 "답하지 않았다"를 구별할 방법이 없다.
     */
    @Test
    void submitResponseDoesNotPersistEmptyValues() throws Exception {
        Long formId = saveForm("빈 값 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "홍길동", "q2": [], "q3": ""}
               """)
                .andExpect(status().isCreated());

        assertThat(onlyResponse().getContent().answers()).containsOnlyKeys("q1");
    }

    @Test
    void submitResponseWithoutRequiredAnswerReturns400() throws Exception {
        Long formId = saveForm("필수 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q3": "자기소개만 썼습니다"}
               """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUIRED_ANSWER_MISSING"));

        assertThat(formResponseHistoryRepository.count()).isZero();
    }

    @Test
    void submitResponseWithPatternMismatchReturns400() throws Exception {
        Long formId = saveForm("정규식 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "Hong Gil Dong"}
               """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANSWER_PATTERN_MISMATCH"));
    }

    @Test
    void submitResponseExceedingSelectionLimitReturns400() throws Exception {
        Long formId = saveForm("최대 선택 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(
                        formId,
                        """
                        {"q1": "홍길동", "q2": ["백엔드", "프론트엔드", "디자인"]}
                        """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANSWER_SELECTION_LIMIT_EXCEEDED"));
    }

    /*
     * 폼에 없는 qitemId는 조용히 버리지 않는다 — 문항이 바뀐 뒤 열어 둔 낡은 탭에서 제출됐다는
     * 신호이며, 버리면 그 어긋남이 접수 마감 후 집계에서야 드러난다.
     */
    @Test
    void submitResponseWithUnknownQuestionItemReturns400() throws Exception {
        Long formId = saveForm("낡은 화면 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "홍길동", "qDeleted": "지워진 문항의 답"}
               """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_QUESTION_ITEM"));

        assertThat(formResponseHistoryRepository.count()).isZero();
    }

    // 선택지 목록에 없는 값은 어떤 선택지도 가리키지 않는 답이 되고 분기 목적지까지 틀어진다
    @Test
    void submitResponseWithUnknownOptionReturns400() throws Exception {
        Long formId = saveForm("선택지 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "홍길동", "q2": ["기획"]}
               """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ANSWER_VALUE"));
    }

    @Test
    void submitResponseTwiceReturns409AndKeepsSingleRow() throws Exception {
        Long formId = saveForm("중복 제출 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESPONSE_ALREADY_SUBMITTED"));

        assertThat(formResponseHistoryRepository.count()).isEqualTo(1);
        assertThat(onlyResponse().getContent().answers()).containsEntry("q1", "홍길동");
    }

    // 다른 회원의 응답은 서로를 막지 않는다 — 제약은 (form_id, mbr_id) 쌍이다
    @Test
    void submitResponseByAnotherMemberIsAllowed() throws Exception {
        Long formId = saveForm("2인 제출 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        FormEntity form = formRepository.findById(formId).orElseThrow();
        MemberEntity other = saveMember(UUID.randomUUID(), "20260002", "박민수", "other@sscc.org");
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        form, other, ResponseContent.of(Map.of("q1", "박민수")), NOW));

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        assertThat(formResponseHistoryRepository.count()).isEqualTo(2);
    }

    /*
     * 임시저장(#36)이 만들어 둔 행은 아직 낸 것이 아니다. 409로 막으면 자동 저장을 쓴 응답자는
     * (form_id, mbr_id) UNIQUE 때문에 새 행도 만들 수 없어 영영 제출할 수 없게 된다.
     */
    @Test
    void submitResponseTurnsExistingDraftIntoSubmission() throws Exception {
        Long formId = saveForm("임시저장이 있는 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        FormEntity form = formRepository.findById(formId).orElseThrow();
        Long draftId =
                formResponseHistoryRepository
                        .saveAndFlush(FormResponseHistoryEntity.createDraft(form, respondent, null))
                        .getId();

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formRspnsId").value(draftId))
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("SUBMITTED"));

        assertThat(formResponseHistoryRepository.count()).isEqualTo(1);
        assertThat(onlyResponse().getSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    void submitResponseToDraftFormReturns409() throws Exception {
        Long formId = saveForm("작성 중인 폼", FormStatus.DRAFT, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"));

        assertThat(formResponseHistoryRepository.count()).isZero();
    }

    @Test
    void submitResponseAfterReceiptPeriodReturns409() throws Exception {
        Long formId =
                saveForm(
                        "기간이 끝난 폼",
                        FormStatus.OPEN,
                        NOW.minusSeconds(864000),
                        NOW.minusSeconds(86400),
                        SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"));
    }

    /* ── 분기로 건너뛴 페이지 ───────────────────────────────── */

    /*
     * 이 이슈에서 가장 틀리기 쉬운 규칙이다. '아니오'를 고르면 1페이지를 건너뛰므로 그 페이지의
     * 필수 문항(qDetail)은 응답자가 보지도 못했다. 요구하면 이 폼은 어떤 답으로도 제출할 수 없다.
     */
    @Test
    void submitResponseSkippingBranchedPageOmitsItsRequiredAnswer() throws Exception {
        Long formId = saveForm("분기 폼", FormStatus.OPEN, null, null, BRANCHING_COMPOSITION);

        submit(
                        formId,
                        """
                        {"qBranch": "아니오", "qLast": "감사합니다"}
                        """)
                .andExpect(status().isCreated());

        assertThat(onlyResponse().getContent().answers()).containsOnlyKeys("qBranch", "qLast");
    }

    /*
     * 반대쪽 경로. '예'를 고르면 1페이지를 실제로 지나가므로 그 페이지의 필수 문항은 그대로
     * 필수다 — 건너뛴 페이지를 빼는 규칙이 필수 검사 자체를 무력화하지 않는지 함께 고정한다.
     */
    @Test
    void submitResponseTakingBranchedPageStillRequiresItsAnswer() throws Exception {
        Long formId = saveForm("분기 폼", FormStatus.OPEN, null, null, BRANCHING_COMPOSITION);

        submit(formId, """
               {"qBranch": "예", "qLast": "감사합니다"}
               """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUIRED_ANSWER_MISSING"));
    }

    // 분기 문항 자체에 답하지 않으면 다음 페이지로 그냥 넘어간 것이라 1페이지도 도달한 페이지다
    @Test
    void submitResponseWithoutBranchAnswerFallsThroughToNextPage() throws Exception {
        Long formId = saveForm("분기 폼", FormStatus.OPEN, null, null, BRANCHING_COMPOSITION);

        submit(formId, """
               {"qLast": "감사합니다"}
               """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUIRED_ANSWER_MISSING"));
    }

    /* ── 인증 ─────────────────────────────────────────────── */

    /*
     * '공개'는 누구나 링크를 열 수 있다는 뜻이지 익명으로 낼 수 있다는 뜻이 아니다.
     * 두 경로 모두 SecurityConfig의 permitAll에 걸리지 않아야 한다.
     */
    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        mockMvc.perform(get("/v1/forms/1/public")).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post("/v1/forms/1/responses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rspnsCn\": {}}"))
                .andExpect(status().isUnauthorized());
    }

    /* ── 준비 ─────────────────────────────────────────────── */

    /** 접수 불가 폼의 조회는 상태 코드뿐 아니라 본문에 문항이 없다는 것까지 함께 본다 */
    private void assertPublicFormRejected(Long formId) throws Exception {
        String body =
                mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/public"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("qitemCpstCn").doesNotContain("q1").doesNotContain("이름");
    }

    private ResultActions submit(Long formId, String answers) throws Exception {
        return mockMvc.perform(
                authenticatedPost(
                        "/v1/forms/" + formId + "/responses", "{\"rspnsCn\": " + answers + "}"));
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
            String title,
            FormStatus status,
            Instant receiptBeginAt,
            Instant receiptEndAt,
            String composition)
            throws Exception {

        QuestionCompositionContent content =
                objectMapper.readValue(composition, QuestionCompositionContent.class);
        return formRepository
                .saveAndFlush(
                        FormEntity.create(
                                respondent, title, content, receiptBeginAt, receiptEndAt, status))
                .getId();
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

    private MockHttpServletRequestBuilder authenticatedGet(String path) {
        return get(path).header("Authorization", "Bearer any-token");
    }

    private MockHttpServletRequestBuilder authenticatedPost(String path, String body) {
        return post(path)
                .header("Authorization", "Bearer any-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
