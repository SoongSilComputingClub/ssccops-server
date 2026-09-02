package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.sscc.ssccopsserver.domain.form.code.FormStatusAction;
import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseReviewHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
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

    /*
     * 기획안 시스템 폼의 축소 표본 (#196). 대표 문항(programTitle)만 두는 것은 이 테스트가 보는 것이
     * "선언된 문항의 답이 목록에 실리는가" 하나이기 때문이다 — 시드의 문항 열한 개를 그대로 옮기면
     * 시드가 문항을 하나 더할 때마다 이 표본도 함께 고쳐야 하고, 그러면 무엇을 검증하는 테스트인지
     * 흐려진다. qitemId는 리터럴이 아니라 ProposalFormSeed의 상수를 쓴다.
     */
    private static final String PROPOSAL_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기획안", "pageDescCn": null}],
              "qitems": [
                {
                  "qitemId": "programTitle", "qitemLblNm": "활동명", "qitemTypeCd": "SHORT_TEXT",
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
    @Autowired private FormResponseReviewHistoryRepository formResponseReviewHistoryRepository;

    private MemberEntity respondent;

    /** 재제출(#141) 표본을 만들 때 수정요청을 누르는 사람. 이 클래스의 인증 주체는 언제나 응답자다 */
    private MemberEntity reviewer;

    @BeforeEach
    void setUp() {
        respondent = saveMember(AUTH_USER_ID, "20260001", "이서연", "actor@sscc.org");
        reviewer = saveMember(UUID.randomUUID(), "20200001", "김운영", "reviewer@sscc.org");
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

    /*
     * 반려된 응답은 alreadySubmitted를 세우지 않는다 (#192). 그 뜻이 "냈는가"가 아니라 "더 낼 수
     * 없는가"인 이상(#143) 다시 낼 수 있는 응답자에게 참을 내려주면 웹은 작성 화면 대신 제출 내역
     * 화면을 띄우고, 신청 자체가 화면에서 막힌다.
     *
     * myResponseCount·submittedAt은 그대로 반려된 응답을 센다 — 그 둘이 묻는 것은 "냈는가"다.
     */
    @Test
    void getPublicFormAfterRejectionDoesNotReportAlreadySubmitted() throws Exception {
        Long formId = saveForm("반려 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        reject();

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadySubmitted").value(false))
                .andExpect(jsonPath("$.data.myResponseCount").value(1))
                .andExpect(jsonPath("$.data.submittedAt").value(NOW_IN_SERVICE_ZONE));
    }

    /* ── 회원용 시스템 폼 조회 (#181) ───────────────────────── */

    /*
     * 이 이슈의 핵심 한 줄이다 — 일반 회원이 인증만으로 sys_form_cd로 폼의 form_id와 문항 구성을
     * 얻는다. sysFormCd를 싣는 운영자용 조회는 FORM_READ 권한에 막혀 기획안 제출자가 부를 수 없다.
     */
    @Test
    void getSystemFormReturnsFormIdAndComposition() throws Exception {
        Long formId = saveSystemForm("기획안", "PROPOSAL", FormStatus.OPEN, null, null, true);

        mockMvc.perform(authenticatedGet("/v1/forms/system/PROPOSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").value(formId))
                .andExpect(jsonPath("$.data.formTtlNm").value("기획안"))
                .andExpect(jsonPath("$.data.sysFormCd").value("PROPOSAL"))
                .andExpect(jsonPath("$.data.mltplRspnsYn").value(true))
                .andExpect(jsonPath("$.data.acceptingYn").value(true))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(3))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemId").value("q1"));
    }

    /*
     * 운영자용 DTO를 재사용하지 않는다 (#181 지킬 것). 생성자·응답 집계·폼 상태 내부값은
     * 회원용 경로로 나갈 이유가 없다.
     */
    @Test
    void getSystemFormDoesNotExposeOperatorOnlyFields() throws Exception {
        saveSystemForm("기획안", "PROPOSAL", FormStatus.OPEN, null, null, true);

        mockMvc.perform(authenticatedGet("/v1/forms/system/PROPOSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatrMbrId").doesNotExist())
                .andExpect(jsonPath("$.data.creatrMbrNm").doesNotExist())
                .andExpect(jsonPath("$.data.responseSummary").doesNotExist())
                .andExpect(jsonPath("$.data.responseCount").doesNotExist())
                .andExpect(jsonPath("$.data.formSttsCd").doesNotExist());
    }

    /*
     * 아직 시드되지 않았거나(회원이 한 명도 없으면 기획안 폼 시드를 미룬다) 지워진 코드는 404다 —
     * 웹은 이것을 "폼이 아직 준비되지 않았다"로 갈라 안내한다.
     */
    @Test
    void getSystemFormOnUnknownCodeReturns404() throws Exception {
        mockMvc.perform(authenticatedGet("/v1/forms/system/PROPOSAL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * **마감된 폼도 200이고 문항 구성이 실린다.** 재제출 화면은 마감된 폼의 문항도 그려야 한다 —
     * CHANGES_REQUESTED 재제출은 접수 마감에 막히지 않는데(#177) GET .../public은 마감 시 409라
     * 문항을 받을 수 없다. 이 조회는 접수 가능 여부를 보지 않는다.
     */
    @Test
    void getSystemFormOnClosedFormStillReturnsComposition() throws Exception {
        saveSystemForm("기획안", "PROPOSAL", FormStatus.CLOSED, null, null, true);

        mockMvc.perform(authenticatedGet("/v1/forms/system/PROPOSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptingYn").value(false))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(3));
    }

    /*
     * acceptingYn의 출처는 FormReceiptPolicy 하나여야 한다 (#181 지킬 것) — 웹이 상태·기간으로
     * 다시 계산하지 않게. 상태는 OPEN이지만 접수 기간이 지난 폼은 FormReceiptPolicy가 EXPIRED로
     * 보므로 acceptingYn도 false여야 한다.
     */
    @Test
    void getSystemFormAcceptingYnMatchesReceiptPolicyAfterPeriodEnds() throws Exception {
        saveSystemForm(
                "기획안",
                "PROPOSAL",
                FormStatus.OPEN,
                NOW.minusSeconds(864000),
                NOW.minusSeconds(86400),
                true);

        mockMvc.perform(authenticatedGet("/v1/forms/system/PROPOSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptingYn").value(false))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems.length()").value(3));
    }

    // 접수 시작 전(SCHEDULED)도 같다 — 상태만 OPEN이고 아직 열리지 않은 폼이다
    @Test
    void getSystemFormAcceptingYnMatchesReceiptPolicyBeforePeriodStarts() throws Exception {
        saveSystemForm(
                "기획안",
                "PROPOSAL",
                FormStatus.OPEN,
                NOW.plusSeconds(86400),
                NOW.plusSeconds(864000),
                true);

        mockMvc.perform(authenticatedGet("/v1/forms/system/PROPOSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptingYn").value(false));
    }

    /*
     * 등급 제한이 없다 — 가입 직후의 임시회원(TEMP)도 자기 기획안을 재제출해야 하므로 조회된다.
     * 이 클래스의 인증 주체(respondent)는 MemberFixture가 만든 TEMP 회원이다.
     */
    @Test
    void getSystemFormIsReadableByTempMember() throws Exception {
        assertThat(respondent.getMembershipGrade().getCode())
                .isEqualTo(MemberGradeCode.TEMP.code());
        saveSystemForm("기획안", "PROPOSAL", FormStatus.OPEN, null, null, true);

        mockMvc.perform(authenticatedGet("/v1/forms/system/PROPOSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sysFormCd").value("PROPOSAL"));
    }

    @Test
    void getSystemFormWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/forms/system/PROPOSAL")).andExpect(status().isUnauthorized());
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

    /*
     * 응답은 "그 답이 어느 문항 구성에 대한 답인가"를 함께 남긴다 (#140 · form_rspns_hstry.qitem_ver).
     *
     * 폼의 현재 버전을 나중에 다시 읽으면 되지 않는다 — 그 값은 이미 다음 버전일 수 있고,
     * 그러면 "지원자가 무엇을 보고 답했는가"에 답할 수 없다. 임시저장을 시작한 시점이 아니라
     * **마지막으로 답을 쓴 시점**의 버전이어야 하므로, 1번 구성에서 시작한 초안이 폼이 2번으로
     * 바뀐 뒤 제출되면 2가 찍혀야 한다.
     */
    @Test
    void submittedResponseCarriesTheQuestionVersionItAnsweredAgainst() throws Exception {
        Long formId = saveForm("버전 기록 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        FormEntity form = formRepository.findById(formId).orElseThrow();
        FormResponseHistoryEntity draft =
                formResponseHistoryRepository.saveAndFlush(
                        FormResponseHistoryEntity.createDraft(form, respondent, null));
        assertThat(draft.getQuestionVersion()).isEqualTo(1);

        // 문항은 그대로 두고 페이지만 더해 구성을 바꾼다 — 기존 답이 그대로 제출될 수 있어야 하기 때문이다
        List<QuestionCompositionContent.Page> pages =
                new ArrayList<>(form.getQuestionComposition().pages());
        pages.add(new QuestionCompositionContent.Page("덧붙인 페이지", null));
        form.update(
                form.getTitle(),
                new QuestionCompositionContent(pages, form.getQuestionComposition().qitems()),
                null,
                null,
                false);
        formRepository.saveAndFlush(form);

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        assertThat(onlyResponse().getQuestionVersion()).isEqualTo(2);
    }

    /*
     * **재제출 (#141).** 수정요청을 받은 응답은 같은 행을 다시 제출한다 — 응답은 회원당 폼당
     * 1건(UNIQUE)이라 새 행을 만들 수 없고, DRAFT로 되돌리는 길도 없기 때문이다. 회차가 오르는
     * 것이 요점이다: 회차가 없으면 이력의 처리들이 어느 제출본에 대한 것이었는지 알 수 없다.
     */
    @Test
    void resubmitAfterChangesRequestedIncreasesSequence() throws Exception {
        Long formId = saveForm("수정요청 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        requestChanges();

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("SUBMITTED"));

        FormResponseHistoryEntity resubmitted = onlyResponse();
        assertThat(resubmitted.getStatus()).isEqualTo(ResponseStatus.SUBMITTED);
        assertThat(resubmitted.getSubmissionSequence()).isEqualTo(2);
        assertThat(resubmitted.getContent().answers()).containsEntry("q1", "김철수");
        assertThat(formResponseHistoryRepository.count()).isEqualTo(1);
    }

    /*
     * 재제출도 처리 이력에 SUBMIT 한 줄을 남긴다. 그 줄이 없으면 타임라인이 "수정요청 → 승인"으로
     * 읽혀, 승인이 고쳐진 답을 보고 내려진 것인지 알 수 없다. 최초 제출도 같은 이유로 남는다.
     */
    @Test
    void everySubmissionLeavesSubmitHistory() throws Exception {
        Long formId = saveForm("이력 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        requestChanges();
        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated());

        List<FormResponseReviewHistoryEntity> histories =
                formResponseReviewHistoryRepository.findAllByResponseOrderByProcessedAtAscIdAsc(
                        onlyResponse());

        assertThat(histories)
                .extracting(
                        FormResponseReviewHistoryEntity::getAction,
                        FormResponseReviewHistoryEntity::getSubmissionSequence)
                .containsExactly(
                        tuple(ResponseReviewAction.SUBMIT, 1),
                        tuple(ResponseReviewAction.REQUEST_CHANGES, 1),
                        tuple(ResponseReviewAction.SUBMIT, 2));
        // 제출 행의 처리자는 검토자가 아니라 응답자 본인이다
        assertThat(histories.get(0).getProcessor().getId()).isEqualTo(respondent.getId());
    }

    /*
     * **반려는 그 응답에 대한 종결이지 그 폼에 대한 종결이 아니다 (#192).**
     *
     * #141이 정한 "오조작의 탈출구는 번복이 아니라 새 응답"이 그동안 다중 응답 폼에서만 열려
     * 있었다 — 단일 응답 폼(모집 폼은 전부 여기 든다)에서 반려된 응답자는 재제출도 새 제출도
     * 막혀 재신청 경로가 아예 없었다. 이제 폼의 종류와 무관하게 다음 순번의 새 응답이 된다.
     *
     * **반려된 행은 그대로 남는다.** 되살리는 것이 아니라 새로 내는 것이므로 그 행의 내용도
     * 제출 회차도 움직이지 않는다 — 움직이면 번복이 되어 #141이 막은 자리로 되돌아간다.
     */
    @Test
    void submitAfterRejectionIsAllowedOnSingleResponseForm() throws Exception {
        Long formId = saveForm("반려 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        reject();

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSeq").value(2));

        assertThat(myResponses())
                .extracting(
                        FormResponseHistoryEntity::getStatus,
                        FormResponseHistoryEntity::getSubmissionSequence,
                        response -> response.getContent().answers().get("q1"))
                .containsExactly(
                        tuple(ResponseStatus.REJECTED, 1, "홍길동"),
                        tuple(ResponseStatus.SUBMITTED, 1, "김철수"));
    }

    /*
     * 심사 중·승인된 응답은 종전대로 막는다 (#143). #192가 연 것은 반려 하나뿐이며, 여기까지
     * 넓히면 심사 중인 응답을 두고 또 내는 것이 되어 단일 응답 폼의 뜻 자체가 사라진다.
     */
    @Test
    void submitAgainWhileUnderReviewStillReturns409() throws Exception {
        Long formId = saveForm("단일 응답 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESPONSE_ALREADY_SUBMITTED"));

        assertThat(onlyResponse().getContent().answers()).containsEntry("q1", "홍길동");
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

    /* ── 다중 응답 (#143) ──────────────────────────────────── */

    /*
     * 폼이 허용하면 같은 사람이 여러 건을 낸다. 새 행이 생기고 응답 순번이 1 늘어야 한다 —
     * 같은 행을 다시 쓰면(제출 회차만 오르면) 첫 제안이 두 번째 제안으로 덮여 사라진다.
     */
    @Test
    void submitTwiceOnMultipleResponseFormCreatesSecondResponse() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSeq").value(1));
        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSeq").value(2));

        assertThat(myResponses())
                .extracting(
                        FormResponseHistoryEntity::getResponseSequence,
                        FormResponseHistoryEntity::getSubmissionSequence)
                .containsExactly(tuple(1, 1), tuple(2, 1));
    }

    /*
     * 단일 응답 폼의 동작은 그대로다 (#143 회귀 방어). 제약을 옮긴 것이지 없앤 것이 아니므로
     * 두 번째 제출은 여전히 409이고 행도 늘지 않는다 — 그 확인은 위쪽
     * submitResponseTwiceReturns409AndKeepsSingleRow가 맡는다.
     *
     * 여기서 보는 것은 **응답 순번이 1로 고정된다**는 사실이다. 서버가 이 값을 올려 버리면
     * UNIQUE가 성립하지 않아 단일 응답 폼에서도 두 번째 행이 들어간다.
     */
    @Test
    void singleResponseFormKeepsTheFirstSequence() throws Exception {
        Long formId = saveForm("단일 응답 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSeq").value(1));

        assertThat(onlyResponse().getResponseSequence()).isEqualTo(1);
    }

    /*
     * alreadySubmitted의 뜻은 "냈는가"가 아니라 **"더 낼 수 없는가"**다 (#143). 다중 응답 폼에서
     * 이미 낸 것을 이유로 true를 내리면 화면이 제출 내역만 보여줘 두 번째 제안을 낼 길이 없다.
     * 대신 myResponseCount로 "이미 1건 냈다"를 전한다.
     */
    @Test
    void publicFormOnMultipleResponseFormKeepsShowingTheWriteForm() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mltplRspnsYn").value(true))
                .andExpect(jsonPath("$.data.alreadySubmitted").value(false))
                .andExpect(jsonPath("$.data.myResponseCount").value(1))
                // 마지막 제출 일시는 그대로 내려간다 — 두 필드가 묻는 것이 다르다
                .andExpect(jsonPath("$.data.submittedAt").value(NOW_IN_SERVICE_ZONE));
    }

    /*
     * 수정요청을 받은 응답이 있으면 다중 응답 폼에서도 **그 응답을 마무리하는 것이 먼저다**.
     * 제출 경로에 응답 식별자가 없어 그 행을 지목할 방법이 없으므로, 새 응답을 우선하면
     * 수정요청받은 응답은 영영 SUBMITTED로 돌아가지 못한다.
     *
     * 이때 오르는 것은 제출 회차(sbmsnSeq)뿐이고 응답 순번(rspnsSeq)은 그대로다 — 두 값이
     * 다르다는 것을 가장 잘 보여주는 자리다.
     */
    @Test
    void resubmitAfterChangesRequestedReusesTheRowEvenOnMultipleResponseForm() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        requestChanges();

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSeq").value(1));

        FormResponseHistoryEntity resubmitted = onlyResponse();
        assertThat(resubmitted.getResponseSequence()).isEqualTo(1);
        assertThat(resubmitted.getSubmissionSequence()).isEqualTo(2);
        assertThat(resubmitted.getContent().answers()).containsEntry("q1", "김철수");
    }

    /*
     * 반려는 그 응답에 대한 종결이지 그 폼에 대한 종결이 아니다 (#141이 말한 "오조작의 탈출구는
     * 번복이 아니라 새 응답"의 실제 경로). 다중 응답 폼에서는 반려된 응답만 남아 있어도 새로 낼 수
     * 있어야 하며, 반려된 행은 그대로 남는다.
     */
    @Test
    void submitAfterRejectionIsAllowedOnMultipleResponseForm() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        reject();

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSeq").value(2));

        assertThat(myResponses())
                .extracting(FormResponseHistoryEntity::getStatus)
                .containsExactly(ResponseStatus.REJECTED, ResponseStatus.SUBMITTED);
    }

    /* ── 내 응답 목록 (#143) ───────────────────────────────── */

    /*
     * 응답자 본인의 응답을 순번·상태와 함께 내려준다. 경로에 mbrId가 없다 (#36과 같은 규칙).
     */
    @Test
    void getMyResponsesReturnsEachResponseWithItsSequence() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated());

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].rspnsSeq").value(1))
                .andExpect(jsonPath("$.data[0].sbmsnSeq").value(1))
                .andExpect(jsonPath("$.data[0].rspnsSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[0].sbmsnDt").value(NOW_IN_SERVICE_ZONE))
                .andExpect(jsonPath("$.data[1].rspnsSeq").value(2))
                // 응답 내용은 싣지 않는다 — 이 목록이 답하는 것은 "몇 건을 어떤 상태로 냈는가"다
                .andExpect(jsonPath("$.data[0].rspnsCn").doesNotExist());
    }

    /*
     * 작성 중(DRAFT) 응답도 내 목록에는 나온다. 운영자 목록이 DRAFT를 빼는 규칙은 "남의 제출 전
     * 답안이 심사 목록에 섞이지 않게" 하는 것이라 내 것에는 해당하지 않는다 — 빼면 쓰다 만 응답이
     * 화면에서 사라져 이어 쓸 방법이 없어진다.
     */
    @Test
    void getMyResponsesIncludesDraft() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        FormEntity form = formRepository.findById(formId).orElseThrow();
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(form, respondent, null));

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].rspnsSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].sbmsnDt").value(Matchers.nullValue()));
    }

    // 남의 응답은 섞이지 않는다. 대상은 언제나 인증 주체 본인이며 지목할 자리조차 없다
    @Test
    void getMyResponsesDoesNotReturnAnotherMembersResponse() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        FormEntity form = formRepository.findById(formId).orElseThrow();
        MemberEntity other = saveMember(UUID.randomUUID(), "20260002", "박민수", "other@sscc.org");
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        form, other, ResponseContent.of(Map.of("q1", "박민수")), NOW));

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /*
     * **접수가 끝난 폼에서도 조회된다.** 자동 저장 조회(GET .../responses/draft)와 갈리는 지점이며,
     * 여기서 409를 내면 마감된 순간부터 응답자가 자기가 낸 것을 확인할 길이 사라진다.
     */
    @Test
    void getMyResponsesWorksAfterTheFormIsClosed() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        FormEntity form = formRepository.findById(formId).orElseThrow();
        form.changeStatus(FormStatusAction.CLOSE);
        formRepository.saveAndFlush(form);

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void getMyResponsesOnUnknownFormReturns404() throws Exception {
        mockMvc.perform(authenticatedGet("/v1/forms/999999/responses/mine"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 대표 문항 (#196) ─────────────────────────────────── */

    /*
     * 이 이슈의 한 줄이다 — 제출 현황 화면이 "1번째 기획안 · 2번째 기획안"으로만 떠 제출자가 자기가
     * 낸 것을 구별할 수 없었다(ssccops-web#204). 목록이 응답 내용을 싣지 않는다는 규칙은 그대로이고,
     * 늘어난 것은 대표 문항의 답 한 줄뿐이다.
     *
     * 어느 문항이 대표값인지는 SystemFormContract의 실제 선언(PROPOSAL → programTitle)을 그대로
     * 쓴다. 잠금 계약(#155)을 시험용 코드로 갈아 끼우는 FormControllerTest와 갈리는데, 저쪽은
     * 시드가 문항을 더할 때마다 흔들리는 '집합'이고 이쪽은 값 하나라 그 값이 바뀌면 목록의 제목이
     * 실제로 달라진다 — 그 사실이 테스트에 잡히는 편이 맞다.
     */
    @Test
    void getMyResponsesCarriesTheTitleAnswerOfEachResponse() throws Exception {
        Long formId = saveProposalForm();
        submit(formId, """
               {"programTitle": "React 스터디"}
               """)
                .andExpect(status().isCreated());
        submit(formId, """
               {"programTitle": "알고리즘 스터디"}
               """)
                .andExpect(status().isCreated());

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].rspnsSeq").value(1))
                .andExpect(jsonPath("$.data[0].responseTitle").value("React 스터디"))
                .andExpect(jsonPath("$.data[1].rspnsSeq").value(2))
                .andExpect(jsonPath("$.data[1].responseTitle").value("알고리즘 스터디"))
                // 제목이 순번을 대체하지 않는다 — 같은 이름으로 두 번 낼 수 있어 순번이 여전히 필요하다
                .andExpect(jsonPath("$.data[0].rspnsCn").doesNotExist());
    }

    /*
     * 작성 중(DRAFT)인 응답도 제목을 싣는다. 자동 저장은 검증하지 않으므로(#36) 초안의 답은 언제든
     * 비어 있을 수 있지만, 활동명을 이미 적어 둔 초안이라면 목록에서도 그것으로 알아볼 수 있어야
     * 한다 — 초안을 목록에 싣기로 한 이유(이어 쓸 응답을 찾는다)와 같은 근거다.
     */
    @Test
    void getMyResponsesCarriesTheTitleOfADraft() throws Exception {
        Long formId = saveProposalForm();
        FormEntity form = formRepository.findById(formId).orElseThrow();
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(
                        form, respondent, ResponseContent.of(Map.of("programTitle", "쓰는 중인 기획안"))));

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rspnsSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].responseTitle").value("쓰는 중인 기획안"));
    }

    /*
     * **값이 없으면 null이다.** 대표 문항을 선언하지 않은 평범한 폼도, 그 문항을 비워 둔 응답도
     * 마찬가지이며 서버가 "제목 없음" 같은 대체값을 만들지 않는다 — 웹은 값이 없을 때 종전 문구
     * ("{rspnsSeq}번째 기획안")로 떨어지므로, 서버가 지어낸 문자열은 그 분기를 무력화한다.
     */
    @Test
    void getMyResponsesLeavesResponseTitleNullWhenThereIsNoDeclaredAnswer() throws Exception {
        Long ordinaryFormId = saveMultipleResponseForm("2026 신규모집 지원서");
        submit(ordinaryFormId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        mockMvc.perform(authenticatedGet("/v1/forms/" + ordinaryFormId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].responseTitle").value(Matchers.nullValue()));

        Long proposalFormId = saveProposalForm();
        submit(proposalFormId, "{}").andExpect(status().isCreated());

        mockMvc.perform(authenticatedGet("/v1/forms/" + proposalFormId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].responseTitle").value(Matchers.nullValue()));
    }

    /* ── 내 응답 상세 (#177) ───────────────────────────────── */

    /*
     * 이 이슈의 핵심 한 줄이다 — 제출자가 **자기 답(rspnsCn)과 검토 사유(reviewHistories)**를
     * 함께 읽는다. 그전까지 답은 내 응답 목록이 싣지 않았고 사유를 실은 상세는 RESPONSE_REVIEW에
     * 막혀 본인도 열 수 없었다.
     *
     * 이력은 제출 → 수정요청 → 재제출 순으로 실려야 한다. 제출(SUBMIT) 행이 빠지면 회차가 언제
     * 올라갔는지가 사라져 사유가 어느 제출본에 대한 것인지 알 수 없다.
     */
    @Test
    void getMyResponseReturnsMyAnswersAndReviewHistoriesInOrder() throws Exception {
        Long formId = saveForm("수정요청 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        requestChanges();
        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated());

        mockMvc.perform(myResponse(formId, onlyResponse().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.rspnsSeq").value(1))
                .andExpect(jsonPath("$.data.sbmsnSeq").value(2))
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("김철수"))
                .andExpect(jsonPath("$.data.reviewHistories.length()").value(3))
                .andExpect(jsonPath("$.data.reviewHistories[0].rvwPrcsSeCd").value("SUBMIT"))
                .andExpect(jsonPath("$.data.reviewHistories[0].sbmsnSeq").value(1))
                .andExpect(
                        jsonPath("$.data.reviewHistories[1].rvwPrcsSeCd").value("REQUEST_CHANGES"))
                .andExpect(
                        jsonPath("$.data.reviewHistories[1].rvwOpnnCn")
                                .value("지원 동기를 더 구체적으로 적어주세요."))
                // 처리자_명은 제출자에게도 보인다 (#177 결정 1 — 동아리 내부 결재다)
                .andExpect(jsonPath("$.data.reviewHistories[1].prcsMbrNm").value("김운영"))
                .andExpect(jsonPath("$.data.reviewHistories[2].rvwPrcsSeCd").value("SUBMIT"))
                .andExpect(jsonPath("$.data.reviewHistories[2].sbmsnSeq").value(2));
    }

    /*
     * 인접 응답 식별자는 심사 목록의 이웃이라 **정의상 남의 응답**이다. 내려주면 폼 하나에 누가
     * 응답했는지가 이동 버튼으로 드러난다 — 운영자용 상세와 스키마를 나눈 이유가 이 두 필드다.
     * 응답자 정보(회원 블록)도 함께 확인한다(요청 주체 본인이라 실을 이유가 없다).
     */
    @Test
    void getMyResponseDoesNotExposeNeighbourIdsOrMemberBlock() throws Exception {
        Long formId = saveForm("이웃 노출 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());

        mockMvc.perform(myResponse(formId, onlyResponse().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prevFormRspnsId").doesNotExist())
                .andExpect(jsonPath("$.data.nextFormRspnsId").doesNotExist())
                .andExpect(jsonPath("$.data.member").doesNotExist());
    }

    /*
     * 남의 응답은 없는 응답과 **같은 404**다. 코드를 나누면 식별자를 훑는 것만으로 그 번호의
     * 응답이 존재하는지 알 수 있고, 응답 식별자는 연속된 정수라 훑는 데 비용이 들지 않는다.
     */
    @Test
    void getMyResponseOfAnotherMemberReturns404() throws Exception {
        Long formId = saveForm("남의 응답 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        FormEntity form = formRepository.findById(formId).orElseThrow();
        MemberEntity other = saveMember(UUID.randomUUID(), "20260002", "박민수", "other@sscc.org");
        FormResponseHistoryEntity others =
                formResponseHistoryRepository.saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                form, other, ResponseContent.of(Map.of("q1", "박민수")), NOW));

        String body =
                mockMvc.perform(myResponse(formId, others.getId()))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("박민수");
    }

    @Test
    void getMyResponseOnUnknownResponseReturns404() throws Exception {
        Long formId = saveForm("없는 응답 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);

        mockMvc.perform(myResponse(formId, 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    /*
     * 작성 중(DRAFT) 응답도 열린다. 아직 아무 처리도 없으므로 이력은 **빈 배열이지 null이 아니다**
     * — 상태로 분기해 조회하지 않으면 "이력이 없다"와 "이력을 안 봤다"가 같은 응답이 된다.
     */
    @Test
    void getMyResponseOnDraftReturnsEmptyReviewHistories() throws Exception {
        Long formId = saveForm("작성 중 확인 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        FormEntity form = formRepository.findById(formId).orElseThrow();
        FormResponseHistoryEntity draft =
                formResponseHistoryRepository.saveAndFlush(
                        FormResponseHistoryEntity.createDraft(
                                form, respondent, ResponseContent.of(Map.of("q1", "쓰는 중"))));

        mockMvc.perform(myResponse(formId, draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.sbmsnDt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("쓰는 중"))
                .andExpect(jsonPath("$.data.reviewHistories.length()").value(0));
    }

    /*
     * **접수가 끝난 폼에서도 열린다** — 내 응답 목록과 같은 기준이다. 오히려 이 조회의 실제 쓰임이
     * 마감 뒤에 있다: 기획안은 접수를 마감한 뒤 검토하므로 수정요청 사유를 읽는 시점은 언제나
     * 접수가 끝난 뒤다.
     */
    @Test
    void getMyResponseWorksAfterTheFormIsClosed() throws Exception {
        Long formId = saveForm("마감 후 조회 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        requestChanges();
        close(formId);

        mockMvc.perform(myResponse(formId, onlyResponse().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.data.reviewHistories.length()").value(2));
    }

    /* ── 마감된 폼의 재제출 (#177) ─────────────────────────── */

    /*
     * **이 이슈의 나머지 절반이다.** 기획안은 접수를 마감한 뒤 검토하는 것이 정상 순서라, 마감 후
     * 수정요청을 받은 응답자는 이 예외가 없으면 다시 낼 방법이 아예 없다 — 사유를 읽을 수 있게
     * 열어 두고 재제출이 409로 막히면 화면이 완성되지 않는다.
     *
     * 회차가 오르고 SUBMIT 이력이 남는 것까지 함께 본다 (#141 규칙 회귀). 마감을 건너뛴 경로가
     * 제출의 다른 규칙까지 건너뛰면 그 응답은 이력 없이 상태만 바뀐 행이 된다.
     */
    @Test
    void resubmitAfterChangesRequestedPassesEvenWhenTheFormIsClosed() throws Exception {
        Long formId = saveForm("마감 후 재제출 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        requestChanges();
        close(formId);

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("SUBMITTED"));

        FormResponseHistoryEntity resubmitted = onlyResponse();
        assertThat(resubmitted.getSubmissionSequence()).isEqualTo(2);
        assertThat(resubmitted.getContent().answers()).containsEntry("q1", "김철수");
        assertThat(
                        formResponseReviewHistoryRepository
                                .findAllByResponseOrderByProcessedAtAscIdAsc(resubmitted))
                .extracting(
                        FormResponseReviewHistoryEntity::getAction,
                        FormResponseReviewHistoryEntity::getSubmissionSequence)
                .containsExactly(
                        tuple(ResponseReviewAction.SUBMIT, 1),
                        tuple(ResponseReviewAction.REQUEST_CHANGES, 1),
                        tuple(ResponseReviewAction.SUBMIT, 2));
    }

    /*
     * 접수 기간이 지나 마감된 폼(상태는 OPEN이지만 기간 밖)에서도 같다. 마감 판정은
     * FormReceiptPolicy가 상태와 시간을 함께 보므로 두 경로를 다 확인한다.
     */
    @Test
    void resubmitAfterChangesRequestedPassesAfterTheReceiptPeriodEnds() throws Exception {
        Long formId =
                saveForm(
                        "기간이 끝난 재제출 폼",
                        FormStatus.OPEN,
                        NOW.minusSeconds(864000),
                        NOW.plusSeconds(86400),
                        SAMPLE_COMPOSITION);
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        requestChanges();

        // 접수 종료를 과거로 당긴다 — 상태는 OPEN인 채로 기간만 지난 폼이 된다
        FormEntity form = formRepository.findById(formId).orElseThrow();
        form.update(
                form.getTitle(),
                form.getQuestionComposition(),
                NOW.minusSeconds(864000),
                NOW.minusSeconds(86400),
                false);
        formRepository.saveAndFlush(form);

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isCreated());

        assertThat(onlyResponse().getSubmissionSequence()).isEqualTo(2);
    }

    /*
     * **새 응답은 종전대로 마감에 막힌다.** 열리는 것은 검토자가 부른 응답을 마무리하는 길
     * 하나뿐이며, 다중 응답 폼에 한 건 더 내는 것은 그 길이 아니다 — 여기까지 열면 마감이
     * 아무것도 막지 못하게 된다.
     */
    @Test
    void submittingANewResponseToAClosedFormStillReturns409() throws Exception {
        Long formId = saveMultipleResponseForm("스터디 제안서");
        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isCreated());
        close(formId);

        submit(formId, """
               {"q1": "김철수"}
               """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"));

        assertThat(myResponses()).hasSize(1);
    }

    /*
     * 초안을 내는 것도 **새 제출**이라 마감 판정을 탄다. 예외의 근거가 "검토자의 수정요청에
     * 답하는 것"이라, 응답자가 스스로 쓰던 것을 마감 뒤에 내는 것은 그 근거에 해당하지 않는다.
     */
    @Test
    void submittingADraftToAClosedFormStillReturns409() throws Exception {
        Long formId = saveForm("마감 후 초안 제출 폼", FormStatus.OPEN, null, null, SAMPLE_COMPOSITION);
        FormEntity form = formRepository.findById(formId).orElseThrow();
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(form, respondent, null));
        close(formId);

        submit(formId, """
               {"q1": "홍길동"}
               """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_ACCEPTING"));

        assertThat(onlyResponse().getStatus()).isEqualTo(ResponseStatus.DRAFT);
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

    private MockHttpServletRequestBuilder myResponse(Long formId, Long formResponseId) {
        return authenticatedGet("/v1/forms/" + formId + "/responses/mine/" + formResponseId);
    }

    /** 접수를 마감한다 (#177 표본). 상태만 CLOSED로 바꾸며 접수 기간은 건드리지 않는다 */
    private void close(Long formId) {
        FormEntity form = formRepository.findById(formId).orElseThrow();
        form.changeStatus(FormStatusAction.CLOSE);
        formRepository.saveAndFlush(form);
    }

    private ResultActions submit(Long formId, String answers) throws Exception {
        return mockMvc.perform(
                authenticatedPost(
                        "/v1/forms/" + formId + "/responses", "{\"rspnsCn\": " + answers + "}"));
    }

    /*
     * 검토자의 수정요청을 흉내 낸다 (#141). 검토 API(POST .../reviews)는 RESPONSE_REVIEW 권한을
     * 가진 검토자의 토큰을 요구하는데 이 클래스의 인증 주체는 고정된 응답자라, 응답자 경로만 보는
     * 여기서는 엔티티와 리포지토리로 직접 옮긴다 — 그 경로 자체는 FormResponseControllerTest가 본다.
     */
    private void requestChanges() {
        FormResponseHistoryEntity response = onlyResponse();
        ResponseReviewAction action = response.review(ResponseStatus.CHANGES_REQUESTED);
        formResponseReviewHistoryRepository.save(
                FormResponseReviewHistoryEntity.record(
                        response, action, reviewer, "지원 동기를 더 구체적으로 적어주세요.", NOW));
        formResponseHistoryRepository.flush();
    }

    /** 다중 응답을 허용하는 표본 폼 (#143). 그 밖의 조건은 saveForm과 같다 */
    private Long saveMultipleResponseForm(String title) throws Exception {
        QuestionCompositionContent content =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        return formRepository
                .saveAndFlush(
                        FormEntity.create(
                                respondent, title, content, null, null, FormStatus.OPEN, true))
                .getId();
    }

    /** 검토자의 반려를 흉내 낸다 (requestChanges와 같은 이유로 엔티티를 직접 옮긴다) */
    private void reject() {
        FormResponseHistoryEntity response = onlyResponse();
        ResponseReviewAction action = response.review(ResponseStatus.REJECTED);
        formResponseReviewHistoryRepository.save(
                FormResponseReviewHistoryEntity.record(
                        response, action, reviewer, "이번 회차에는 반영하지 않습니다.", NOW));
        formResponseHistoryRepository.flush();
    }

    /** 인증 주체(응답자)의 응답 전부. 순번 오름차순이라 몇 번째 응답인지로 읽을 수 있다 */
    private List<FormResponseHistoryEntity> myResponses() {
        return formResponseHistoryRepository.findAll().stream()
                .filter(response -> response.getMember().getId().equals(respondent.getId()))
                .sorted(Comparator.comparingInt(FormResponseHistoryEntity::getResponseSequence))
                .toList();
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

    /*
     * 시스템 폼 표본 (#181). FormEntity.create로 만든 뒤 designateAsSystemForm으로 코드를 붙인다 —
     * 요청 본문으로 지정하는 길이 없어(코드가 세우는 유일한 자리다) 시드처럼 엔티티를 직접 세운다.
     */
    private Long saveSystemForm(
            String title,
            String sysFormCd,
            FormStatus status,
            Instant receiptBeginAt,
            Instant receiptEndAt,
            boolean multipleResponseAllowed)
            throws Exception {

        QuestionCompositionContent content =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        FormEntity form =
                FormEntity.create(
                        respondent,
                        title,
                        content,
                        receiptBeginAt,
                        receiptEndAt,
                        status,
                        multipleResponseAllowed);
        form.designateAsSystemForm(sysFormCd);
        return formRepository.saveAndFlush(form).getId();
    }

    /*
     * 대표 문항(programTitle)을 가진 기획안 폼 표본 (#196). 시스템 폼 코드는 리터럴이 아니라
     * ProposalFormSeed의 상수를 쓴다 — 계약이 그 상수를 열쇠로 삼으므로, 문자열을 다시 적으면
     * 선언과 표본이 갈려도 테스트가 초록으로 남는다.
     */
    private Long saveProposalForm() throws Exception {
        QuestionCompositionContent content =
                objectMapper.readValue(PROPOSAL_COMPOSITION, QuestionCompositionContent.class);
        FormEntity form =
                FormEntity.create(
                        respondent, "스터디·프로젝트 기획안", content, null, null, FormStatus.OPEN, true);
        form.designateAsSystemForm(ProposalFormSeed.SYSTEM_FORM_CODE);
        return formRepository.saveAndFlush(form).getId();
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
