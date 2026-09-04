package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hamcrest.Matchers;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormQuestionHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormQuestionHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.FormService;
import org.sscc.ssccopsserver.domain.form.service.SystemFormContract;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 폼 API(#32) 통합 검증. 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록
 * 대체한다 — WorkControllerTest와 같은 방식이며, SecurityMockMvcRequestPostProcessors.jwt()는
 * 커스텀 컨버터(@CurrentMember가 기대는 AuthenticatedUser)를 우회하므로 쓰지 않는다.
 *
 * 문항 구성의 규칙별 검증은 QuestionCompositionValidatorTest가 다루고, 여기서는 그 위반이
 * 실제로 400 INVALID_QUESTION_COMPOSITION으로 나가는지와 나머지 계약을 본다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, FormControllerTest.FormTestConfig.class})
@Transactional
class FormControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    /** 고정 기준 시각 (2026-03-15 KST). 접수 기간 표본은 이 값을 사이에 두고 앞뒤로 잡는다 */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    /** 문항이 하나도 없는 구성. 저장은 통과하지만 접수 시작은 막혀야 한다 */
    private static final String EMPTY_COMPOSITION =
            """
            {"pages": [{"pageTtl": "한 장", "pageDescCn": null}], "qitems": []}
            """;

    /*
     * 두 페이지·두 문항짜리 표본. 정규식·선택지·분기를 한 번에 담아 두어야 저장 왕복에서
     * 유형별 속성이 살아 있는지 한 요청으로 볼 수 있다.
     */
    private static final String VALID_COMPOSITION =
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
                  "qitemId": "q2", "qitemLblNm": "지원 분야", "qitemTypeCd": "SINGLE_CHOICE",
                  "reqYn": true, "pageSeq": 0, "optionList": ["백엔드", "프론트엔드"],
                  "branchMap": {"백엔드": 1, "프론트엔드": 1}
                }
              ]
            }
            """;

    /*
     * 시험용 시스템 폼 코드 (#140). 실제 선언(SystemFormContract.DECLARED)에는 PROPOSAL이 들어
     * 있지만(#173) 그 계약으로 여기를 검증하지는 않는다 — 시드가 문항을 바꿀 때마다 잠금 배선
     * 테스트가 함께 흔들리고, 그러면 이 테스트가 확인하는 것이 '배선'인지 '기획안 폼의 문항'인지
     * 갈린다. 그래서 계약을 갈아 끼워(StubJwtDecoderConfig) 컨트롤러 → 서비스 → 엔티티 배선까지
     * 실제 요청으로 확인한다. 판정 자체는 FormSystemLockTest가 엔티티 단위로 본다.
     */
    private static final String SYSTEM_FORM_CODE = "TEST_SYSTEM_FORM";

    /*
     * 계약 문항이 둘인 두 번째 시험용 코드 (#155). 하나만 두면 "응답의 목록이 계약 선언을 따라가는가"가
     * 상수 하나와 구별되지 않는다 — 선언이 다른 코드를 다는 순간 응답도 달라져야 값이 계약에서 온다고 말할 수 있다.
     * 정렬 순서도 여기서 확인한다(Set은 순회 순서를 보장하지 않는다).
     */
    private static final String TWO_QUESTION_SYSTEM_FORM_CODE = "TEST_SYSTEM_FORM_TWO";

    /** 계약 문항(q1)을 지운 구성. 시스템 폼에서는 400, 평범한 폼에서는 통과해야 한다 */
    private static final String COMPOSITION_WITHOUT_CONTRACT_QUESTION =
            """
            {
              "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
              "qitems": [
                {
                  "qitemId": "q2", "qitemLblNm": "지원 분야", "qitemTypeCd": "SINGLE_CHOICE",
                  "reqYn": true, "pageSeq": 0, "optionList": ["백엔드", "프론트엔드"]
                }
              ]
            }
            """;

    /** 계약 문항(q1)은 남기되 문구를 고치고 문항을 더하고 순서를 바꾼 구성. 전부 허용돼야 한다 */
    private static final String CONTRACT_KEPT_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
              "qitems": [
                {
                  "qitemId": "q3", "qitemLblNm": "새로 더한 문항", "qitemTypeCd": "LONG_TEXT",
                  "reqYn": false, "pageSeq": 0, "optionList": []
                },
                {
                  "qitemId": "q1", "qitemLblNm": "다시 쓴 이름 문항", "qitemTypeCd": "SHORT_TEXT",
                  "reqYn": true, "pageSeq": 0, "optionList": []
                }
              ]
            }
            """;

    @PersistenceContext private EntityManager entityManager;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormLabelRepository formLabelRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FormQuestionHistoryRepository formQuestionHistoryRepository;
    @Autowired private FormService formService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;

    private Long actorId;

    @BeforeEach
    void setUp() {
        MemberEntity actor = saveMember(AUTH_USER_ID, "20260001", "이서연", "actor@sscc.org");
        actorId = actor.getId();

        // 폼 API는 FORM_READ·FORM_WRITE·FORM_STATUS_CHANGE를 요구한다 (#9). 국장(OPERATOR)이 셋 다에 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                actor,
                MemberRoleFixture.DIRECTOR);
    }

    /* ── 생성 ─────────────────────────────────────────────── */

    /*
     * 생성자는 요청 본문이 아니라 인증 주체에서 온다. 상태를 지정하지 않았으므로 DRAFT다 —
     * 만들자마자 공개되는 폼이 실수로 생기지 않게 하는 기본값이다.
     */
    @Test
    void createFormReturns201WithCreatorFromTokenAndDraftStatus() throws Exception {
        String body = saveBody("2026 신규모집 지원서", null, null, null, "[]");

        String response =
                mockMvc.perform(authenticatedPost("/v1/forms", body))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.formId").isNumber())
                        .andExpect(jsonPath("$.data.formTtlNm").value("2026 신규모집 지원서"))
                        .andExpect(jsonPath("$.data.formSttsCd").value("DRAFT"))
                        .andExpect(jsonPath("$.data.labels").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Long formId = JsonPath.parse(response).read("$.data.formId", Long.class);
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.creatrMbrId").value(actorId))
                .andExpect(jsonPath("$.data.creatrMbrNm").value("이서연"));
    }

    // 편집 화면의 '바로 접수 시작'이 보내는 상태다. 만들자마자 OPEN인 폼이 나와야 한다
    @Test
    void createFormAcceptsOpenStatus() throws Exception {
        String body =
                saveBody(
                        "바로 접수 시작 폼",
                        "OPEN",
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[]");

        mockMvc.perform(authenticatedPost("/v1/forms", body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data.rcptBgngDt").value("2026-03-01T00:00:00+09:00"));
    }

    @Test
    void createFormWithoutTitleReturnsValidationFailed() throws Exception {
        String body =
                """
                {"formTtlNm": "", "qitemCpstCn": %s}
                """
                        .formatted(VALID_COMPOSITION);

        mockMvc.perform(authenticatedPost("/v1/forms", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 계약표가 이 조건에 전용 코드를 요구하므로 VALIDATION_FAILED로 뭉뚱그리지 않는다
    @Test
    void createFormWithInvertedReceiptPeriodReturnsInvalidReceiptPeriod() throws Exception {
        String body =
                saveBody(
                        "기간 역전 폼",
                        null,
                        "2026-03-31T00:00:00+09:00",
                        "2026-03-01T00:00:00+09:00",
                        "[]");

        mockMvc.perform(authenticatedPost("/v1/forms", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECEIPT_PERIOD"));
    }

    @Test
    void createFormWithPageSequenceOutOfRangeReturnsInvalidComposition() throws Exception {
        assertCompositionRejected(
                """
                {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                    "reqYn": true, "pageSeq": 3, "optionList": []
                  }]
                }
                """);
    }

    @Test
    void createFormWithBranchToUnknownPageReturnsInvalidComposition() throws Exception {
        assertCompositionRejected(
                """
                {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "q1", "qitemLblNm": "분야", "qitemTypeCd": "SINGLE_CHOICE",
                    "reqYn": true, "pageSeq": 0, "optionList": ["가", "나"],
                    "branchMap": {"가": 5}
                  }]
                }
                """);
    }

    @Test
    void createFormWithBrokenPatternReturnsInvalidComposition() throws Exception {
        assertCompositionRejected(
                """
                {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                    "reqYn": true, "pageSeq": 0, "optionList": [], "ptrnCn": "^[가-힣"
                  }]
                }
                """);
    }

    @Test
    void createFormWithChoiceButNoOptionsReturnsInvalidComposition() throws Exception {
        assertCompositionRejected(
                """
                {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "q1", "qitemLblNm": "분야", "qitemTypeCd": "SINGLE_CHOICE",
                    "reqYn": true, "pageSeq": 0, "optionList": []
                  }]
                }
                """);
    }

    @Test
    void createFormWithMaxSelectCountOverflowReturnsInvalidComposition() throws Exception {
        assertCompositionRejected(
                """
                {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "q1", "qitemLblNm": "스택", "qitemTypeCd": "MULTI_CHOICE",
                    "reqYn": false, "pageSeq": 0, "optionList": ["가", "나"], "maxSlctCnt": 5
                  }]
                }
                """);
    }

    private void assertCompositionRejected(String composition) throws Exception {
        String body =
                """
                {"formTtlNm": "규칙 위반 폼", "qitemCpstCn": %s}
                """
                        .formatted(composition);

        mockMvc.perform(authenticatedPost("/v1/forms", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUESTION_COMPOSITION"));
    }

    /* ── 목록 ─────────────────────────────────────────────── */

    /*
     * 필터 네 조합을 한 테스트에서 확인한다. 조합마다 테스트를 나누면 같은 세 폼을 세 번
     * 만들게 되고, 정작 확인하려는 것("둘 다 주면 AND")은 조합 간 비교라 한 자리에 있어야 한다.
     */
    @Test
    void getFormsAppliesStatusAndLabelFiltersWithAnd() throws Exception {
        Long recruitLabelId = saveLabel("신규모집").getId();
        Long eventLabelId = saveLabel("행사").getId();

        Long openRecruit = createForm("접수 중 신규모집", "OPEN", "[" + recruitLabelId + "]");
        Long draftRecruit = createForm("작성 중 신규모집", null, "[" + recruitLabelId + "]");
        Long openEvent = createForm("접수 중 행사", "OPEN", "[" + eventLabelId + "]");

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(authenticatedGet("/v1/forms?statusCode=OPEN"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(
                        jsonPath(
                                "$.data[*].formId",
                                Matchers.hasItems(openEvent.intValue(), openRecruit.intValue())));

        mockMvc.perform(authenticatedGet("/v1/forms?labelId=" + recruitLabelId))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(
                        jsonPath(
                                "$.data[*].formId",
                                Matchers.hasItems(
                                        draftRecruit.intValue(), openRecruit.intValue())));

        mockMvc.perform(authenticatedGet("/v1/forms?statusCode=OPEN&labelId=" + recruitLabelId))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formId").value(openRecruit))
                .andExpect(jsonPath("$.data[0].labels[0].lblNm").value("신규모집"));
    }

    /*
     * 목록은 문항 구성을 싣지 않는다 — 폼 하나에 문항이 수십 개면 목록 응답이 그만큼 곱해진다.
     * 상세에는 그대로 실려야 하므로 두 응답을 같이 본다.
     */
    @Test
    void getFormsOmitsQuestionCompositionButDetailKeepsIt() throws Exception {
        Long formId = createForm("문항 많은 폼", null, "[]");

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(jsonPath("$.data[0].qitemCpstCn").doesNotExist());

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.qitemCpstCn.pages.length()").value(2))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].ptrnCn").value("^[가-힣]{2,5}$"))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[1].branchMap.백엔드").value(1));
    }

    /*
     * 문항 0개 DRAFT 폼(승인 이관이 붙이는 빈 모집 폼)도 qitemCpstCn.pages를 항상 싣는다 (#186).
     * pages를 NULL로 두면 @JsonInclude(NON_NULL)이 키를 통째로 빼, 편집기가 pages.map(...)에서
     * TypeError로 죽는다.
     */
    @Test
    void getFormDetailForEmptyDraftAlwaysIncludesPagesKey() throws Exception {
        Long formId =
                formService
                        .createEmptyDraft(
                                "빈 모집 폼", memberRepository.findById(actorId).orElseThrow())
                        .getId();

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qitemCpstCn.pages").isArray())
                .andExpect(jsonPath("$.data.qitemCpstCn.pages.length()").value(1))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems").isArray())
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems").isEmpty());
    }

    /*
     * 작성 중(DRAFT) 응답은 아직 응답자가 낸 것이 아니다. 세면 운영진이 보는 접수 건수가
     * 부풀어 마감 판단이 어긋난다.
     */
    @Test
    void getFormsCountsSubmittedResponsesOnly() throws Exception {
        Long formId = createForm("응답 집계 폼", "OPEN", "[]");
        FormEntity form = formRepository.findById(formId).orElseThrow();

        saveResponse(form, "20260011", ResponseStatus.SUBMITTED);
        saveResponse(form, "20260012", ResponseStatus.ACCEPTED);
        saveResponse(form, "20260013", ResponseStatus.REJECTED);
        saveResponse(form, "20260014", ResponseStatus.DRAFT);

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(jsonPath("$.data[0].responseCount").value(3));
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.responseCount").value(3));
    }

    /*
     * 폼 상세의 응답 요약 (#37). 상세 화면은 '전체 · 제출 · 승인 · 반려' 네 숫자를 보여주는데,
     * 서버가 responseCount 한 숫자만 내려주던 동안 웹은 res.responseSummary를 찾지 못해 요약
     * 상자를 늘 0으로 그렸다 — 터지지 않고 조용히 틀리는 종류라 화면만 보고는 알 수 없었다.
     *
     * 네 숫자가 서로 다른 값이 되도록 상태별 건수를 다르게 넣는다. 같은 수로 두면 집계가 상태를
     * 실제로 갈라 세는지, 아니면 같은 값을 네 번 실은 것인지 구별되지 않는다.
     */
    @Test
    void getFormDetailBreaksResponseCountDownByStatus() throws Exception {
        Long formId = createForm("응답 요약 폼", "OPEN", "[]");
        FormEntity form = formRepository.findById(formId).orElseThrow();

        saveResponse(form, "20260021", ResponseStatus.SUBMITTED);
        saveResponse(form, "20260022", ResponseStatus.ACCEPTED);
        saveResponse(form, "20260023", ResponseStatus.ACCEPTED);
        saveResponse(form, "20260024", ResponseStatus.REJECTED);
        saveResponse(form, "20260025", ResponseStatus.REJECTED);
        saveResponse(form, "20260026", ResponseStatus.REJECTED);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseSummary.total").value(6))
                .andExpect(jsonPath("$.data.responseSummary.submitted").value(1))
                .andExpect(jsonPath("$.data.responseSummary.accepted").value(2))
                .andExpect(jsonPath("$.data.responseSummary.rejected").value(3))
                // 목록과 같은 집계에서 나오므로 total과 responseCount는 언제나 같다
                .andExpect(jsonPath("$.data.responseCount").value(6));
    }

    /*
     * 작성 중(DRAFT)은 요약 어디에도 들어가지 않는다 — #36이 정한 "작성 중 응답은 집계·목록에서
     * 뺀다"와 같은 기준이며, total을 DRAFT까지 세는 '전부'로 읽으면 상세의 전체와 목록의
     * 응답 건수가 갈려 같은 폼이 화면마다 다른 접수 건수를 갖는다.
     */
    @Test
    void getFormDetailResponseSummaryExcludesDraft() throws Exception {
        Long formId = createForm("작성 중 제외 확인 폼", "OPEN", "[]");
        FormEntity form = formRepository.findById(formId).orElseThrow();

        saveResponse(form, "20260031", ResponseStatus.SUBMITTED);
        saveResponse(form, "20260032", ResponseStatus.DRAFT);
        saveResponse(form, "20260033", ResponseStatus.DRAFT);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseSummary.total").value(1))
                .andExpect(jsonPath("$.data.responseSummary.submitted").value(1));
    }

    /*
     * N+1 회귀 방지. 응답 집계가 상태별로 나뉘었다고 해서(#37) 폼 목록의 쿼리 수가 늘어나서는
     * 안 된다 — 폼 1 + 라벨 1 + 응답 집계 1로 끝나고, 폼이 몇 건이든 그 아래 응답이 몇 건이든
     * 이 수는 그대로다 (DB-13).
     */
    @Test
    void getFormsRunsThreeQueriesRegardlessOfFormAndResponseCount() throws Exception {
        for (int index = 0; index < 3; index++) {
            Long formId = createForm("집계 폼 " + index, "OPEN", "[]");
            FormEntity form = formRepository.findById(formId).orElseThrow();
            saveResponse(form, "2026010" + index, ResponseStatus.SUBMITTED);
            saveResponse(form, "2026020" + index, ResponseStatus.ACCEPTED);
            saveResponse(form, "2026030" + index, ResponseStatus.REJECTED);
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        List<FormSummaryResponse> forms = formService.getForms(null, null);

        assertThat(forms).hasSize(3);
        assertThat(forms).allSatisfy(form -> assertThat(form.responseCount()).isEqualTo(3));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    // 응답이 한 건도 없는 폼은 GROUP BY 결과에 나오지 않는다. 그것과 0건은 같은 뜻이다
    @Test
    void getFormDetailResponseSummaryIsZeroWithoutResponses() throws Exception {
        Long formId = createForm("응답 없는 폼", "DRAFT", "[]");

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseSummary.total").value(0))
                .andExpect(jsonPath("$.data.responseSummary.submitted").value(0))
                .andExpect(jsonPath("$.data.responseSummary.accepted").value(0))
                .andExpect(jsonPath("$.data.responseSummary.rejected").value(0));
    }

    @Test
    void getUnknownFormReturns404() throws Exception {
        mockMvc.perform(authenticatedGet("/v1/forms/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * 학술 활동에서 이관된 모집 폼은 상세에 academicProgramId를 실어, 편집 화면이 접수 기간
     * 입력란을 숨길 근거를 얻는다 (#190). 이관 폼의 분류는 "EVENT"라 분류 코드로는 일반 폼과
     * 구별되지 않고, form → event → acdm_actv 역참조가 유일한 판별이다.
     */
    @Test
    void getFormExposesAcademicProgramIdWhenLinked() throws Exception {
        Long formId =
                createFormWithPeriod(
                        "학술 모집 폼",
                        "OPEN",
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[]");
        AcademicProgramEntity program = linkFormToAcademicProgram(formId);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramId").value(program.getId()));
    }

    // 학술에 연결되지 않은 일반 폼은 academicProgramId가 null이다 (#190) — 기존 소비자에 영향 없음
    @Test
    void getFormAcademicProgramIdIsNullForOrdinaryForm() throws Exception {
        Long formId = createForm("일반 지원서", "DRAFT", "[]");

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramId").value(Matchers.nullValue()));
    }

    /* ── 수정 ─────────────────────────────────────────────── */

    /*
     * 상태를 지정하지 않은 수정은 현재 상태를 그대로 둔다. 편집 자동 저장(ssccops #63)이
     * 같은 엔드포인트를 쓰므로, 생략을 DRAFT로 해석하면 접수 중인 폼이 저장할 때마다 닫힌다.
     */
    @Test
    void updateFormKeepsCurrentStatusWhenOmitted() throws Exception {
        Long formId = createForm("수정 대상 폼", "OPEN", "[]");
        String body = saveBody("제목만 바꾼 폼", null, null, null, "[]");

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("제목만 바꾼 폼"))
                .andExpect(jsonPath("$.data.formSttsCd").value("OPEN"));
    }

    /*
     * 학술 활동에 연결된 폼의 접수 기간은 "모집 관리"에서만 바꾼다 (#190). 폼 편집(PUT)에서
     * 접수 기간 두 필드를 현재 값과 다르게 보내면 400 ACADEMIC_FORM_RECEIPT_PERIOD_LOCKED다 —
     * 저장소가 하나(form.rcpt_bgng_dt/rcpt_end_dt)라 두 화면이 같은 값을 두고 경쟁하는 것을 막는다.
     */
    @Test
    void updateFormRejectsReceiptPeriodChangeOnAcademicLinkedForm() throws Exception {
        Long formId =
                createFormWithPeriod(
                        "학술 모집 폼",
                        "OPEN",
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[]");
        linkFormToAcademicProgram(formId);

        String body =
                saveBody(
                        "학술 모집 폼",
                        null,
                        "2026-03-01T00:00:00+09:00",
                        "2026-04-30T00:00:00+09:00",
                        "[]");

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACADEMIC_FORM_RECEIPT_PERIOD_LOCKED"));
    }

    /*
     * 잠그는 것은 접수 기간 두 필드뿐이다 (#190). 접수 기간은 현재 값 그대로 두고 제목만 바꾼
     * 저장(학술국장이 편집 화면에서 문항·문구를 채우는 정상 동선)은 200으로 통과해야 한다 —
     * 편집 자동 저장이 상세 응답을 그대로 되돌려 보내는 구조라 "안 바뀐 값"은 막지 않는다.
     */
    @Test
    void updateFormAllowsOtherEditsWhenReceiptPeriodUnchangedOnAcademicLinkedForm()
            throws Exception {
        Long formId =
                createFormWithPeriod(
                        "학술 모집 폼",
                        "OPEN",
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[]");
        linkFormToAcademicProgram(formId);

        String body =
                saveBody(
                        "문구를 다듬은 학술 모집 폼",
                        null,
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[]");

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("문구를 다듬은 학술 모집 폼"));
    }

    // 일반 폼은 PUT으로 접수 기간을 여전히 바꿀 수 있다 (#190 회귀 방지)
    @Test
    void updateFormStillAllowsReceiptPeriodChangeOnOrdinaryForm() throws Exception {
        Long formId =
                createFormWithPeriod(
                        "일반 지원서",
                        "OPEN",
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[]");

        String body =
                saveBody(
                        "일반 지원서",
                        null,
                        "2026-03-01T00:00:00+09:00",
                        "2026-04-30T00:00:00+09:00",
                        "[]");

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rcptEndDt").value("2026-04-30T00:00:00+09:00"));
    }

    // PUT은 전체 교체이므로 labelIds를 빈 배열로 보내면 라벨이 전부 떨어져야 한다
    @Test
    void updateFormReplacesLabelAssignment() throws Exception {
        Long recruitLabelId = saveLabel("신규모집").getId();
        Long eventLabelId = saveLabel("행사").getId();
        Long formId = createForm("라벨 교체 폼", null, "[" + recruitLabelId + "]");

        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId,
                                saveBody("라벨 교체 폼", null, null, null, "[" + eventLabelId + "]")))
                .andExpect(jsonPath("$.data.labels.length()").value(1))
                .andExpect(jsonPath("$.data.labels[0].lblNm").value("행사"));

        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId, saveBody("라벨 교체 폼", null, null, null, "[]")))
                .andExpect(jsonPath("$.data.labels").isEmpty());
    }

    /*
     * 폼 저장의 labelIds와 라벨 지정 API(#34)는 같은 규칙을 태워야 한다. 한동안 이 경로에만
     * 비활성 검사가 없어, 편집 화면에서는 비활성 라벨이 그대로 달리고 지정 API로는 막히는
     * 상태였다. 폼 편집 화면이 labelIds를 폼 저장에 실어 보내므로 사용자가 마주치는 것은
     * 이 경로다 — 규칙이 갈리지 않도록 고정한다.
     */
    @Test
    void createFormWithInactiveLabelReturns400() throws Exception {
        Long inactiveLabelId = saveInactiveLabel("지난학기").getId();

        mockMvc.perform(
                        authenticatedPost(
                                "/v1/forms",
                                saveBody(
                                        "비활성 라벨을 단 폼",
                                        null,
                                        null,
                                        null,
                                        "[" + inactiveLabelId + "]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_LABEL_NOT_USABLE"));
    }

    /*
     * 비활성은 "새로 달 수 없다"는 뜻이지 "달려 있던 것을 떼라"는 뜻이 아니다. 이미 달린
     * 라벨을 그대로 다시 보내는 저장(편집 화면이 늘 하는 일)은 통과해야 한다.
     */
    @Test
    void updateFormKeepsAlreadyAssignedLabelAfterItIsDeactivated() throws Exception {
        FormLabelEntity label = saveLabel("신규모집");
        Long formId = createForm("라벨 유지 폼", null, "[" + label.getId() + "]");

        label.changeActive(false);
        formLabelRepository.saveAndFlush(label);

        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId,
                                saveBody("라벨 유지 폼", null, null, null, "[" + label.getId() + "]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labels.length()").value(1))
                .andExpect(jsonPath("$.data.labels[0].lblNm").value("신규모집"));
    }

    /*
     * rspns_cn의 key가 qitemId라, 응답이 있는 폼에서 문항 식별자가 사라지면 과거 응답이 어느
     * 문항의 답인지 알 수 없게 된다. 되돌릴 수 없는 손실이라 400이 아니라 409다.
     */
    @Test
    void updateFormRemovingUsedQuestionItemReturns409() throws Exception {
        Long formId = createForm("응답이 있는 폼", "OPEN", "[]");
        saveResponse(
                formRepository.findById(formId).orElseThrow(),
                "20260021",
                ResponseStatus.SUBMITTED);

        String body =
                """
                {"formTtlNm": "문항을 지운 폼", "qitemCpstCn": {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                    "reqYn": true, "pageSeq": 0, "optionList": []
                  }]
                }}
                """;

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUESTION_ITEM_IN_USE"));
    }

    // 문항 식별자의 이름 변경은 "옛 id를 지우고 새 id를 넣는 것"과 구별되지 않으므로 같은 규칙에 걸린다
    @Test
    void updateFormRenamingUsedQuestionItemIdReturns409() throws Exception {
        Long formId = createForm("응답이 있는 폼", "OPEN", "[]");
        saveResponse(
                formRepository.findById(formId).orElseThrow(),
                "20260022",
                ResponseStatus.SUBMITTED);

        String body =
                """
                {"formTtlNm": "식별자를 바꾼 폼", "qitemCpstCn": {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [
                    {"qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                     "reqYn": true, "pageSeq": 0, "optionList": []},
                    {"qitemId": "renamed", "qitemLblNm": "지원 분야", "qitemTypeCd": "SINGLE_CHOICE",
                     "reqYn": true, "pageSeq": 0, "optionList": ["가", "나"]}
                  ]
                }}
                """;

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUESTION_ITEM_IN_USE"));
    }

    // 기존 식별자가 전부 남아 있으면 문항을 새로 더하는 것은 응답을 끊지 않는다
    @Test
    void updateFormAddingQuestionItemIsAllowedEvenWithResponses() throws Exception {
        Long formId = createForm("응답이 있는 폼", "OPEN", "[]");
        saveResponse(
                formRepository.findById(formId).orElseThrow(),
                "20260023",
                ResponseStatus.SUBMITTED);

        String body =
                """
                {"formTtlNm": "문항을 더한 폼", "qitemCpstCn": {
                  "pages": [
                    {"pageTtl": "기본 정보", "pageDescCn": null},
                    {"pageTtl": "상세", "pageDescCn": null}
                  ],
                  "qitems": [
                    {"qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                     "reqYn": true, "pageSeq": 0, "optionList": []},
                    {"qitemId": "q2", "qitemLblNm": "지원 분야", "qitemTypeCd": "SINGLE_CHOICE",
                     "reqYn": true, "pageSeq": 0, "optionList": ["백엔드", "프론트엔드"]},
                    {"qitemId": "q3", "qitemLblNm": "한마디", "qitemTypeCd": "LONG_TEXT",
                     "reqYn": false, "pageSeq": 1, "optionList": []}
                  ]
                }}
                """;

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body)).andExpect(status().isOk());
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(3));
    }

    // 응답이 없는 폼은 끊길 답이 없으므로 문항을 자유롭게 지울 수 있다
    @Test
    void updateFormWithoutResponsesAllowsRemovingQuestionItems() throws Exception {
        Long formId = createForm("응답 없는 폼", null, "[]");

        String body =
                """
                {"formTtlNm": "문항을 지운 폼", "qitemCpstCn": {
                  "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                    "reqYn": true, "pageSeq": 0, "optionList": []
                  }]
                }}
                """;

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, body)).andExpect(status().isOk());
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(1));
    }

    /* ── 복제 ─────────────────────────────────────────────── */

    /*
     * 웹 목 스토어(duplicateForm)가 확정해 둔 규칙 — '(복사본)' 접미·DRAFT·접수 일시 초기화 —
     * 그대로다. 라벨과 응답은 승계하지 않고 생성자는 복제를 수행한 회원이다.
     */
    @Test
    void duplicateFormCreatesDraftCopyWithoutLabelsAndResponses() throws Exception {
        Long labelId = saveLabel("신규모집").getId();
        Long sourceId =
                createFormWithPeriod(
                        "2026 신규모집 지원서",
                        "OPEN",
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[" + labelId + "]");
        saveResponse(
                formRepository.findById(sourceId).orElseThrow(),
                "20260031",
                ResponseStatus.SUBMITTED);

        String response =
                mockMvc.perform(authenticatedPost("/v1/forms/" + sourceId + "/duplicate", null))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.data.sourceFormId").value(sourceId))
                        .andExpect(jsonPath("$.data.formTtlNm").value("2026 신규모집 지원서 (복사본)"))
                        .andExpect(jsonPath("$.data.formSttsCd").value("DRAFT"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Long copyId = JsonPath.parse(response).read("$.data.formId", Long.class);
        assertThat(copyId).isNotEqualTo(sourceId);

        mockMvc.perform(authenticatedGet("/v1/forms/" + copyId))
                .andExpect(jsonPath("$.data.rcptBgngDt").isEmpty())
                .andExpect(jsonPath("$.data.rcptEndDt").isEmpty())
                .andExpect(jsonPath("$.data.labels").isEmpty())
                .andExpect(jsonPath("$.data.responseCount").value(0))
                .andExpect(jsonPath("$.data.creatrMbrId").value(actorId))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(2));
    }

    /*
     * 깊은 복사 확인. 사본은 원본과 같은 컬렉션을 가리키면 안 되므로, 복제 뒤 원본의 문항을
     * 갈아치우고 사본이 그대로인지 본다.
     */
    @Test
    void duplicatedFormIsNotAffectedByLaterChangesToSource() throws Exception {
        Long sourceId = createForm("원본 폼", null, "[]");

        String response =
                mockMvc.perform(authenticatedPost("/v1/forms/" + sourceId + "/duplicate", null))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long copyId = JsonPath.parse(response).read("$.data.formId", Long.class);

        String changed =
                """
                {"formTtlNm": "원본 폼", "qitemCpstCn": {
                  "pages": [{"pageTtl": "새 구성", "pageDescCn": null}],
                  "qitems": [{
                    "qitemId": "changed", "qitemLblNm": "바뀐 문항", "qitemTypeCd": "LONG_TEXT",
                    "reqYn": false, "pageSeq": 0, "optionList": []
                  }]
                }}
                """;
        mockMvc.perform(authenticatedPut("/v1/forms/" + sourceId, changed))
                .andExpect(status().isOk());

        mockMvc.perform(authenticatedGet("/v1/forms/" + copyId))
                .andExpect(jsonPath("$.data.qitemCpstCn.pages.length()").value(2))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(2))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemId").value("q1"));
    }

    @Test
    void duplicateUnknownFormReturns404() throws Exception {
        mockMvc.perform(authenticatedPost("/v1/forms/999999/duplicate", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 시스템 폼 잠금 · 문항 구성 버전 (#140) ───────────── */

    /*
     * 코드가 요구하는 qitemId를 지우면 400이다. 이 판정은 응답 유무를 보지 않는다 —
     * 응답이 한 건도 없어도 코드가 그 식별자로 값을 읽으므로 사라지면 조용히 빈 값이 읽힌다.
     */
    @Test
    void systemFormRejectsRemovingContractQuestionItem() throws Exception {
        Long formId = createForm("시스템 폼", null, "[]");
        designateAsSystemForm(formId);

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, bodyWithoutContractQuestion()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYSTEM_FORM_CONTRACT_VIOLATION"));
    }

    /*
     * 잠금 범위는 authrt.sys_yn 선례와 같다 — 계약을 지키는 한 제목·접수 기간·문구·문항 추가는
     * 전부 열어 둔다. 운영진이 회차마다 손대는 값이라 잠그면 시스템 폼은 한 번 세운 뒤 아무도
     * 운영할 수 없는 폼이 된다.
     */
    @Test
    void systemFormAllowsChangesThatKeepTheContract() throws Exception {
        Long formId = createForm("시스템 폼", null, "[]");
        designateAsSystemForm(formId);

        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId,
                                """
                                {"formTtlNm": "제목을 바꾼 시스템 폼",
                                 "rcptBgngDt": "2026-03-01T00:00:00+09:00",
                                 "qitemCpstCn": %s}
                                """
                                        .formatted(CONTRACT_KEPT_COMPOSITION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("제목을 바꾼 시스템 폼"));

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.sysYn").value(true))
                .andExpect(jsonPath("$.data.sysFormCd").value(SYSTEM_FORM_CODE))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(2))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemId").value("q3"));
    }

    // 시스템 폼이 아닌 폼에는 계약이 걸리지 않는다 — 선언이 있어도 그 코드를 달고 있어야 성립한다
    @Test
    void ordinaryFormCanRemoveTheSameQuestionItem() throws Exception {
        Long formId = createForm("평범한 폼", null, "[]");

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, bodyWithoutContractQuestion()))
                .andExpect(status().isOk());
    }

    /*
     * 편집 자동 저장(ssccops #63)은 매 타이핑마다 PUT을 쏜다. 제목만 바꾼 저장에도 버전이 오르면
     * 한 번 고치는 동안 버전이 수백까지 뛰고 그만큼의 이력이 쌓여 되짚는 데 쓸모가 없어진다.
     */
    @Test
    void questionVersionRisesOnlyWhenTheCompositionActuallyChanges() throws Exception {
        Long formId = createForm("버전 확인 폼", null, "[]");
        assertQuestionVersion(formId, 1);

        // 같은 구성에 제목만 다른 저장 — 자동 저장이 실제로 보내는 본문이다
        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId, saveBody("제목만 바꿈", null, null, null, "[]")))
                .andExpect(status().isOk());
        assertQuestionVersion(formId, 1);

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, bodyWithoutContractQuestion()))
                .andExpect(status().isOk());
        assertQuestionVersion(formId, 2);
    }

    /*
     * 이력은 버전이 오를 때만 남고, 생성 시점의 구성도 1번으로 남는다 — 수정에서만 남기면
     * 1번의 내용만 어디에도 없어 이력을 처음부터 되짚을 수 없다.
     *
     * 변경자는 요청 본문이 아니라 인증 주체에서 온다 (#78이 세운 규칙과 같다).
     */
    @Test
    void questionCompositionHistoryIsWrittenOnCreateAndOnEachVersionBump() throws Exception {
        Long formId = createForm("이력 확인 폼", null, "[]");

        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId, saveBody("제목만 바꿈", null, null, null, "[]")))
                .andExpect(status().isOk());
        assertThat(historyOf(formId)).hasSize(1);

        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, bodyWithoutContractQuestion()))
                .andExpect(status().isOk());

        List<FormQuestionHistoryEntity> history = historyOf(formId);
        assertThat(history).hasSize(2);
        assertThat(history)
                .extracting(FormQuestionHistoryEntity::getQuestionVersion)
                .containsExactly(1, 2);
        assertThat(history.get(0).getQuestionComposition().qitems()).hasSize(2);
        assertThat(history.get(1).getQuestionComposition().qitems()).hasSize(1);
        assertThat(history)
                .allSatisfy(row -> assertThat(row.getChangedBy().getId()).isEqualTo(actorId));
    }

    /*
     * 사본은 시스템 폼이 아니다. sys_form_cd에 UNIQUE가 걸려 있어 승계하면 저장 자체가 실패하고,
     * 실패하지 않더라도 코드가 두 폼 중 어느 쪽을 가리키는지 알 수 없게 된다. 버전도 1에서
     * 다시 시작한다 — 사본의 이력은 여기서 시작하므로 원본의 번호를 물려받으면 그 앞 버전의
     * 이력이 없는 채로 번호만 큰 폼이 된다.
     */
    @Test
    void duplicateClearsSystemFormMarkAndRestartsQuestionVersion() throws Exception {
        Long sourceId = createForm("시스템 폼", null, "[]");
        designateAsSystemForm(sourceId);
        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + sourceId, saveBody("시스템 폼", null, null, null, "[]")))
                .andExpect(status().isOk());

        String response =
                mockMvc.perform(authenticatedPost("/v1/forms/" + sourceId + "/duplicate", null))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long copyId = JsonPath.parse(response).read("$.data.formId", Long.class);

        mockMvc.perform(authenticatedGet("/v1/forms/" + copyId))
                .andExpect(jsonPath("$.data.sysFormCd").isEmpty())
                .andExpect(jsonPath("$.data.sysYn").value(false))
                .andExpect(jsonPath("$.data.qitemVer").value(1));
        assertThat(historyOf(copyId)).hasSize(1);
    }

    // 목록에도 싣는다 — 상세로 들어가기 전에 잠금 배지를 그릴 수 있어야 한다
    @Test
    void formListExposesSystemFormMarkAndQuestionVersion() throws Exception {
        Long formId = createForm("시스템 폼", null, "[]");
        designateAsSystemForm(formId);

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].formId").value(formId))
                .andExpect(jsonPath("$.data[0].sysFormCd").value(SYSTEM_FORM_CODE))
                .andExpect(jsonPath("$.data[0].sysYn").value(true))
                .andExpect(jsonPath("$.data[0].qitemVer").value(1));
    }

    /* ── 계약 문항 노출 (#155) ────────────────────── */

    /*
     * 상세가 코드가 요구하는 qitemId를 실어 준다. 이것이 없는 동안 웹(ssccops-web#132)은 저장이 400으로
     * 터진 뒤 "보낸 구성에서 빠진 문항"을 역산해 잠금을 걸었고, 그러면 운영자는 문항을 지우고 저장을
     * 누른 뒤에야 막혔다는 것을 알았다.
     *
     * q1은 실제 선언에는 없고 갈아 끼운 계약 빈에만 있는 값이다 — 목록이 q1이라는 것 자체가
     * 응답이 계약 빈을 거쳐 왔다는 증거다.
     */
    @Test
    void formDetailExposesSystemContractQuestionItems() throws Exception {
        Long formId = createForm("시스템 폼", null, "[]");
        designateAsSystemForm(formId);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sysYn").value(true))
                .andExpect(jsonPath("$.data.systemRequiredQitemIds").isArray())
                .andExpect(jsonPath("$.data.systemRequiredQitemIds.length()").value(1))
                .andExpect(jsonPath("$.data.systemRequiredQitemIds[0]").value("q1"));
    }

    /*
     * 시스템 폼이 아니면 빈 배열이며 null이 아니다. 둘을 섞으면 받는 쪽이 "없음"과 "비어 있음"을
     * 구별해야 하는데 둘은 같은 뜻이다. isArray()로 보는 것이 요점으로, null이면 그 단언이 깨진다 —
     * isEmpty()는 null과 빈 배열을 같은 것으로 보아 이 규칙을 지키지 못한다.
     */
    @Test
    void ordinaryFormDetailExposesEmptyContractArrayInsteadOfNull() throws Exception {
        Long formId = createForm("평범한 폼", null, "[]");

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sysYn").value(false))
                .andExpect(jsonPath("$.data.systemRequiredQitemIds").isArray())
                .andExpect(jsonPath("$.data.systemRequiredQitemIds.length()").value(0));
    }

    /*
     * 목록은 계약 문항을 싣지 않는다. 문항 편집은 상세·편집 화면에서만 하므로 카드가 쓸 일이 없고,
     * 목록이 qitemCpstCn을 빼는 규칙과 같은 줄기다. 시스템 폼임을 알리는 세 스칼라(sysFormCd·sysYn·
     * qitemVer)는 그대로 실린다 — 잠금 배지는 목록에서도 그려야 한다.
     */
    @Test
    void formListDoesNotCarryContractQuestionItems() throws Exception {
        Long formId = createForm("시스템 폼", null, "[]");
        designateAsSystemForm(formId);

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].formId").value(formId))
                .andExpect(jsonPath("$.data[0].sysFormCd").value(SYSTEM_FORM_CODE))
                .andExpect(jsonPath("$.data[0].systemRequiredQitemIds").doesNotExist());
    }

    /*
     * 계약이 바뀌면 응답도 따라 바뀐다. 폼의 구성은 앞 시험과 똑같고 달라진 것은 그 폼이 달고 있는
     * sys_form_cd 하나뿐이다 — 답이 달라진다면 값이 폼이 아니라 계약 선언에서 온다는 뜻이다.
     * 두 문항이 q1·q2 순으로 나오는지도 같이 본다 — Set을 그대로 실으면 순서가 호출마다 뒤집힌다.
     */
    @Test
    void formDetailFollowsTheContractDeclaration() throws Exception {
        Long formId = createForm("계약이 둘인 시스템 폼", null, "[]");
        designateAsSystemForm(formId, TWO_QUESTION_SYSTEM_FORM_CODE);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemRequiredQitemIds.length()").value(2))
                .andExpect(jsonPath("$.data.systemRequiredQitemIds[0]").value("q1"))
                .andExpect(jsonPath("$.data.systemRequiredQitemIds[1]").value("q2"));
    }

    /*
     * 화면이 미리 잠그는 목록과 서버가 거절하는 목록은 같아야 한다. 상세가 알려 준 문항을 실제로 지워
     * 저장해 400이 나는 것까지를 한 시험에 묶는 것은, 둘이 갈라지면 화면은 잠기지 않았는데 서버는 거절하는
     * — #155가 없애려는 바로 그 상황으로 돌아가기 때문이다. 미리 잠그는 것은 편의이고 서버의 400이
     * 방어선이며, 이번 변경은 뒤에 손대지 않았다.
     */
    @Test
    void exposedContractMatchesWhatTheSavePathRejects() throws Exception {
        Long formId = createForm("시스템 폼", null, "[]");
        designateAsSystemForm(formId);

        String detail =
                mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        List<String> requiredQitemIds =
                JsonPath.parse(detail).read("$.data.systemRequiredQitemIds");
        assertThat(requiredQitemIds).containsExactly("q1");

        // 상세가 알려 준 바로 그 문항(q1)을 지운 구성이다
        mockMvc.perform(authenticatedPut("/v1/forms/" + formId, bodyWithoutContractQuestion()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYSTEM_FORM_CONTRACT_VIOLATION"));
    }

    /* ── 접수 상태 전이 (#33) ─────────────────────────────── */

    /*
     * DRAFT → OPEN. 상세 화면의 '접수 시작' 버튼이다. 기간을 3/1~3/31로 두었고 고정 시각이
     * 3/15이므로 열자마자 실제로 접수 중(ACCEPTING)이 되어야 한다.
     */
    @Test
    void draftFormOpensReceipt() throws Exception {
        Long formId =
                createFormWithPeriod(
                        "접수 시작 대상 폼",
                        null,
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-31T00:00:00+09:00",
                        "[]");

        mockMvc.perform(statusPost(formId, "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").value(formId))
                .andExpect(jsonPath("$.data.formSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data.receiptStatus").value("ACCEPTING"))
                .andExpect(jsonPath("$.data.rcptBgngDt").value("2026-03-01T00:00:00+09:00"))
                .andExpect(jsonPath("$.data.mdfcnDt").isNotEmpty());
    }

    /*
     * 접수 기간이 아직 오지 않았는데 여는 경우. 상태는 OPEN이지만 화면에는 '접수 예정'으로
     * 나가야 한다 — 버튼을 누른 직후 목록이 '접수 중'이라 잘못 말하면 안 된다.
     */
    @Test
    void openingFormBeforeReceiptPeriodReportsScheduled() throws Exception {
        Long formId =
                createFormWithPeriod("접수 예정 폼", null, "2026-04-01T00:00:00+09:00", null, "[]");

        mockMvc.perform(statusPost(formId, "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data.receiptStatus").value("SCHEDULED"));
    }

    // OPEN → CLOSED. 상세 화면의 '마감' 버튼이다
    @Test
    void openFormClosesReceipt() throws Exception {
        Long formId = createForm("마감 대상 폼", "OPEN", "[]");

        mockMvc.perform(statusPost(formId, "CLOSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formSttsCd").value("CLOSED"))
                .andExpect(jsonPath("$.data.receiptStatus").value("CLOSED"));
    }

    /*
     * CLOSED → OPEN(마감 철회). 마감 버튼을 잘못 누른 운영자가 되돌릴 수 있어야 한다 —
     * 되돌릴 수 없으면 폼을 복제해 새로 여는 우회가 생기고 응답이 두 폼으로 갈린다.
     */
    @Test
    void closedFormCanReopenReceipt() throws Exception {
        Long formId = createForm("마감 철회 대상 폼", "OPEN", "[]");
        mockMvc.perform(statusPost(formId, "CLOSE")).andExpect(status().isOk());

        mockMvc.perform(statusPost(formId, "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data.receiptStatus").value("ACCEPTING"));
    }

    /*
     * 전이표에 없는 세 조합. 각각 따로 폼을 만들어야 하지만 확인하려는 것("표에 없으면 전부
     * 같은 코드로 거절한다")은 조합 간 비교라 한 자리에 둔다.
     */
    @Test
    void transitionsOutsideTheTableReturn400() throws Exception {
        Long openForm = createForm("이미 열린 폼", "OPEN", "[]");
        Long draftForm = createForm("작성 중인 폼", null, "[]");
        Long closedForm = createForm("마감된 폼", "OPEN", "[]");
        mockMvc.perform(statusPost(closedForm, "CLOSE")).andExpect(status().isOk());

        assertTransitionRejected(openForm, "OPEN"); // 이미 열림
        assertTransitionRejected(draftForm, "CLOSE"); // 열린 적이 없어 닫을 것이 없다
        assertTransitionRejected(closedForm, "CLOSE"); // 이미 마감
    }

    private void assertTransitionRejected(Long formId, String action) throws Exception {
        mockMvc.perform(statusPost(formId, action))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_FORM_STATUS_TRANSITION"));
    }

    /*
     * 문항 0개인 폼은 열 수 없다. 저장 시점에는 정상인 상태(편집을 막 시작한 DRAFT)라
     * QuestionCompositionValidator는 통과시키므로, 막는 자리는 공개되는 지점이어야 한다.
     */
    @Test
    void openingFormWithoutQuestionReturns400() throws Exception {
        Long formId = createEmptyForm("문항 없는 폼", null);

        mockMvc.perform(statusPost(formId, "OPEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_HAS_NO_QUESTION"));
    }

    /*
     * 같은 규칙이 생성 경로('바로 접수 시작')에도 걸려야 한다. 전이 경로에만 두면 만들자마자
     * OPEN인 폼만 문항 0개로 공개되는 구멍이 남는다.
     */
    @Test
    void creatingOpenFormWithoutQuestionReturns400() throws Exception {
        mockMvc.perform(authenticatedPost("/v1/forms", emptyFormBody("문항 없는 폼", "OPEN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_HAS_NO_QUESTION"));
    }

    @Test
    void changingStatusOfUnknownFormReturns404() throws Exception {
        mockMvc.perform(statusPost(999999L, "OPEN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * 상태를 PUT으로 바꿀 수 없다는 것이 이 엔드포인트를 따로 둔 이유다. 편집 자동 저장은
     * 상세 응답을 초안으로 받아 그대로 되돌려 보내므로 본문에 formSttsCd가 늘 실려 있다 —
     * 그 값을 받아 쓰면 타이핑 한 번이 접수 상태를 덮어쓴다. 요청은 거절하지 않고 값만 무시한다.
     */
    @Test
    void updateFormIgnoresStatusInBody() throws Exception {
        Long formId = createForm("자동 저장 대상 폼", null, "[]");

        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId,
                                saveBody("자동 저장 대상 폼", "OPEN", null, null, "[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formSttsCd").value("DRAFT"));

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.formSttsCd").value("DRAFT"));
    }

    // 반대 방향도 마찬가지다 — 접수 중인 폼에 DRAFT를 실어 보내도 닫히지 않아야 한다
    @Test
    void updateFormCannotCloseOpenFormThroughBody() throws Exception {
        Long formId = createForm("접수 중인 폼", "OPEN", "[]");

        mockMvc.perform(
                        authenticatedPut(
                                "/v1/forms/" + formId,
                                saveBody("접수 중인 폼", "DRAFT", null, null, "[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formSttsCd").value("OPEN"));
    }

    /*
     * 이 이슈에서 내린 결정이 응답으로 드러나는 자리다. 접수 기간이 끝나도 자동 마감 배치를
     * 두지 않으므로 form_stts_cd는 OPEN으로 남고, 목록·상세는 receiptStatus로 '기간 종료'를
     * 구분해 내린다 (웹 #9는 이 값으로 배지를 고른다).
     */
    @Test
    void formWithExpiredReceiptPeriodStaysOpenButIsReportedExpired() throws Exception {
        Long formId =
                createFormWithPeriod(
                        "기간이 끝난 폼",
                        "OPEN",
                        "2026-03-01T00:00:00+09:00",
                        "2026-03-10T00:00:00+09:00",
                        "[]");

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.formSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data.receiptStatus").value("EXPIRED"));

        mockMvc.perform(authenticatedGet("/v1/forms?statusCode=OPEN"))
                .andExpect(jsonPath("$.data[0].formId").value(formId))
                .andExpect(jsonPath("$.data[0].formSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data[0].receiptStatus").value("EXPIRED"));
    }

    /* ── 인증 ─────────────────────────────────────────────── */

    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        mockMvc.perform(get("/v1/forms")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/forms/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/forms").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/forms/1/duplicate")).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post("/v1/forms/1/status")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\": \"OPEN\"}"))
                .andExpect(status().isUnauthorized());
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

    private String saveBody(
            String title, String status, String beginAt, String endAt, String labelIds) {
        return """
               {
                 "formTtlNm": "%s",
                 "formSttsCd": %s,
                 "rcptBgngDt": %s,
                 "rcptEndDt": %s,
                 "qitemCpstCn": %s,
                 "labelIds": %s
               }
               """
                .formatted(
                        title,
                        quoteOrNull(status),
                        quoteOrNull(beginAt),
                        quoteOrNull(endAt),
                        VALID_COMPOSITION,
                        labelIds);
    }

    private String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private Long createForm(String title, String status, String labelIds) throws Exception {
        return createFormWithPeriod(title, status, null, null, labelIds);
    }

    private Long createFormWithPeriod(
            String title, String status, String beginAt, String endAt, String labelIds)
            throws Exception {
        String response =
                mockMvc.perform(
                                authenticatedPost(
                                        "/v1/forms",
                                        saveBody(title, status, beginAt, endAt, labelIds)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.formId", Long.class);
    }

    /** 문항이 하나도 없는 폼 본문. 접수 시작 사전 검증(FORM_HAS_NO_QUESTION)을 확인할 때만 쓴다 */
    private String emptyFormBody(String title, String status) {
        return """
               {"formTtlNm": "%s", "formSttsCd": %s, "qitemCpstCn": %s}
               """
                .formatted(title, quoteOrNull(status), EMPTY_COMPOSITION);
    }

    private Long createEmptyForm(String title, String status) throws Exception {
        String response =
                mockMvc.perform(authenticatedPost("/v1/forms", emptyFormBody(title, status)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.formId", Long.class);
    }

    private MockHttpServletRequestBuilder statusPost(Long formId, String action) {
        return authenticatedPost(
                "/v1/forms/" + formId + "/status", "{\"action\": \"" + action + "\"}");
    }

    /** 계약 문항(q1)을 지운 저장 본문 */
    private String bodyWithoutContractQuestion() {
        return """
               {"formTtlNm": "문항을 지운 폼", "qitemCpstCn": %s}
               """
                .formatted(COMPOSITION_WITHOUT_CONTRACT_QUESTION);
    }

    /*
     * 시스템 폼으로 세운다. API가 아니라 엔티티로 하는 것은 그것이 유일한 경로이기 때문이다 —
     * 화면에서 지정할 수 있으면 운영자가 아무 폼에나 코드를 붙일 수 있고, 그 순간 이 잠금은
     * 지키는 것이 없어진다 (FormEntity.designateAsSystemForm 주석).
     */
    private void designateAsSystemForm(Long formId) {
        designateAsSystemForm(formId, SYSTEM_FORM_CODE);
    }

    private void designateAsSystemForm(Long formId, String systemFormCode) {
        FormEntity form = formRepository.findById(formId).orElseThrow();
        form.designateAsSystemForm(systemFormCode);
        formRepository.saveAndFlush(form);
    }

    private void assertQuestionVersion(Long formId, int expected) throws Exception {
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(jsonPath("$.data.qitemVer").value(expected));
    }

    private List<FormQuestionHistoryEntity> historyOf(Long formId) {
        return formQuestionHistoryRepository.findAllByFormOrderByQuestionVersionAsc(
                formRepository.findById(formId).orElseThrow());
    }

    private FormLabelEntity saveInactiveLabel(String name) {
        FormLabelEntity label = FormLabelEntity.create(name);
        label.changeActive(false);
        return formLabelRepository.saveAndFlush(label);
    }

    private FormLabelEntity saveLabel(String name) {
        return formLabelRepository.saveAndFlush(FormLabelEntity.create(name));
    }

    private void saveResponse(FormEntity form, String studentNumber, ResponseStatus status) {
        MemberEntity responder =
                saveMember(
                        UUID.randomUUID(), studentNumber, "응답자", studentNumber + "@soongsil.ac.kr");
        FormResponseHistoryEntity response =
                status == ResponseStatus.DRAFT
                        ? FormResponseHistoryEntity.createDraft(form, responder, null)
                        : FormResponseHistoryEntity.createSubmitted(
                                form,
                                responder,
                                ResponseContent.of(Map.of("q1", "홍길동")),
                                Instant.parse("2026-03-10T12:00:00Z"));
        formResponseHistoryRepository.saveAndFlush(response);

        /*
         * ACCEPTED·REJECTED는 상태 변경 API(#37)로만 도달하는 상태라 엔티티에 생성 팩토리가 없다.
         * 집계 기준(제출 이상만 센다)을 확인하려면 그 상태의 행이 필요하므로 여기서만 직접 갱신한다 —
         * 확인하려는 것을 위해 프로덕션 코드에 테스트 전용 메서드를 열지 않는다.
         */
        if (status == ResponseStatus.ACCEPTED || status == ResponseStatus.REJECTED) {
            entityManager
                    .createQuery(
                            "update FormResponseHistoryEntity r set r.status = :status"
                                    + " where r.id = :id")
                    .setParameter("status", status)
                    .setParameter("id", response.getId())
                    .executeUpdate();
        }
    }

    /*
     * 승인 이관(ssccops#148)의 결과 모양을 흉내 낸다 — event를 만들어 이 폼에 연결하고, 그
     * event를 확장하는 acdm_actv 행을 심는다(제출자·기획안 응답까지 AcademicProgramFixture가
     * 함께 만든다). form → event → acdm_actv 역참조가 성립하면 폼 상세의 academicProgramId와
     * 접수 기간 잠금이 걸린다 (#190).
     */
    private AcademicProgramEntity linkFormToAcademicProgram(Long formId) {
        MemberEntity actor = memberRepository.findById(actorId).orElseThrow();
        AcademicProgramEntity program =
                AcademicProgramFixture.save(
                        eventRepository,
                        eventClassificationRepository,
                        academicProgramRepository,
                        academicProgramTypeRepository,
                        curriculumItemRepository,
                        formRepository,
                        formResponseHistoryRepository,
                        "STUDY",
                        "학술 모집",
                        actor,
                        List.of());
        EventEntity event = program.getEvent();
        event.linkForm(formRepository.findById(formId).orElseThrow());
        eventRepository.saveAndFlush(event);
        return program;
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
        return get(path).header("Authorization", "Bearer " + AUTH_USER_ID);
    }

    private MockHttpServletRequestBuilder authenticatedPost(String path, String body) {
        MockHttpServletRequestBuilder request =
                post(path).header("Authorization", "Bearer " + AUTH_USER_ID);
        return body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder authenticatedPut(String path, String body) {
        return put(path)
                .header("Authorization", "Bearer " + AUTH_USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @TestConfiguration
    static class FormTestConfig {

        /*
         * 접수 상태 표시(receiptStatus)가 주입된 Clock에서 오는지 확인해야 하므로 시각도 고정한다.
         * 시스템 시각을 그대로 쓰면 '기간 종료' 케이스가 달력에 따라 통과·실패를 오간다.
         *
         * ClockConfig가 정의한 clock 빈과 이름이 겹치지 않게 다른 이름으로 둔다 (MemberControllerTest 선례).
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }

        /*
         * 계약을 갈아 끼운다 (#140). 실제 선언(#173의 PROPOSAL)을 그대로 쓰면 이 테스트가 기획안
         * 폼의 문항 목록에 묶여, 시드가 문항을 하나 더할 때마다 잠금 배선 테스트가 함께 깨진다 —
         * SystemFormContract를 상수가 아니라 빈으로 둔 이유가 이것이다.
         */
        @Bean
        @Primary
        SystemFormContract systemFormContract() {
            return new SystemFormContract(
                    Map.of(
                            SYSTEM_FORM_CODE,
                            Set.of("q1"),
                            TWO_QUESTION_SYSTEM_FORM_CODE,
                            Set.of("q1", "q2")));
        }
    }
}
