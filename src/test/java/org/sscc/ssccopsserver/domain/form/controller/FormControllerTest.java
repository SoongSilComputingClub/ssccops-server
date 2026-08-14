package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 폼 API(#32) 통합 검증. 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록
 * 대체한다 — WorkControllerTest와 같은 방식이며, SecurityMockMvcRequestPostProcessors.jwt()는
 * 커스텀 컨버터(@CurrentMember가 기대는 AuthenticatedUser)를 우회하므로 쓰지 않는다.
 *
 * 문항 구성의 규칙별 검증은 QuestionCompositionValidatorTest가 다루고, 여기서는 그 위반이
 * 실제로 400 INVALID_QUESTION_COMPOSITION으로 나가는지와 나머지 계약을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FormControllerTest.StubJwtDecoderConfig.class)
@Transactional
class FormControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

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

    @PersistenceContext private EntityManager entityManager;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormLabelRepository formLabelRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    private Long actorId;

    @BeforeEach
    void setUp() {
        actorId = saveMember(AUTH_USER_ID, "20260001", "이서연", "actor@sscc.org").getId();
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

    @Test
    void getUnknownFormReturns404() throws Exception {
        mockMvc.perform(authenticatedGet("/v1/forms/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
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

    /* ── 인증 ─────────────────────────────────────────────── */

    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        mockMvc.perform(get("/v1/forms")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/forms/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/forms").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/forms/1/duplicate")).andExpect(status().isUnauthorized());
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
        MockHttpServletRequestBuilder request =
                post(path).header("Authorization", "Bearer any-token");
        return body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder authenticatedPut(String path, String body) {
        return put(path)
                .header("Authorization", "Bearer any-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
