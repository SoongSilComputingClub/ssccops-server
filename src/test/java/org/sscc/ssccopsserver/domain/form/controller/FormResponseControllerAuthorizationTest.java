package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
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
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/*
 * 운영자용 응답 API의 자격 (#694 · ssccops#614 · ADR-0068).
 *
 * FormResponseController는 원래 클래스 전체가 RESPONSE_REVIEW였다. 조회 넷이 «RESPONSE_REVIEW, 또는
 * 기획안(PROPOSAL)이고 학술 조회 권한»으로 바뀌며 클래스 애노테이션을 걷었으므로, 그것이 지키던 것 —
 * **핸들러를 하나 더할 때 자격을 빠뜨리면 남의 지원서가 열린다** — 을 이제 이 시험이 지킨다
 * (handlersWithoutAnnotationAreExactlyTheReadPolicyOnes).
 *
 * 국원은 **시드된 그 역할을 실제로 받은 회원**이다(MemberRoleFixture.STAFF · V34가 붙인 READ를 본다).
 * 애노테이션·@MockitoBean 구성은 ProposalAttachmentControllerTest와 같다 — 스프링 컨텍스트를 함께
 * 쓴다(ADR-0012). 실패를 기대하는 요청은 테스트마다 마지막에 한 번이다(domain/form/AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class FormResponseControllerAuthorizationTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @MockitoBean private S3Presigner r2Presigner;

    private MemberEntity author;
    private Long proposalFormId;
    private Long proposalResponseId;
    private Long attachmentId;
    private Long otherFormId;
    private Long otherResponseId;

    @BeforeEach
    void setUp() {
        author = saveMember(AUTHOR, "20261201", "작성자");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                saveMember(STAFF, "20261202", "국원"),
                MemberRoleFixture.STAFF);
        // 응답 검토는 RESPONSE_REVIEW다 — 시드된 국장 역할이 닿는다(OPERATOR)
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                saveMember(REVIEWER, "20261203", "학술국장"),
                MemberRoleFixture.DIRECTOR);
        // 권한이 아예 없는 것이 아니라 '다른 권한만' 가져야 인증만으로 통과하지 못한다는 것이 드러난다
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                saveMember(OUTSIDER, "20261204", "다른 운영진"),
                AuthorityCode.WORK_MANAGE);

        FormEntity proposalForm =
                FormEntity.create(
                        author, "스터디·프로젝트 기획안", composition(), null, null, FormStatus.OPEN, true);
        proposalForm.designateAsSystemForm(ProposalFormSeed.SYSTEM_FORM_CODE);
        proposalFormId = formRepository.saveAndFlush(proposalForm).getId();
        proposalResponseId = saveSubmittedResponse(proposalForm);
        attachmentId = saveAttachment(proposalResponseId);

        FormEntity otherForm =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                author, "행사 신청서", composition(), null, null, FormStatus.OPEN));
        otherFormId = otherForm.getId();
        otherResponseId = saveSubmittedResponse(otherForm);

        when(r2Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            GetObjectPresignRequest request = invocation.getArgument(0);
                            return stubGet(request.getObjectRequest().key());
                        });
    }

    /*
     * 클래스 애노테이션이 지키던 것을 대신한다. @RequireAuthority가 없는 핸들러는 첫 줄에서
     * FormResponseReadPolicy를 부르는 조회 넷뿐이어야 한다 — 핸들러를 더했는데 이 집합이 바뀌면, 그
     * 핸들러에 애노테이션을 달거나 정책을 부르고 여기 이름을 더한다(정책을 부르는지는 아래 HTTP 시험이 본다).
     */
    @Test
    void handlersWithoutAnnotationAreExactlyTheReadPolicyOnes() {
        assertThat(
                        AnnotatedElementUtils.findMergedAnnotation(
                                FormResponseController.class, RequireAuthority.class))
                .as("클래스 레벨 애노테이션은 걷었다 — 다시 달면 국원의 기획안 조회가 애스펙트에서 끊긴다")
                .isNull();

        List<String> unannotated =
                Arrays.stream(FormResponseController.class.getDeclaredMethods())
                        .filter(FormResponseControllerAuthorizationTest::isHandler)
                        .filter(
                                method ->
                                        AnnotatedElementUtils.findMergedAnnotation(
                                                        method, RequireAuthority.class)
                                                == null)
                        .map(Method::getName)
                        .toList();

        assertThat(unannotated)
                .containsExactlyInAnyOrder(
                        "getFormResponses",
                        "getFormResponse",
                        "getAttachments",
                        "getAttachmentDownloadUrl");
    }

    // 국원은 기획안 응답을 학술국장과 같은 화면으로 읽는다 — 목록 · 상세 · 첨부 · 내려받기
    @Test
    void staffReadsProposalResponses() throws Exception {
        mockMvc.perform(authorized(get(responsesOf(proposalFormId)), STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].formRspnsId").value(proposalResponseId));
        mockMvc.perform(
                        authorized(
                                get(responsesOf(proposalFormId) + "/" + proposalResponseId), STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formRspnsId").value(proposalResponseId))
                // 연락처는 그와 별개로 MEMBER_MANAGE만 본다(#277) — READ로 새지 않는다
                .andExpect(jsonPath("$.data.member.telno").doesNotExist());
        mockMvc.perform(
                        authorized(
                                get(
                                        responsesOf(proposalFormId)
                                                + "/"
                                                + proposalResponseId
                                                + "/attachments"),
                                STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments[0].fileId").value(attachmentId));
        mockMvc.perform(
                        authorized(
                                get(
                                        responsesOf(proposalFormId)
                                                + "/"
                                                + proposalResponseId
                                                + "/attachments/"
                                                + attachmentId
                                                + "/download-url"),
                                STAFF))
                .andExpect(status().isOk());
    }

    // RESPONSE_REVIEW 보유자는 그대로다 — 기획안이 아닌 폼도 읽는다
    @Test
    void reviewerStillReadsEveryForm() throws Exception {
        mockMvc.perform(authorized(get(responsesOf(otherFormId)), REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].formRspnsId").value(otherResponseId));
        mockMvc.perform(authorized(get(responsesOf(proposalFormId)), REVIEWER))
                .andExpect(status().isOk());
    }

    // 국원에게 열리는 것은 기획안뿐이다 — 다른 폼의 응답은 애노테이션 시절과 같은 403이다
    @Test
    void staffCannotReadOtherForms() throws Exception {
        mockMvc.perform(authorized(get(responsesOf(otherFormId)), STAFF))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void staffCannotReadOtherFormsResponseDetail() throws Exception {
        mockMvc.perform(authorized(get(responsesOf(otherFormId) + "/" + otherResponseId), STAFF))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 없는 폼도 403이다 — RESPONSE_REVIEW 없는 사람에게 폼이 있는지 없는지를 알려주지 않는다
    @Test
    void missingFormIsForbiddenNotNotFoundForStaff() throws Exception {
        mockMvc.perform(authorized(get(responsesOf(Long.MAX_VALUE)), STAFF))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 검토는 RESPONSE_REVIEW만 한다 — 국원은 기획안을 읽기만 한다
    @Test
    void staffCannotReviewProposal() throws Exception {
        mockMvc.perform(
                        authorized(
                                        post(
                                                responsesOf(proposalFormId)
                                                        + "/"
                                                        + proposalResponseId
                                                        + "/reviews"),
                                        STAFF)
                                .content(
                                        """
                                        {"rspnsSttsCd": "CHANGES_REQUESTED", "rvwOpnnCn": "커리큘럼을 보완해 주세요"}
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 학술 조회 권한이 없으면 기획안도 403이다 — 조회가 그냥 열린 것이 아니다
    @Test
    void proposalStaysClosedWithoutReadAuthority() throws Exception {
        mockMvc.perform(authorized(get(responsesOf(proposalFormId)), OUTSIDER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private static boolean isHandler(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    private static String responsesOf(Long formId) {
        return "/v1/forms/" + formId + "/responses";
    }

    // 상태는 정상 경로(제출)로만 만든다 — 테스트 전용 생성 경로가 전이표를 비껴가지 않게
    private Long saveSubmittedResponse(FormEntity form) {
        FormResponseHistoryEntity response =
                FormResponseHistoryEntity.createSubmitted(
                        form,
                        author,
                        ResponseContent.of(Map.of("programTitle", "알고리즘 스터디")),
                        Instant.now(),
                        1);
        return formResponseHistoryRepository.saveAndFlush(response).getId();
    }

    private Long saveAttachment(Long responseId) {
        return fileReferenceRepository
                .saveAndFlush(
                        FileReferenceEntity.attachment(
                                FileTargetType.FORM_RESPONSE,
                                responseId,
                                "form-responses/" + responseId + "/" + UUID.randomUUID() + ".pdf",
                                "스터디 계획서.pdf",
                                204_800,
                                author.getId(),
                                Instant.now()))
                .getId();
    }

    private static QuestionCompositionContent composition() {
        return new QuestionCompositionContent(
                List.of(new QuestionCompositionContent.Page("기획안", null)),
                List.of(
                        new QuestionCompositionContent.QuestionItem(
                                "programTitle",
                                "활동명",
                                QuestionItemType.SHORT_TEXT,
                                false,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)));
    }

    private static PresignedGetObjectRequest stubGet(String objectKey) {
        URI uri =
                URI.create(
                        "https://test-account.r2.cloudflarestorage.com/test-bucket/"
                                + objectKey
                                + "?X-Amz-Signature=stub");
        return PresignedGetObjectRequest.builder()
                .expiration(Instant.now().plusSeconds(900))
                .isBrowserExecutable(true)
                .signedHeaders(Map.of("host", List.of("test-account.r2.cloudflarestorage.com")))
                .httpRequest(SdkHttpRequest.builder().method(SdkHttpMethod.GET).uri(uri).build())
                .build();
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
