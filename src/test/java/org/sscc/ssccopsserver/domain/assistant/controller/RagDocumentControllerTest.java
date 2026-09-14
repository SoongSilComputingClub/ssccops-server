package org.sscc.ssccopsserver.domain.assistant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
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
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/*
 * 규정 문서 업로드 API (#399 · 상위 ssccops#326).
 *
 * 확인의 중심은 **순서와 그 순서가 실패했을 때 남기는 것**이다 — 파싱이 R2 PUT보다 먼저라
 * 거절된 요청은 오브젝트도 행도 남기지 않아야 하고, 통과한 요청은 «대기» 배지를 그릴 수 있는
 * 201을 돌려줘야 한다. 그래서 400·413·429를 보는 테스트는 저장소가 비어 있음과 `putObject`가
 * 불리지 않았음을 함께 본다.
 *
 * **S3Client를 목으로 갈아 끼운다** — 진짜 빈을 두면 테스트가 R2 자격을 요구하고(그 값은
 * 저장소에 없다), 여기서 확인하려는 것은 업로드 알고리즘이 아니라 **무엇을 어느 키로 올리는가**다.
 *
 * **기능 플래그를 `properties`로 켠다.** `application-test.yaml`에 켜 두지 않은 것은 «test
 * 프로필도 켜지 않는다»가 `AssistantWiringTest`가 지키는 사실이기 때문이며, 여기서는 위의
 * `@MockitoBean`이 이미 이 클래스만의 컨텍스트를 만들고 있어 **켜는 대가가 0이다**(#103이 줄여
 * 놓은 컨텍스트 수가 이것 때문에 늘지 않는다). 꺼진 상태의 404는 컨텍스트 없이
 * `AssistantFeatureTest`가 본다.
 *
 * 서비스가 예외를 던지면 참여 중인 테스트 트랜잭션이 rollback-only로 표시되므로, 거절을 보는
 * 테스트는 실패하는 요청 하나로 끝낸다(MemberImportControllerTest와 같은 이유).
 */
@SpringBootTest(properties = "ssccops.assistant.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class RagDocumentControllerTest {

    private static final String DOCUMENTS = "/v1/assistant/documents";

    /** 계약을 지키는 최소 회칙 — 장 하나·조 하나 */
    private static final String VALID_MARKDOWN =
            """
            # SSCC 동아리 회칙

            ## 제1장 총칙

            ### 제1조 (명칭)

            본 회의 명칭은 숭실 컴퓨팅 클럽이라 한다.
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private RagDocumentRepository ragDocumentRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository roleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @MockitoBean private S3Client r2Client;

    private MemberEntity manager;
    private UUID managerToken;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260401", "규정관리자");
        grant(manager, AuthorityCode.RAG_DOCUMENT_MANAGE);

        // 권한이 아예 없는 쪽이 아니라 '다른 권한만' 가진 쪽이어야 403이 권한 때문임이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260402", "업무담당"), AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ 통과

    /*
     * `.md` 한 건이 **PENDING · DRAFT · 1판본**으로 들어간다.
     *
     * 화면이 이 응답만으로 목록의 «대기» 행을 그리므로(§13.2) 그 행이 필요로 하는 값이 전부
     * 실려야 한다 — 표시명·크기·청크(아직 null)·등록일·두 상태.
     */
    @Test
    void createsPendingDraftFirstVersionFromMarkdown() throws Exception {
        mockMvc.perform(upload(markdown("회칙.md", VALID_MARKDOWN), "REGULATION", null))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.documentCode").value("REGULATION"))
                .andExpect(jsonPath("$.data.docType").value(RagDocumentType.STRUCTURED.name()))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.indexStatus").value(RagIndexStatus.PENDING.name()))
                .andExpect(jsonPath("$.data.applyStatus").value(RagApplyStatus.DRAFT.name()))
                .andExpect(jsonPath("$.data.originalFileName").value("회칙.md"))
                .andExpect(
                        jsonPath("$.data.fileSize")
                                .value(VALID_MARKDOWN.getBytes(StandardCharsets.UTF_8).length))
                // 색인 전이라 청크 수는 비어 있다 — 화면은 그 자리에 «—»를 그린다
                .andExpect(jsonPath("$.data.chunkCount").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").exists())
                // 표시명의 기본값은 파일명에서 확장자를 뗀 것
                .andExpect(jsonPath("$.data.name").value("회칙"));

        RagDocumentEntity saved = ragDocumentRepository.findAll().get(0);
        assertThat(saved.getRegistrant().getId())
                .as("올린 회원은 요청 본문이 아니라 인증 주체에서 온다 (#78)")
                .isEqualTo(manager.getId());
    }

    /*
     * **원본이 R2에 올라가고 그 키가 `file_rfrnc`에 남는다** (#220 · #402).
     *
     * 키가 `rag-documents/{ragDocId}/{uuid}.md`인 것이 계약이다 — 원본 파일명을 키에 쓰지 않는
     * 것은 같은 버킷에 얼굴이 찍힌 출석 인증사진이 있기 때문이고(ssccops#156), 식별자를 넣는
     * 것은 버킷만 보고도 어느 판본의 원본인지 알 수 있게 하기 위해서다.
     */
    @Test
    void storesOriginalUnderRagDocumentsKeyAndRecordsFileReference() throws Exception {
        mockMvc.perform(upload(markdown("회칙.md", VALID_MARKDOWN), "REGULATION", null))
                .andExpect(status().isCreated());

        Long ragDocId = ragDocumentRepository.findAll().get(0).getId();

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(r2Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key())
                .matches("rag-documents/" + ragDocId + "/[0-9a-f-]{36}\\.md");
        assertThat(put.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(put.getValue().contentType())
                .as("요청이 신고한 MIME이 아니라 확장자 표의 값을 싣는다")
                .isEqualTo("text/markdown");

        List<FileReferenceEntity> references = fileReferenceRepository.findAll();
        assertThat(references).hasSize(1);
        assertThat(references.get(0).getTargetType()).isEqualTo(FileTargetType.RAG_DOCUMENT);
        assertThat(references.get(0).getTargetId()).isEqualTo(ragDocId);
        assertThat(references.get(0).objectKey()).isEqualTo(put.getValue().key());
    }

    /*
     * 같은 `doc_cd`로 다시 올리면 **직전 판본 + 1**이다. 제목이 아니라 이 코드가 판본을 묶으므로
     * 표시명을 달리 줘도 같은 문서의 2판본이다.
     */
    @Test
    void secondUploadOfSameDocumentCodeBecomesNextVersion() throws Exception {
        mockMvc.perform(upload(markdown("회칙.md", VALID_MARKDOWN), "REGULATION", null))
                .andExpect(status().isCreated());

        mockMvc.perform(upload(markdown("회칙_개정안.md", VALID_MARKDOWN), "regulation", "2026 개정안"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(2))
                // 소문자로 보내도 같은 문서다 — 눈으로 같아 보이는 두 값이 다른 문서가 되면 안 된다
                .andExpect(jsonPath("$.data.documentCode").value("REGULATION"))
                .andExpect(jsonPath("$.data.name").value("2026 개정안"));

        assertThat(ragDocumentRepository.findMaxVersion("REGULATION")).contains((short) 2);
    }

    /** `.pdf`는 평문 갈래다 — 유형을 요청이 신고하지 않고 확장자가 정한다(#210과 같은 판단) */
    @Test
    void classifiesPdfAsGenericFromItsExtension() throws Exception {
        mockMvc.perform(upload(resource("rag/regulation-current.pdf"), "SCHOOL_RULE", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docType").value(RagDocumentType.GENERIC.name()))
                .andExpect(jsonPath("$.data.indexStatus").value(RagIndexStatus.PENDING.name()));

        verify(r2Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ------------------------------------------------------------------ 거절

    /*
     * **`.md` 계약 위반은 그 자리에서 400이고 «몇째 줄이 왜»가 실린다**(§13.2).
     *
     * 파싱을 워커로 미루지 않은 이유가 이것이며, 파싱이 R2 PUT보다 먼저라 **오브젝트도 행도
     * 남지 않는다** — 뒤집으면 고아 오브젝트가 남는다.
     */
    @Test
    void rejectsMarkdownThatBreaksTheContractBeforeTouchingR2() throws Exception {
        mockMvc.perform(
                        upload(
                                markdown("회칙.md", "# 회칙\n\n### 제1조 (명칭)\n\n본 회의 명칭.\n"),
                                "REGULATION",
                                null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAG_DOCUMENT_PARSE_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("3번째 줄")));

        assertThat(ragDocumentRepository.count()).isZero();
        verify(r2Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 텍스트가 한 글자도 없는 스캔 PDF도 같은 코드다 — 색인 완료인데 아무 답도 못 하는 행을 만들지 않는다 */
    @Test
    void rejectsScannedPdfWithNoText() throws Exception {
        mockMvc.perform(upload(resource("rag/scanned-no-text.pdf"), "SCHOOL_RULE", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAG_DOCUMENT_PARSE_FAILED"));

        assertThat(ragDocumentRepository.count()).isZero();
        verify(r2Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 받는 형식 밖은 파싱 실패와 코드가 갈린다 — 운영진이 할 일이 «형식을 바꾼다»로 다르다 */
    @Test
    void rejectsUnsupportedExtension() throws Exception {
        mockMvc.perform(
                        upload(
                                file("학칙.hwp", "application/octet-stream", "본문"),
                                "SCHOOL_RULE",
                                null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAG_DOCUMENT_UNSUPPORTED_TYPE"));

        assertThat(ragDocumentRepository.count()).isZero();
        verify(r2Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /*
     * 10MB를 넘으면 413이다. **서블릿 상한(16MB)이 아니라 도메인이 끊으므로** 응답에 도메인
     * 오류 코드가 붙는다 — 서블릿이 먼저 걸러 버리면 화면이 무엇이 잘못됐는지 안내하지 못한다(#84).
     */
    @Test
    void rejectsFileLargerThanTenMegabytes() throws Exception {
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];

        mockMvc.perform(
                        upload(
                                new MockMultipartFile("file", "큰회칙.md", "text/markdown", tooLarge),
                                "REGULATION",
                                null))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("RAG_DOCUMENT_TOO_LARGE"));

        assertThat(ragDocumentRepository.count()).isZero();
        verify(r2Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /*
     * **적재는 회원당 일 10회다**(§11) — 그 요청 하나가 나중에 임베딩을 수백 번 부른다.
     *
     * 세는 것은 요청이 아니라 오늘 올라간 행이므로, 이미 10건이 있으면 열한 번째가 429다.
     */
    @Test
    void rejectsEleventhUploadOfTheDay() throws Exception {
        for (int i = 1; i <= 10; i++) {
            ragDocumentRepository.saveAndFlush(
                    RagDocumentEntity.register(
                            "GUIDE" + i,
                            "지침 " + i,
                            RagDocumentType.STRUCTURED,
                            RagDocumentEntity.FIRST_VERSION,
                            "guide" + i + ".md",
                            10,
                            manager));
        }

        mockMvc.perform(upload(markdown("회칙.md", VALID_MARKDOWN), "REGULATION", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ASSISTANT_RATE_LIMITED"));

        verify(r2Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /*
     * **권한이 없으면 403이고 코퍼스에 닿지 못한다.** 클래스 레벨 `@RequireAuthority`가 하는 일이며,
     * 코퍼스를 바꾸는 것은 모든 답변의 근거를 갈아치우는 조작이다(ADR-0029 · §6.4).
     */
    @Test
    void rejectsUploadWithoutRagDocumentManageAuthority() throws Exception {
        MockMultipartHttpServletRequestBuilder request =
                multipart(DOCUMENTS).file(markdown("회칙.md", VALID_MARKDOWN));
        request.param("documentCode", "REGULATION");

        mockMvc.perform(authorized(request, outsiderToken)).andExpect(status().isForbidden());

        assertThat(ragDocumentRepository.count()).isZero();
        verify(r2Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** `doc_cd`는 판본을 묶는 열쇠라 모양을 본다 — 공백·한글이 섞이면 같은 문서가 둘로 갈린다 */
    @Test
    void rejectsMalformedDocumentCode() throws Exception {
        mockMvc.perform(upload(markdown("회칙.md", VALID_MARKDOWN), "회칙 2026", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(ragDocumentRepository.count()).isZero();
        verify(r2Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ------------------------------------------------------------------ 도우미

    private MockMultipartHttpServletRequestBuilder upload(
            MockMultipartFile file, String documentCode, String name) {

        MockMultipartHttpServletRequestBuilder builder = multipart(DOCUMENTS).file(file);
        if (documentCode != null) {
            builder.param("documentCode", documentCode);
        }
        if (name != null) {
            builder.param("name", name);
        }
        return authorized(builder, managerToken);
    }

    private static MockMultipartHttpServletRequestBuilder authorized(
            MockMultipartHttpServletRequestBuilder builder, UUID authUserId) {
        builder.header("Authorization", "Bearer " + authUserId);
        return builder;
    }

    private static MockMultipartFile markdown(String fileName, String content) {
        return file(fileName, "text/markdown", content);
    }

    private static MockMultipartFile file(String fileName, String contentType, String content) {
        return new MockMultipartFile(
                "file", fileName, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 골든셋 문서를 그대로 올린다 — 파싱 갈래가 실제 파일에서 갈리는지 보는 것이 요점이다 */
    private static MockMultipartFile resource(String path) throws IOException {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            return new MockMultipartFile(
                    "file", fileName, "application/pdf", stream.readAllBytes());
        }
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                roleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }
}
