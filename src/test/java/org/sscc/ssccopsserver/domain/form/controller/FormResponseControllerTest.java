package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseReviewHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.FormResponseService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
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
 * 운영자용 폼 응답 조회·검토 처리 API(#37 · #141) 통합 검증.
 *
 * 필터체인 전체를 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록 대체한다 (PublicFormControllerTest와
 * 같은 방식). 제출 일시가 정렬과 인접 응답 계산의 기준이라 시각도 고정한다 — 시스템 시각을 쓰면
 * 정렬 표본의 앞뒤가 실행 시점에 따라 흔들린다.
 *
 * 응답 행은 API가 아니라 리포지토리로 직접 만든다. ACCEPTED·REJECTED·DRAFT가 섞인 표본이
 * 필요한데 정상 경로로 만들려면 회원마다 제출 요청 + 상태 변경 요청이 필요해, 검증하려는 것이
 * 준비 코드에 묻힌다.
 *
 * 회원 정보 조인(N+1)을 보기 위해 Hibernate 통계를 켠다 (WorkServiceImplSearchTest 선례).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FormResponseControllerTest.StubJwtDecoderConfig.class)
@Transactional
class FormResponseControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    /** 고정 기준 시각 (2026-03-15 00:00 KST) */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    private static final long ONE_DAY = 86400L;

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
    @Autowired private FormResponseReviewHistoryRepository formResponseReviewHistoryRepository;
    @Autowired private FormResponseService formResponseService;
    @PersistenceContext private EntityManager entityManager;

    private MemberEntity operator;
    private FormEntity form;

    /** 다른 폼. 폼 범위 검사가 실제로 걸리는지 보려면 '남의 폼'이 표본에 있어야 한다 */
    private FormEntity otherForm;

    /** 제출 일시 내림차순으로 최신 → 과거. 목록·인접 응답의 기대 순서가 이 순서다 */
    private Long rejectedId;

    private Long acceptedId;
    private Long submittedId;
    private Long draftId;
    private Long otherFormResponseId;

    @BeforeEach
    void setUp() throws Exception {
        operator = saveMember(AUTH_USER_ID, "20200001", "김운영", "actor@sscc.org");

        // 운영자용 응답 조회·심사는 RESPONSE_REVIEW를 요구한다 (#9). 국장이 OPERATOR로 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                operator,
                MemberRoleFixture.DIRECTOR);

        form = saveForm("2026 신규모집 지원서");
        otherForm = saveForm("2026 임원 모집 지원서");

        submittedId = saveResponse(form, "20260001", "이서연", ResponseStatus.SUBMITTED, 3);
        acceptedId = saveResponse(form, "20260002", "박민수", ResponseStatus.ACCEPTED, 2);
        rejectedId = saveResponse(form, "20260003", "정지우", ResponseStatus.REJECTED, 1);
        draftId = saveResponse(form, "20260004", "최유진", ResponseStatus.DRAFT, 0);

        otherFormResponseId =
                saveResponse(otherForm, "20260005", "한서윤", ResponseStatus.SUBMITTED, 1);
    }

    /* ── 목록 ─────────────────────────────────────────────── */

    /*
     * 이 이슈에서 가장 중요한 기본값이다. 제출 전 답안이 심사 대기 목록에 섞이면 운영자에게는
     * 낸 응답처럼 보이고, 거기서 승인을 누르면 응답자가 아직 쓰고 있던 내용이 그대로 확정된다.
     */
    @Test
    void getResponsesExcludesDraftByDefault() throws Exception {
        mockMvc.perform(authenticatedGet(responsesPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.rspnsSttsCd == 'DRAFT')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.formRspnsId == " + draftId + ")]").isEmpty());
    }

    @Test
    void getResponsesFiltersByStatus() throws Exception {
        mockMvc.perform(authenticatedGet(responsesPath() + "?statusCode=ACCEPTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formRspnsId").value(acceptedId))
                .andExpect(jsonPath("$.data[0].rspnsSttsCd").value("ACCEPTED"));
    }

    // 작성 중 응답을 아예 볼 수 없게 하는 것이 아니라, 명시적으로 골랐을 때만 보이게 하는 것이다
    @Test
    void getResponsesWithDraftStatusReturnsOnlyDrafts() throws Exception {
        mockMvc.perform(authenticatedGet(responsesPath() + "?statusCode=DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formRspnsId").value(draftId))
                // 제출하지 않은 응답은 제출 일시를 가질 수 없다 (ssccops #64)
                .andExpect(jsonPath("$.data[0].sbmsnDt").doesNotExist());
    }

    /*
     * 다중 응답 폼에서는 같은 회원의 응답이 **별도 행**으로 나오고 각 행이 응답 순번을 싣는다
     * (#143). 순번이 없으면 운영자는 이름이 같은 두 줄을 제출 일시로만 구별해야 하고, 응답 하나를
     * 승인하려다 다른 하나를 여는 일이 생긴다.
     *
     * 목록 정렬은 종전대로 제출 일시 내림차순이라 나중에 낸 2번이 먼저 온다 — 순번 오름차순으로
     * 바꾸지 않은 것은 그 정렬이 상세의 이전/다음 이동과 한 벌이기 때문이다.
     */
    @Test
    void getResponsesListsEachResponseOfTheSameMemberSeparately() throws Exception {
        FormEntity multiple = saveMultipleResponseForm("스터디 제안서");
        MemberEntity proposer =
                saveMember(UUID.randomUUID(), "20260010", "박제안", "proposer@sscc.org");
        saveSubmittedResponse(multiple, proposer, 1, NOW.minusSeconds(ONE_DAY));
        saveSubmittedResponse(multiple, proposer, 2, NOW);

        mockMvc.perform(authenticatedGet("/v1/forms/" + multiple.getId() + "/responses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].rspnsSeq").value(2))
                .andExpect(jsonPath("$.data[1].rspnsSeq").value(1))
                .andExpect(jsonPath("$.data[0].member.mbrNm").value("박제안"))
                .andExpect(jsonPath("$.data[1].member.mbrNm").value("박제안"));
    }

    /*
     * 응답자 정보는 응답 행에 복사돼 있지 않고 mbr에서 온다. 회원이 학과를 바꾸면 목록도 함께
     * 바뀌어야 하고, 그래서 웹이 회원 상세로 이동하는 링크를 걸 수 있다.
     */
    @Test
    void getResponsesPopulatesMemberFromMemberTable() throws Exception {
        mockMvc.perform(authenticatedGet(responsesPath() + "?statusCode=SUBMITTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].member.mbrNm").value("이서연"))
                .andExpect(jsonPath("$.data[0].member.stdntNo").value("20260001"))
                .andExpect(jsonPath("$.data[0].member.scsbjtNm").value("컴퓨터학부"))
                .andExpect(jsonPath("$.data[0].member.mbrGrdCd").value("TEMP"))
                .andExpect(jsonPath("$.data[0].member.mbrSttsCd").value("ENROLLED"))
                .andExpect(jsonPath("$.data[0].member.mbrId").isNumber());
    }

    /*
     * 응답 내용은 목록에 싣지 않는다. 문항 수 × 응답 수만큼 곱해져 목록 응답이 비대해지고,
     * 목록 표는 그 값을 쓰지 않는다. 연락처도 같은 이유로 상세에만 있다.
     */
    @Test
    void getResponsesOmitsContentAndPhoneNumber() throws Exception {
        mockMvc.perform(authenticatedGet(responsesPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rspnsCn").doesNotExist())
                .andExpect(jsonPath("$.data[0].member.telno").doesNotExist());
    }

    // 정렬 기본값은 제출 일시 내림차순이다. 상세의 이전/다음 이동이 이 순서를 그대로 쓴다
    @Test
    void getResponsesSortsBySubmittedAtDescending() throws Exception {
        mockMvc.perform(authenticatedGet(responsesPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].formRspnsId").value(rejectedId))
                .andExpect(jsonPath("$.data[1].formRspnsId").value(acceptedId))
                .andExpect(jsonPath("$.data[2].formRspnsId").value(submittedId));
    }

    // 목록은 폼 범위 안에서만 본다 — 다른 폼의 응답이 섞이면 폼별 심사 자체가 성립하지 않는다
    @Test
    void getResponsesDoesNotLeakOtherFormResponses() throws Exception {
        mockMvc.perform(authenticatedGet(responsesPath()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[?(@.formRspnsId == " + otherFormResponseId + ")]")
                                .isEmpty());
    }

    @Test
    void getResponsesOnUnknownFormReturns404() throws Exception {
        mockMvc.perform(authenticatedGet("/v1/forms/999999/responses"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * N+1 회귀 방지. 목록은 응답마다 회원_명·학번·학과·등급·상태를 그리는데, 그때마다 mbr을
     * 따로 조회하면 응답 수 × 3이 그대로 쿼리 수가 된다 (DB-13). 폼 1 + 목록 1로 끝나는지,
     * 그리고 응답이 몇 건이든 그 수가 그대로인지 못 박아 둔다.
     */
    @Test
    void getResponsesRunsTwoQueriesRegardlessOfRowCount() {
        for (int index = 0; index < 5; index++) {
            saveResponse(
                    form,
                    "2027000" + index,
                    "추가 응답자 " + index,
                    ResponseStatus.SUBMITTED,
                    10 + index);
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        List<FormResponseSummaryResponse> responses =
                formResponseService.getResponses(form.getId(), null);

        assertThat(responses).hasSize(8);
        // 회원 정보가 실제로 채워졌는지까지 함께 본다 — 비어 있으면 조인 없이도 쿼리 2회다
        assertThat(responses).allSatisfy(item -> assertThat(item.member().mbrNm()).isNotBlank());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    /* ── 상세 ─────────────────────────────────────────────── */

    @Test
    void getResponseReturnsContentMemberDetailAndNeighbours() throws Exception {
        mockMvc.perform(authenticatedGet(responsePath(acceptedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formRspnsId").value(acceptedId))
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("잘 부탁드립니다."))
                .andExpect(jsonPath("$.data.member.mbrNm").value("박민수"))
                .andExpect(jsonPath("$.data.member.genNo").value(21))
                .andExpect(jsonPath("$.data.member.scyrNo").value(2))
                .andExpect(jsonPath("$.data.member.telno").value("010-1234-5678"))
                // 목록 순서가 rejected → accepted → submitted이므로 이웃도 그 순서다
                .andExpect(jsonPath("$.data.prevFormRspnsId").value(rejectedId))
                .andExpect(jsonPath("$.data.nextFormRspnsId").value(submittedId));
    }

    // 목록의 양 끝. 이웃이 없으면 null이고 웹은 그 값으로 이동 버튼을 비활성화한다
    @Test
    void getResponseAtListEdgesHasNullNeighbour() throws Exception {
        mockMvc.perform(authenticatedGet(responsePath(rejectedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prevFormRspnsId").doesNotExist())
                .andExpect(jsonPath("$.data.nextFormRspnsId").value(acceptedId));

        mockMvc.perform(authenticatedGet(responsePath(submittedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prevFormRspnsId").value(acceptedId))
                .andExpect(jsonPath("$.data.nextFormRspnsId").doesNotExist());
    }

    /*
     * 작성 중 응답은 심사 목록에 들어 있지 않으므로 이웃도 없다. 이웃을 만들어 주면 목록에서
     * 뺀 응답이 이동만으로 심사 흐름 안에 들어와, DRAFT 제외가 목록에서만 지켜지는 규칙이 된다.
     */
    @Test
    void getDraftResponseHasNoNeighbours() throws Exception {
        mockMvc.perform(authenticatedGet(responsePath(draftId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.prevFormRspnsId").doesNotExist())
                .andExpect(jsonPath("$.data.nextFormRspnsId").doesNotExist());
    }

    /*
     * **이 이슈에서 가장 중요한 한 줄이다.** 경로에 formId와 응답 식별자가 둘 다 있는데 응답
     * 식별자만 보고 조회하면 다른 폼의 지원자 답변과 개인정보가 그대로 새어 나간다. 상태 코드뿐
     * 아니라 본문에 남의 폼 응답자의 이름이 실려 있지 않은지도 함께 본다.
     */
    @Test
    void getResponseOfAnotherFormReturns404() throws Exception {
        String body =
                mockMvc.perform(authenticatedGet(responsePath(otherFormResponseId)))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("한서윤").doesNotContain("20260005");
    }

    @Test
    void getUnknownResponseReturns404() throws Exception {
        mockMvc.perform(authenticatedGet(responsePath(999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    /*
     * 처리 이력은 상세와 함께 나간다 (#141). 별도 엔드포인트를 두지 않았으므로 상세 응답이
     * 타임라인을 싣지 않으면 이력이 남아도 화면에 닿을 길이 없다.
     */
    @Test
    void getResponseIncludesReviewTimeline() throws Exception {
        review(submittedId, "CHANGES_REQUESTED", "지원 동기를 더 구체적으로 적어주세요.")
                .andExpect(status().isOk());
        review(submittedId, "ACCEPTED", null).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet(responsePath(submittedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sbmsnSeq").value(1))
                .andExpect(jsonPath("$.data.reviewHistories.length()").value(2))
                // 시간순이므로 먼저 한 처리가 앞이다
                .andExpect(jsonPath("$.data.reviewHistories[0].prcsSeCd").value("REQUEST_CHANGES"))
                .andExpect(
                        jsonPath("$.data.reviewHistories[0].rvwOpnnCn")
                                .value("지원 동기를 더 구체적으로 적어주세요."))
                .andExpect(jsonPath("$.data.reviewHistories[0].sbmsnSeq").value(1))
                // 처리자_명은 이력에 복사돼 있지 않고 mbr에서 조인해 온다
                .andExpect(jsonPath("$.data.reviewHistories[0].prcsMbrNm").value("김운영"))
                .andExpect(jsonPath("$.data.reviewHistories[0].prcsMbrId").isNumber())
                .andExpect(jsonPath("$.data.reviewHistories[0].prcsDt").exists())
                .andExpect(jsonPath("$.data.reviewHistories[1].prcsSeCd").value("ACCEPT"))
                // 승인은 검토 의견이 선택이라 비어 있을 수 있다
                .andExpect(jsonPath("$.data.reviewHistories[1].rvwOpnnCn").doesNotExist());
    }

    // 아직 아무 처리도 없는 응답은 빈 배열이다 — null이면 웹이 두 경우를 따로 다뤄야 한다
    @Test
    void getResponseWithoutAnyReviewHasEmptyTimeline() throws Exception {
        mockMvc.perform(authenticatedGet(responsePath(submittedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewHistories").isArray())
                .andExpect(jsonPath("$.data.reviewHistories.length()").value(0));
    }

    /*
     * N+1 회귀 방지 (#141). 처리 이력은 줄마다 처리자_명을 그리는데 이름을 이력에 복사하지 않고
     * mbr에서 조인하기로 했으므로, 그 대가가 N+1이면 복사하지 않기로 한 결정이 무너진다.
     * 폼 1 + 응답 1 + 인접 식별자 1 + 이력 1로 네 번이고 이력이 몇 줄이든 그대로다.
     */
    @Test
    void getResponseRunsFourQueriesRegardlessOfTimelineLength() {
        /*
         * 이력 행은 API가 아니라 리포지토리로 직접 만든다. 전이표(#141)가 번복을 막아 한 응답에
         * 검토 요청을 여러 번 보낼 수 없고, 재제출은 응답자의 토큰이 필요해 이 테스트의 고정
         * 주체(운영자)로는 만들 수 없다 — 표본을 정상 경로로 만들려면 준비 코드가 검증하려는
         * 것(쿼리 수)을 덮는다.
         */
        FormResponseHistoryEntity response =
                formResponseHistoryRepository.findById(submittedId).orElseThrow();
        for (int index = 0; index < 4; index++) {
            formResponseReviewHistoryRepository.save(
                    FormResponseReviewHistoryEntity.record(
                            response,
                            ResponseReviewAction.REQUEST_CHANGES,
                            operator,
                            "다시 봐주세요 " + index,
                            NOW.plusSeconds(index)));
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        FormResponseDetailResponse detail =
                formResponseService.getResponse(form.getId(), submittedId);

        assertThat(detail.reviewHistories()).hasSize(4);
        // 처리자 이름이 실제로 채워졌는지까지 함께 본다 — 비어 있으면 조인 없이도 쿼리 4회다
        assertThat(detail.reviewHistories())
                .allSatisfy(history -> assertThat(history.prcsMbrNm()).isNotBlank());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
    }

    /* ── 검토 처리 ─────────────────────────────────────────── */

    /*
     * 아직 결론이 나지 않은 응답에서는 세 결론 중 무엇이든 고를 수 있다. 심사가 열려 있는 것은
     * SUBMITTED와 CHANGES_REQUESTED 둘뿐이며, 그 둘에서 나가는 길이 이 테스트의 표본이다.
     */
    @Test
    void reviewFromOpenStatusesReachesEveryConclusion() throws Exception {
        review(submittedId, "CHANGES_REQUESTED", "학과를 다시 확인해주세요.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("CHANGES_REQUESTED"));
        // 수정요청 뒤에도 검토자는 응답자의 재제출을 기다리지 않고 결론을 낼 수 있다
        review(submittedId, "ACCEPTED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("ACCEPTED"));

        Long anotherId = saveResponse(form, "20260011", "조하늘", ResponseStatus.SUBMITTED, 5);
        review(anotherId, "REJECTED", "지원 자격을 충족하지 않습니다.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("REJECTED"));

        assertThat(reload(submittedId).getStatus()).isEqualTo(ResponseStatus.ACCEPTED);
        assertThat(reload(anotherId).getStatus()).isEqualTo(ResponseStatus.REJECTED);
    }

    /*
     * **심사 번복을 없앤 것이 이 이슈의 두 번째 결정이다** (#141). 승인 직후 후속 처리가
     * 시작되므로 — 기획안이 승인되면 활동이 개설되고 역할이 부여된다 — 그 뒤에 승인을 반려로
     * 되돌리면 이미 만들어진 것들을 되돌릴 방법이 없다. 잘못 누른 반려를 승인으로 되돌리는 것도
     * 같은 이유로 막힌다: 오조작의 탈출구는 번복이 아니라 새 응답이다.
     */
    @Test
    void reviewOnConcludedResponseReturns400() throws Exception {
        for (String target : List.of("ACCEPTED", "CHANGES_REQUESTED", "REJECTED")) {
            review(acceptedId, target, "다시 보겠습니다.")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RESPONSE_STATUS_TRANSITION"));
            review(rejectedId, target, "다시 보겠습니다.")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RESPONSE_STATUS_TRANSITION"));
        }

        assertThat(reload(acceptedId).getStatus()).isEqualTo(ResponseStatus.ACCEPTED);
        assertThat(reload(rejectedId).getStatus()).isEqualTo(ResponseStatus.REJECTED);
    }

    /*
     * 같은 상태로의 재지정도 막는다 (#141). #37에서는 "아무것도 바꾸지 않을 뿐"이라며 통과시켰지만,
     * 처리 이력이 생긴 뒤로는 그 요청이 아무것도 바꾸지 않은 이력 한 줄을 남겨 실제 심사 시점을
     * 못 찾게 만든다 (등급·상태 변경의 NO_CHANGE와 같은 이유).
     */
    @Test
    void reviewWithSameStatusReturns400() throws Exception {
        review(submittedId, "CHANGES_REQUESTED", "다시 봐주세요.").andExpect(status().isOk());
        review(submittedId, "CHANGES_REQUESTED", "다시 봐주세요.")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESPONSE_STATUS_TRANSITION"));

        assertThat(reload(submittedId).getStatus()).isEqualTo(ResponseStatus.CHANGES_REQUESTED);
    }

    /*
     * **이 이슈의 핵심 규칙이다.** 수정요청·반려는 응답자에게 무엇을 하라는 통보라 사유 없이
     * 성립하지 않는다. 공백만 있는 문자열도 없는 것으로 본다 — 통과시키면 이력 행은 남지만
     * "왜"가 비어 있어 증거가 되지 못하고, 그 행은 잠겨 있어 나중에 채울 수도 없다.
     */
    @Test
    void reviewWithoutOpinionReturns400ForChangesRequestedAndReject() throws Exception {
        assertOpinionRequired("CHANGES_REQUESTED", null, 0);
        assertOpinionRequired("CHANGES_REQUESTED", "   ", 1);
        assertOpinionRequired("REJECTED", null, 2);
        assertOpinionRequired("REJECTED", "   ", 3);

        // 거절된 요청은 이력도 남기지 않는다. 상태가 함께 되돌아가는지는 FormResponseReviewRollbackTest가
        // 본다 — 트랜잭션을 건 테스트에서는 실제 롤백이 일어나지 않아 여기서는 확인할 수 없다
        assertThat(formResponseReviewHistoryRepository.count()).isZero();
    }

    /*
     * 표본을 경우마다 새로 만드는 것은 트랜잭션을 건 테스트에서 **실제 롤백이 일어나지 않기**
     * 때문이다. 거절된 요청도 영속성 컨텍스트의 상태는 이미 바꿔 놓았으므로, 같은 응답에 두 번
     * 걸면 두 번째는 검토 의견이 아니라 전이 규칙(같은 상태로의 재지정)에 먼저 걸려 무엇을
     * 검증하는 테스트인지가 흐려진다.
     */
    private void assertOpinionRequired(String target, String opinion, int sampleNo)
            throws Exception {
        Long responseId =
                saveResponse(
                        form,
                        "2028000" + sampleNo,
                        "의견 표본 " + sampleNo,
                        ResponseStatus.SUBMITTED,
                        20 + sampleNo);

        review(responseId, target, opinion)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_OPINION_REQUIRED"));
    }

    // 승인은 통보할 것이 없어 검토 의견이 선택이다. 필드를 아예 빼도 된다
    @Test
    void reviewAcceptWithoutOpinionSucceeds() throws Exception {
        mockMvc.perform(
                        authenticatedPost(
                                reviewPath(submittedId), "{\"rspnsSttsCd\": \"ACCEPTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("ACCEPTED"));

        assertThat(reload(submittedId).getStatus()).isEqualTo(ResponseStatus.ACCEPTED);
    }

    // 검토 처리는 응답자 정보를 그대로 돌려준다 — 웹은 재조회로 화면을 맞추지만 본문은 비어 있지 않다
    @Test
    void reviewReturnsChangedResponse() throws Exception {
        review(submittedId, "ACCEPTED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formRspnsId").value(submittedId))
                .andExpect(jsonPath("$.data.member.mbrNm").value("이서연"));
    }

    /*
     * 작성 중 응답을 운영자가 승인하면 응답자가 아직 쓰고 있던 내용이 그대로 심사 결과로 굳는다.
     * DRAFT → SUBMITTED가 여기서도 막히는 것이 요점이다 — 제출은 응답자만 할 수 있는 일이다.
     */
    @Test
    void reviewOnDraftReturns400() throws Exception {
        for (String target : List.of("SUBMITTED", "ACCEPTED", "REJECTED")) {
            review(draftId, target, "사유")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RESPONSE_STATUS_TRANSITION"));
        }

        assertThat(reload(draftId).getStatus()).isEqualTo(ResponseStatus.DRAFT);
    }

    /*
     * 제출된 응답을 DRAFT로 되돌리면 sbmsn_dt가 남아 있는 '미제출' 응답이 생겨 데이터가 스스로
     * 모순된다 (ssccops #64 — DRAFT는 제출 일시가 NULL인 유일한 상태다).
     */
    @Test
    void reviewToDraftReturns400() throws Exception {
        for (Long responseId : List.of(submittedId, acceptedId, rejectedId)) {
            review(responseId, "DRAFT", "사유")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RESPONSE_STATUS_TRANSITION"));
        }

        assertThat(reload(submittedId).getStatus()).isEqualTo(ResponseStatus.SUBMITTED);
        assertThat(reload(submittedId).getSubmittedAt()).isNotNull();
    }

    /*
     * **#37에서 열려 있던 전이 하나가 닫혔다.** 검토자가 응답을 미심사(SUBMITTED)로 되돌리는
     * 요청은 이제 400이다 — 처리 구분 어휘에 검토자가 쓸 SUBMIT이 없어 옮겨 적을 자리가 없고,
     * SUBMIT으로 기록하면 응답자가 한 제출을 검토자 이름으로 남기게 된다. 심사 번복 자체는
     * 승인 ↔ 수정요청 ↔ 반려로 그대로 열려 있고, SUBMITTED로 돌아가는 길은 응답자의 재제출이다.
     */
    @Test
    void reviewToSubmittedReturns400() throws Exception {
        review(acceptedId, "SUBMITTED", "다시 심사하겠습니다.")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESPONSE_STATUS_TRANSITION"));

        assertThat(reload(acceptedId).getStatus()).isEqualTo(ResponseStatus.ACCEPTED);
    }

    // 기준 코드 밖의 값은 enum 역직렬화 실패를 전역 핸들러가 옮긴 것이다 (VL-09)
    @Test
    void reviewWithUnknownCodeReturns400() throws Exception {
        review(submittedId, "APPROVED", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void reviewWithoutStatusReturns400() throws Exception {
        mockMvc.perform(authenticatedPost(reviewPath(submittedId), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 폼 범위 검사는 조회뿐 아니라 쓰기에도 걸린다 — 남의 폼 응답을 심사할 수 있으면 더 나쁘다
    @Test
    void reviewOnAnotherFormsResponseReturns404() throws Exception {
        review(otherFormResponseId, "ACCEPTED", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));

        assertThat(reload(otherFormResponseId).getStatus()).isEqualTo(ResponseStatus.SUBMITTED);
    }

    /* ── 인증 ─────────────────────────────────────────────── */

    /*
     * 세 경로 모두 다른 회원의 학번·연락처·지원서 내용을 다룬다. SecurityConfig의 permitAll
     * 목록(Swagger·헬스 프로브뿐이다)에 걸리지 않아야 한다.
     */
    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        mockMvc.perform(get(responsesPath())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(responsePath(submittedId))).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post(reviewPath(submittedId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"rspnsSttsCd\": \"ACCEPTED\"}"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * 인가는 클래스 전체에 걸린 RESPONSE_REVIEW다 (#9). 검토 처리는 남의 지원서에 결론을 내리는
     * 조작이라 권한이 빠지면 조회보다 나쁘다 — 핸들러가 하나 늘 때 애노테이션을 빠뜨리는 것만으로
     * 열리는 자리를 만들지 않기 위해 클래스에 걸어 두었고, 새 경로도 그 안에 들어 있는지 본다.
     */
    @Test
    void reviewWithoutResponseReviewAuthorityReturns403() throws Exception {
        MemberRoleAssignmentEntity assignment =
                memberRoleAssignmentRepository.findAll().stream()
                        .filter(row -> row.getMember().getId().equals(operator.getId()))
                        .findFirst()
                        .orElseThrow();
        memberRoleAssignmentRepository.delete(assignment);
        entityManager.flush();

        review(submittedId, "ACCEPTED", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(reload(submittedId).getStatus()).isEqualTo(ResponseStatus.SUBMITTED);
    }

    /* ── 준비 ─────────────────────────────────────────────── */

    private String responsesPath() {
        return "/v1/forms/" + form.getId() + "/responses";
    }

    private String responsePath(Long formResponseId) {
        return responsesPath() + "/" + formResponseId;
    }

    private String reviewPath(Long formResponseId) {
        return responsePath(formResponseId) + "/reviews";
    }

    /** 검토 의견이 null이면 필드 자체를 빼지 않고 null로 보낸다 — 웹이 비운 칸을 그렇게 보낸다 */
    private ResultActions review(Long formResponseId, String status, String opinion)
            throws Exception {
        String body =
                "{\"rspnsSttsCd\": \""
                        + status
                        + "\", \"rvwOpnnCn\": "
                        + (opinion == null ? "null" : "\"" + opinion + "\"")
                        + "}";
        return mockMvc.perform(authenticatedPost(reviewPath(formResponseId), body));
    }

    private FormResponseHistoryEntity reload(Long formResponseId) {
        entityManager.flush();
        entityManager.clear();
        return formResponseHistoryRepository.findById(formResponseId).orElseThrow();
    }

    /** 다중 응답을 허용하는 표본 폼 (#143). 그 밖의 조건은 saveForm과 같다 */
    private FormEntity saveMultipleResponseForm(String title) throws Exception {
        QuestionCompositionContent content =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        return formRepository.saveAndFlush(
                FormEntity.create(
                        operator, title, content, null, null, FormStatus.OPEN, true));
    }

    /*
     * 응답 순번을 지정한 제출 응답 (#143). saveResponse와 갈리는 것은 응답자를 새로 만들지
     * 않는다는 점이다 — 다중 응답은 **같은 회원**의 응답이 여러 건인 상황이라 회원을 받아야 한다.
     */
    private Long saveSubmittedResponse(
            FormEntity targetForm,
            MemberEntity respondent,
            int responseSequence,
            Instant submittedAt) {

        return formResponseHistoryRepository
                .saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                targetForm,
                                respondent,
                                ResponseContent.of(Map.of("q1", "제안 " + responseSequence)),
                                submittedAt,
                                responseSequence))
                .getId();
    }

    private FormEntity saveForm(String title) throws Exception {
        QuestionCompositionContent content =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        return formRepository.saveAndFlush(
                FormEntity.create(operator, title, content, null, null, FormStatus.OPEN));
    }

    /*
     * 표본 응답 한 건. daysAgo가 클수록 오래된 제출이라 목록에서 뒤에 온다.
     *
     * ACCEPTED·REJECTED는 제출 뒤 심사로만 도달하는 상태라 엔티티 생성 팩토리가 없다.
     * 제출 행을 만든 뒤 changeStatus로 옮기는 것은 프로덕션 규칙을 그대로 태우는 길이기도 하다 —
     * 테스트 전용 생성 경로를 열면 그 경로만 전이 규칙을 비껴간다.
     */
    private Long saveResponse(
            FormEntity targetForm,
            String studentNumber,
            String name,
            ResponseStatus status,
            long daysAgo) {

        MemberEntity respondent =
                saveMember(UUID.randomUUID(), studentNumber, name, studentNumber + "@sscc.org");

        FormResponseHistoryEntity response =
                status == ResponseStatus.DRAFT
                        ? FormResponseHistoryEntity.createDraft(
                                targetForm, respondent, ResponseContent.of(Map.of("q1", "작성 중")))
                        : FormResponseHistoryEntity.createSubmitted(
                                targetForm,
                                respondent,
                                ResponseContent.of(Map.of("q1", "잘 부탁드립니다.")),
                                NOW.minusSeconds(daysAgo * ONE_DAY));

        formResponseHistoryRepository.saveAndFlush(response);
        if (status == ResponseStatus.ACCEPTED || status == ResponseStatus.REJECTED) {
            response.changeStatus(status);
            formResponseHistoryRepository.flush();
        }
        return response.getId();
    }

    /*
     * 픽스처가 채우지 않는 학과·기수·학년·연락처를 여기서 넣는다. 목록·상세가 mbr에서 그 값을
     * 실제로 끌어오는지 보려면 회원 쪽에 값이 있어야 하고, 비어 있으면 조인이 빠져도 테스트가
     * 통과한다.
     */
    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, String email) {
        MemberEntity member =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        authUserId,
                        studentNumber,
                        name,
                        email);
        member.updateBasicInfo(21, name, "컴퓨터학부", 2, "010-1234-5678", email);
        return memberRepository.saveAndFlush(member);
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
         * 제출 일시가 정렬과 인접 응답 계산의 기준이라 시각을 고정한다. ClockConfig가 정의한
         * clock 빈과 이름이 겹치지 않게 다른 이름으로 둔다 (FormControllerTest 선례).
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
