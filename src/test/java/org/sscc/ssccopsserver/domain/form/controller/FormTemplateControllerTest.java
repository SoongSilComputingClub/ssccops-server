package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

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
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormTemplateEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormTemplateRepository;
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
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

/*
 * 폼 템플릿 API (#142) 통합 검증.
 *
 * 인증 필터체인을 그대로 태우기 위해 JwtDecoder만 토큰 문자열을 sub로 삼는 Jwt를 반환하도록
 * 대체한다 — 권한이 다른 주체 둘을 같은 테스트에서 쓰려면 토큰마다 회원이 갈려야 하기 때문이다
 * (RoleAuthorityControllerTest와 같은 방식).
 *
 * 확인의 중심은 세 가지다:
 *   1. 템플릿은 폼이 아니다 — 접수 기간·상태·라벨·응답이 오가지 않는다.
 *   2. 템플릿에서 나온 폼은 **독립한다** — 이후 어느 쪽을 고쳐도 다른 쪽이 바뀌지 않는다.
 *   3. 문항 검증기는 폼과 한 벌이다 — 템플릿에서 통과한 구성은 폼 저장에서도 통과한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class FormTemplateControllerTest {

    /** 페이지 한 장·문항 두 개짜리 표본. 선택지·분기·정규식을 한 번에 담아 왕복에서 유형별 속성이 살아 있는지 본다 */
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

    /** 없는 페이지(2)로 분기하는 구성. 폼에서 400이면 템플릿에서도 400이어야 한다 */
    private static final String BRANCH_OUT_OF_RANGE_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "한 장", "pageDescCn": null}],
              "qitems": [
                {
                  "qitemId": "q1", "qitemLblNm": "지원 분야", "qitemTypeCd": "SINGLE_CHOICE",
                  "reqYn": true, "pageSeq": 0, "optionList": ["백엔드"],
                  "branchMap": {"백엔드": 2}
                }
              ]
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;
    @Autowired private FormRepository formRepository;
    @Autowired private FormTemplateRepository formTemplateRepository;
    @Autowired private FormLabelRepository formLabelRepository;
    @Autowired private FormLabelRelationRepository formLabelRelationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private UUID operatorToken;
    private UUID outsiderToken;
    private MemberEntity operator;

    @BeforeEach
    void setUp() {
        operatorToken = UUID.randomUUID();
        operator = saveMember(operatorToken, "20260001", "이서연");

        // 템플릿 API는 클래스 레벨 FORM_WRITE 하나를 요구한다. 국장(OPERATOR)이 FORM_MANAGE 아래로 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                operator,
                MemberRoleFixture.DIRECTOR);

        // 폼과 무관한 권한만 가진 회원 — 조회조차 막히는지 확인하는 데 쓴다
        outsiderToken = UUID.randomUUID();
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                saveMember(outsiderToken, "20260002", "김하늘"),
                AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ CRUD

    /*
     * 생성자는 요청 본문이 아니라 인증 주체에서 오고, 새 템플릿은 항상 활성이다.
     * 문항 수(qitemCnt)는 목록이 구성을 싣지 않는 대신 주는 값이다.
     */
    @Test
    void createTemplateReturns201WithCreatorFromTokenAndActiveFlag() throws Exception {
        mockMvc.perform(
                        authorized(post("/v1/form-templates"), operatorToken)
                                .content(saveBody("신규모집 표준 문항", "매 학기 신규모집에 쓰는 기본 구성")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tmplNm").value("신규모집 표준 문항"))
                .andExpect(jsonPath("$.data.tmplExpln").value("매 학기 신규모집에 쓰는 기본 구성"))
                .andExpect(jsonPath("$.data.useYn").value(true))
                .andExpect(jsonPath("$.data.qitemCnt").value(2))
                .andExpect(jsonPath("$.data.creatrMbrId").value(operator.getId()))
                .andExpect(jsonPath("$.data.creatrMbrNm").value("이서연"))
                // 저장 응답은 문항 구성을 되돌려주지 않는다 (FormSaveResponse와 같은 규칙)
                .andExpect(jsonPath("$.data.qitemCpstCn").doesNotExist());

        assertThat(formTemplateRepository.count()).isEqualTo(1);
    }

    // 목록은 문항 구성을 싣지 않고 이름 오름차순이다 — 폼 목록과 같은 규칙
    @Test
    void templateListIsSortedByNameAndCarriesNoQuestionComposition() throws Exception {
        saveTemplate("행사 신청 문항", true);
        saveTemplate("신규모집 표준 문항", true);

        mockMvc.perform(authorized(get("/v1/form-templates"), operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                // 이름 오름차순: 신규모집 < 행사
                .andExpect(jsonPath("$.data[*].tmplNm", contains("신규모집 표준 문항", "행사 신청 문항")))
                .andExpect(jsonPath("$.data[0].qitemCpstCn").doesNotExist())
                .andExpect(jsonPath("$.data[0].qitemCnt").value(2));
    }

    // 상세는 구성을 통째로 싣는다 — 템플릿 편집기가 이 응답을 초안으로 받아 쓴다
    @Test
    void templateDetailCarriesTheWholeQuestionComposition() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        mockMvc.perform(authorized(get("/v1/form-templates/" + template.getId()), operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qitemCpstCn.pages", hasSize(2)))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems", hasSize(2)))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].ptrnCn").value("^[가-힣]{2,5}$"))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[1].branchMap.백엔드").value(1));
    }

    // 수정은 이름·설명·구성을 통째로 갈아 끼운다. 사용 여부는 본문에 실려도 바뀌지 않는다(필드 자체가 없다)
    @Test
    void updateTemplateReplacesNameDescriptionAndComposition() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        String body =
                """
                {"tmplNm": "2026 신규모집 문항", "tmplExpln": null, "qitemCpstCn": %s, "useYn": false}
                """
                        .formatted(
                                BRANCH_OUT_OF_RANGE_COMPOSITION.replace(
                                        "\"백엔드\": 2", "\"백엔드\": 0"));

        mockMvc.perform(
                        authorized(put("/v1/form-templates/" + template.getId()), operatorToken)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tmplNm").value("2026 신규모집 문항"))
                .andExpect(jsonPath("$.data.tmplExpln").doesNotExist())
                .andExpect(jsonPath("$.data.qitemCnt").value(1))
                // 본문에 useYn을 실어도 무시된다 — 전환은 PATCH .../use 하나뿐이다
                .andExpect(jsonPath("$.data.useYn").value(true));
    }

    @Test
    void unknownTemplateReturnsDedicatedNotFoundCode() throws Exception {
        mockMvc.perform(authorized(get("/v1/form-templates/999999"), operatorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_TEMPLATE_NOT_FOUND"));
    }

    @Test
    void blankTemplateNameIsRejected() throws Exception {
        mockMvc.perform(
                        authorized(post("/v1/form-templates"), operatorToken)
                                .content(saveBody("   ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // DELETE는 두지 않았다 — 내리는 길은 use_yn뿐이다
    @Test
    void deleteEndpointDoesNotExist() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        mockMvc.perform(authorized(delete("/v1/form-templates/" + template.getId()), operatorToken))
                .andExpect(status().isMethodNotAllowed());

        assertThat(formTemplateRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 사용 여부

    /*
     * 비활성으로 내린 템플릿은 '템플릿에서 시작하기' 목록(?useYn=true)에서 빠지지만, 관리 화면이
     * 보는 전체 목록에는 남아 있고 상세·수정도 그대로 된다 — 지우지 않았기 때문이다.
     */
    @Test
    void deactivatedTemplateDropsOutOfActiveListButStaysReadableAndEditable() throws Exception {
        FormTemplateEntity retired = saveTemplate("2025 신규모집 문항", true);
        saveTemplate("행사 신청 문항", true);

        mockMvc.perform(
                        authorized(
                                        patch("/v1/form-templates/" + retired.getId() + "/use"),
                                        operatorToken)
                                .content("{\"useYn\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.useYn").value(false));

        mockMvc.perform(authorized(get("/v1/form-templates"), operatorToken).param("useYn", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].tmplNm").value("행사 신청 문항"));

        mockMvc.perform(authorized(get("/v1/form-templates"), operatorToken))
                .andExpect(jsonPath("$.data", hasSize(2)));

        // 비활성 템플릿도 조회·수정된다 — 오타를 고친 뒤 다시 켜는 것이 정상 경로다
        mockMvc.perform(authorized(get("/v1/form-templates/" + retired.getId()), operatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(
                        authorized(put("/v1/form-templates/" + retired.getId()), operatorToken)
                                .content(saveBody("2025 신규모집 문항(수정)", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.useYn").value(false));
    }

    // 같은 값을 두 번 넣어도 결과가 같다 — 화면의 토글은 두 번 눌릴 수 있다
    @Test
    void togglingUsageIsIdempotent() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(
                            authorized(
                                            patch(
                                                    "/v1/form-templates/"
                                                            + template.getId()
                                                            + "/use"),
                                            operatorToken)
                                    .content("{\"useYn\": false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.useYn").value(false));
        }
    }

    @Test
    void missingUseYnIsRejected() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        mockMvc.perform(
                        authorized(
                                        patch("/v1/form-templates/" + template.getId() + "/use"),
                                        operatorToken)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ 템플릿 → 폼

    /*
     * **이 이슈가 열어 주는 동작.** 만들어진 폼은 DRAFT이고 접수 기간·라벨이 비어 있으며,
     * 문항 구성은 템플릿의 것을 그대로 갖는다. 본문을 생략하면 제목은 템플릿명이다.
     */
    @Test
    void createFormFromTemplateProducesDraftFormWithoutPeriodOrLabels() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        String response =
                mockMvc.perform(
                                authorized(
                                        post("/v1/form-templates/" + template.getId() + "/forms"),
                                        operatorToken))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.data.formTmplId").value(template.getId()))
                        // 본문을 생략하면 제목은 템플릿명이다
                        .andExpect(jsonPath("$.data.formTtlNm").value("신규모집 표준 문항"))
                        .andExpect(jsonPath("$.data.formSttsCd").value("DRAFT"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Long formId = JsonPath.parse(response).read("$.data.formId", Long.class);
        mockMvc.perform(authorized(get("/v1/forms/" + formId), operatorToken))
                .andExpect(jsonPath("$.data.formSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.rcptBgngDt").doesNotExist())
                .andExpect(jsonPath("$.data.rcptEndDt").doesNotExist())
                .andExpect(jsonPath("$.data.labels", hasSize(0)))
                .andExpect(jsonPath("$.data.responseCount").value(0))
                // 문항 구성은 템플릿의 것을 그대로 물려받는다
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems", hasSize(2)))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemId").value("q1"))
                // 생성자는 이 조작을 수행한 회원이다
                .andExpect(jsonPath("$.data.creatrMbrId").value(operator.getId()));
    }

    @Test
    void createFormFromTemplateUsesGivenTitleWhenProvided() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        mockMvc.perform(
                        authorized(
                                        post("/v1/form-templates/" + template.getId() + "/forms"),
                                        operatorToken)
                                .content("{\"formTtlNm\": \"2026-2 신규모집 지원서\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.formTtlNm").value("2026-2 신규모집 지원서"));
    }

    /*
     * **깊은 복사의 증명.** 템플릿에서 만든 폼의 문항을 고쳐도 템플릿은 그대로여야 한다.
     * 얕게 넘겼다면 Hibernate가 두 행에 같은 JSON을 써 여기서 걸린다.
     */
    @Test
    void editingAFormBornFromATemplateDoesNotChangeTheTemplate() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        String created =
                mockMvc.perform(
                                authorized(
                                        post("/v1/form-templates/" + template.getId() + "/forms"),
                                        operatorToken))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long formId = JsonPath.parse(created).read("$.data.formId", Long.class);

        // 폼의 문항을 한 개짜리로 갈아 끼운다
        String body =
                """
                {"formTtlNm": "고친 폼", "qitemCpstCn": %s}
                """
                        .formatted(
                                BRANCH_OUT_OF_RANGE_COMPOSITION.replace(
                                        "\"백엔드\": 2", "\"백엔드\": 0"));
        mockMvc.perform(authorized(put("/v1/forms/" + formId), operatorToken).content(body))
                .andExpect(status().isOk());

        flushAndClear();

        mockMvc.perform(authorized(get("/v1/form-templates/" + template.getId()), operatorToken))
                .andExpect(jsonPath("$.data.qitemCnt").value(2))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems", hasSize(2)));
    }

    // 비활성 템플릿으로는 새 폼을 시작할 수 없다 — 라벨의 FORM_LABEL_NOT_USABLE과 같은 규칙
    @Test
    void createFormFromInactiveTemplateIsRejected() throws Exception {
        FormTemplateEntity retired = saveTemplate("2025 신규모집 문항", false);

        mockMvc.perform(
                        authorized(
                                post("/v1/form-templates/" + retired.getId() + "/forms"),
                                operatorToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_TEMPLATE_NOT_USABLE"));

        assertThat(formRepository.count()).isZero();
    }

    @Test
    void createFormFromUnknownTemplateReturnsNotFound() throws Exception {
        mockMvc.perform(authorized(post("/v1/form-templates/999999/forms"), operatorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_TEMPLATE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 폼 → 템플릿

    /*
     * 폼의 현재 문항 구성만 옮겨 간다. 접수 기간·상태·라벨은 템플릿에 담을 자리가 없다 —
     * 그 사실이 form_tmpl을 별도 테이블로 나눈 이유 그 자체다.
     */
    @Test
    void saveFormAsTemplateCopiesOnlyTheQuestionComposition() throws Exception {
        FormEntity form = saveForm("2026 신규모집 지원서");
        assignLabel(form, "신규모집");

        String response =
                mockMvc.perform(
                                authorized(
                                                post("/v1/forms/" + form.getId() + "/templates"),
                                                operatorToken)
                                        .content("{\"tmplExpln\": \"올해 쓰던 구성\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        // tmplNm을 생략하면 폼 제목을 쓴다
                        .andExpect(jsonPath("$.data.tmplNm").value("2026 신규모집 지원서"))
                        .andExpect(jsonPath("$.data.tmplExpln").value("올해 쓰던 구성"))
                        .andExpect(jsonPath("$.data.useYn").value(true))
                        .andExpect(jsonPath("$.data.qitemCnt").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Long templateId = JsonPath.parse(response).read("$.data.formTmplId", Long.class);
        mockMvc.perform(authorized(get("/v1/form-templates/" + templateId), operatorToken))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemId").value("q1"))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[1].optionList", hasSize(2)));

        // 폼에 걸린 라벨은 템플릿으로 따라가지 않는다 (담을 컬럼 자체가 없다)
        flushAndClear();
        assertThat(formLabelRelationRepository.count()).isEqualTo(1);
    }

    @Test
    void saveFormAsTemplateUsesGivenNameWhenProvided() throws Exception {
        FormEntity form = saveForm("2026 신규모집 지원서");

        mockMvc.perform(
                        authorized(post("/v1/forms/" + form.getId() + "/templates"), operatorToken)
                                .content("{\"tmplNm\": \"신규모집 표준 문항\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tmplNm").value("신규모집 표준 문항"));
    }

    // 폼과 템플릿도 구성을 공유하지 않는다 — 반대 방향의 깊은 복사
    @Test
    void editingAFormAfterSavingItAsTemplateDoesNotChangeTheTemplate() throws Exception {
        FormEntity form = saveForm("2026 신규모집 지원서");

        String created =
                mockMvc.perform(
                                authorized(
                                        post("/v1/forms/" + form.getId() + "/templates"),
                                        operatorToken))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long templateId = JsonPath.parse(created).read("$.data.formTmplId", Long.class);

        String body =
                """
                {"formTtlNm": "고친 폼", "qitemCpstCn": %s}
                """
                        .formatted(
                                BRANCH_OUT_OF_RANGE_COMPOSITION.replace(
                                        "\"백엔드\": 2", "\"백엔드\": 0"));
        mockMvc.perform(authorized(put("/v1/forms/" + form.getId()), operatorToken).content(body))
                .andExpect(status().isOk());

        flushAndClear();

        mockMvc.perform(authorized(get("/v1/form-templates/" + templateId), operatorToken))
                .andExpect(jsonPath("$.data.qitemCnt").value(2));
    }

    @Test
    void saveUnknownFormAsTemplateReturnsFormNotFound() throws Exception {
        mockMvc.perform(authorized(post("/v1/forms/999999/templates"), operatorToken))
                .andExpect(status().isNotFound())
                // 폼 쪽은 #31이 정한 공통 NOT_FOUND를 그대로 쓴다 — 템플릿 전용 코드와 갈려야 한다
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 검증기 공유

    /*
     * **이 이슈의 핵심 제약(BR-M28).** 폼에서 400이 나는 구성은 템플릿에서도 400이어야 한다.
     * 템플릿 전용 검증기를 한 벌 더 두면 여기서 201이 나고, 그렇게 만든 템플릿으로 폼을 만들면
     * 사용자는 자기가 만들지 않은 폼의 저장 실패를 마주한다.
     */
    @Test
    void templateRejectsTheSameCompositionTheFormRejects() throws Exception {
        String body =
                """
                {"formTtlNm": "분기가 깨진 폼", "qitemCpstCn": %s}
                """
                        .formatted(BRANCH_OUT_OF_RANGE_COMPOSITION);
        mockMvc.perform(authorized(post("/v1/forms"), operatorToken).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUESTION_COMPOSITION"));

        mockMvc.perform(
                        authorized(post("/v1/form-templates"), operatorToken)
                                .content(
                                        """
                                        {"tmplNm": "분기가 깨진 템플릿", "qitemCpstCn": %s}
                                        """
                                                .formatted(BRANCH_OUT_OF_RANGE_COMPOSITION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUESTION_COMPOSITION"));
    }

    /*
     * 같은 규칙의 반대편. 템플릿에서 나온 폼은 상세 조회 응답을 그대로 되돌려 보내는
     * 편집 자동 저장(PUT)에서도 통과해야 한다 — 검증기가 두 벌이면 여기서 400이 난다.
     */
    @Test
    void formBornFromTemplateSurvivesItsOwnSaveRoundTrip() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        String created =
                mockMvc.perform(
                                authorized(
                                        post("/v1/form-templates/" + template.getId() + "/forms"),
                                        operatorToken))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long formId = JsonPath.parse(created).read("$.data.formId", Long.class);

        String detail =
                mockMvc.perform(authorized(get("/v1/forms/" + formId), operatorToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Object composition = JsonPath.parse(detail).read("$.data.qitemCpstCn");

        String body =
                """
                {"formTtlNm": "템플릿에서 만든 폼", "qitemCpstCn": %s}
                """
                        .formatted(objectMapper.writeValueAsString(composition));

        mockMvc.perform(authorized(put("/v1/forms/" + formId), operatorToken).content(body))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 인가

    // 토큰 없는 호출은 인증에서 끊긴다
    @Test
    void requestWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/form-templates")).andExpect(status().isUnauthorized());
    }

    /*
     * **조회도 예외가 아니다.** 클래스 레벨 @RequireAuthority(FORM_WRITE)라 FORM_WRITE가 없는
     * 회원은 목록·상세도 받지 못한다 — 핸들러가 하나 늘 때 애노테이션을 빠뜨릴 자리를 만들지
     * 않겠다는 결정이 실제로 조회에까지 걸려 있는지 확인한다.
     */
    @Test
    void callerWithoutFormWriteIsForbiddenEvenForReads() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        mockMvc.perform(authorized(get("/v1/form-templates"), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(authorized(get("/v1/form-templates/" + template.getId()), outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void callerWithoutFormWriteCannotCreateFormFromTemplate() throws Exception {
        FormTemplateEntity template = saveTemplate("신규모집 표준 문항", true);

        mockMvc.perform(
                        authorized(
                                post("/v1/form-templates/" + template.getId() + "/forms"),
                                outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(post("/v1/forms/1/templates"), outsiderToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 헬퍼

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

    private FormTemplateEntity saveTemplate(String name, boolean active) {
        FormTemplateEntity template =
                FormTemplateEntity.create(operator, name, null, sampleComposition());
        template.changeActive(active);
        return formTemplateRepository.saveAndFlush(template);
    }

    private FormEntity saveForm(String title) {
        return formRepository.saveAndFlush(
                FormEntity.create(operator, title, sampleComposition(), null, null));
    }

    private void assignLabel(FormEntity form, String labelName) {
        FormLabelEntity label = formLabelRepository.saveAndFlush(FormLabelEntity.create(labelName));
        formLabelRelationRepository.saveAndFlush(FormLabelRelationEntity.create(form, label));
    }

    /*
     * 표본 구성을 엔티티에 직접 넣을 때 쓰는 값. 컨트롤러를 거치지 않으므로 검증기를 타지
     * 않지만, 이미 검증기를 통과하는 모양으로 적어 두었다.
     */
    private static QuestionCompositionContent sampleComposition() {
        return new QuestionCompositionContent(
                List.of(
                        new QuestionCompositionContent.Page("기본 정보", "지원자 정보를 입력해주세요."),
                        new QuestionCompositionContent.Page("상세", null)),
                List.of(
                        new QuestionCompositionContent.QuestionItem(
                                "q1",
                                "이름",
                                QuestionItemType.SHORT_TEXT,
                                true,
                                0,
                                List.of(),
                                null,
                                "^[가-힣]{2,5}$",
                                "한글 이름",
                                "한글 2~5자",
                                null),
                        new QuestionCompositionContent.QuestionItem(
                                "q2",
                                "지원 분야",
                                QuestionItemType.SINGLE_CHOICE,
                                true,
                                0,
                                List.of("백엔드", "프론트엔드"),
                                Map.of("백엔드", 1),
                                null,
                                null,
                                null,
                                null)));
    }

    private static String saveBody(String name, String description) {
        return """
               {"tmplNm": "%s", "tmplExpln": %s, "qitemCpstCn": %s}
               """
                .formatted(
                        name,
                        description == null ? "null" : "\"" + description + "\"",
                        VALID_COMPOSITION);
    }

    /*
     * 테스트가 한 트랜잭션 안에서 돌아 서비스와 영속성 컨텍스트를 공유한다. 깊은 복사가 정말
     * DB까지 갈렸는지 보려면 1차 캐시를 비우고 다시 읽어야 한다.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
