package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatusAction;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
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

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 기획안 첨부 API (server#685 · ssccops#607) — 작성자 · 검토자 · 학술 활동 세 경로.
 *
 * 운영 건 첨부 테스트(OperationAttachmentControllerTest)와 같은 뼈대다 — R2 presigner만 스텁하고 나머지는
 * 실제 컨텍스트다. **애노테이션·@MockitoBean 구성을 그 클래스와 똑같이 둔다** — 그래야 스프링 테스트
 * 컨텍스트를 새로 띄우지 않고 그 캐시를 함께 쓴다(ADR-0012 · 컨텍스트 하나가 힙을 먹는다). 그래서 시계도
 * 고정하지 않는다: 기획안 폼은 접수 기간을 비운 OPEN이라 «접수 중»이 시각에 달려 있지 않다.
 *
 * 이미 붙은 첨부는 API가 아니라 저장소로 직접 만든다(용량·개수 경계를 맞추려면 정확한 크기의 행이
 * 필요하다). 트랜잭션 테스트라 **실패하는 요청은 각 테스트의 끝에만 둔다**(domain/form/AGENTS.md 테스트
 * 함정 — 실패 뒤의 성공 요청은 rollback-only에 걸린다). 동시 발급 · 감사 줄 · 초안 일괄 삭제처럼 커밋이
 * 있어야 보이는 것은 ProposalAttachmentCommitTest가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class ProposalAttachmentControllerTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();

    private static final long MB = 1024L * 1024;
    private static final long MAX_TOTAL = 20 * MB;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @MockitoBean private S3Presigner r2Presigner;

    /** 서명에 실제로 들어간 PUT 요청 — 상한으로 넘긴 남은 용량과 Content-Length를 본다 */
    private final List<PutObjectPresignRequest> presignedPuts = new ArrayList<>();

    private MemberEntity author;
    private MemberEntity other;
    private FormEntity proposalForm;
    private Long formId;
    private Long draftId;

    @BeforeEach
    void setUp() {
        author = saveMember(AUTHOR, "20261001", "작성자");
        other = saveMember(OTHER, "20261002", "부원");
        MemberEntity reviewer = saveMember(REVIEWER, "20261003", "학술국장");
        // 응답 검토는 RESPONSE_REVIEW다 — 국장 역할이 닿는다(FormResponseControllerTest와 같다)
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                reviewer,
                MemberRoleFixture.DIRECTOR);

        proposalForm = saveProposalForm();
        formId = proposalForm.getId();
        draftId = saveResponse(proposalForm, author, ResponseStatus.DRAFT, 1);

        presignedPuts.clear();
        when(r2Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            PutObjectPresignRequest request = invocation.getArgument(0);
                            presignedPuts.add(request);
                            return stubPut(request.putObjectRequest().key());
                        });
        when(r2Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            GetObjectPresignRequest request = invocation.getArgument(0);
                            return stubGet(request.getObjectRequest().key());
                        });
    }

    /* ── 작성자 경로 ───────────────────────────────────────────────── */

    @Test
    @DisplayName("발급 → 목록 → 내려받기 URL → 삭제 — FORM_RESPONSE 행이 메타와 함께 남았다 사라진다")
    void authorLifecycle() throws Exception {
        String issued =
                issue(AUTHOR, draftId, "스터디 계획서.pdf", 204_800)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.fileId").isNumber())
                        .andExpect(jsonPath("$.data.contentType").value("application/pdf"))
                        .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                        .andExpect(
                                jsonPath(
                                        "$.data.uploadUrl",
                                        containsString("form-responses/" + draftId + "/")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long fileId = JsonPath.parse(issued).read("$.data.fileId", Long.class);

        FileReferenceEntity row = fileReferenceRepository.findById(fileId).orElseThrow();
        assertThat(row.getTargetType()).isEqualTo(FileTargetType.FORM_RESPONSE);
        assertThat(row.getTargetId()).isEqualTo(draftId);
        assertThat(row.objectKey()).startsWith("form-responses/" + draftId + "/").endsWith(".pdf");
        assertThat(row.getOriginalFileName()).isEqualTo("스터디 계획서.pdf");
        assertThat(row.getFileSize()).isEqualTo(204_800L);
        assertThat(row.getUploaderId()).isEqualTo(author.getId());
        assertThat(row.getCreatedAt()).isNotNull();

        mockMvc.perform(authorized(get(minePath(draftId)), AUTHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andExpect(jsonPath("$.data.attachments[0].fileId").value(fileId))
                .andExpect(jsonPath("$.data.attachments[0].fileName").value("스터디 계획서.pdf"))
                .andExpect(jsonPath("$.data.attachments[0].fileSize").value(204_800))
                .andExpect(jsonPath("$.data.attachments[0].uploadedAt").isNotEmpty())
                // 올린 사람은 싣지 않는다 — 승인 뒤 회원 전체에게 나가는 응답이다
                .andExpect(jsonPath("$.data.attachments[0].uploader").doesNotExist())
                .andExpect(jsonPath("$.data.totalSize").value(204_800))
                .andExpect(jsonPath("$.data.maxTotalSize").value(MAX_TOTAL))
                .andExpect(jsonPath("$.data.maxCount").value(5));

        mockMvc.perform(authorized(get(minePath(draftId) + "/" + fileId + "/download-url"), AUTHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url", containsString("X-Amz-Signature=stub")))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(900));

        mockMvc.perform(authorized(delete(minePath(draftId) + "/" + fileId), AUTHOR))
                .andExpect(status().isOk());
        assertThat(fileReferenceRepository.findById(fileId)).isEmpty();

        mockMvc.perform(authorized(delete(minePath(draftId) + "/" + fileId), AUTHOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("pdf·txt·md만 받고 확장자 대소문자는 가리지 않는다 — Content-Type은 서버 표의 값이다")
    void acceptsPdfTxtMdRegardlessOfCase() throws Exception {
        issue(AUTHOR, draftId, "계획서.PDF", 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("application/pdf"));
        issue(AUTHOR, draftId, "커리큘럼.Txt", 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("text/plain"));
        issue(AUTHOR, draftId, "README.md", 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("text/markdown"));
    }

    @Test
    @DisplayName(
            "docx·html·svg·확장자 없음은 400 PROPOSAL_ATTACHMENT_UNSUPPORTED_TYPE — 운영 건이 받는 docx도 여기서는"
                    + " 거절")
    void rejectsOtherTypes() throws Exception {
        for (String fileName : List.of("계획서.docx", "page.html", "logo.svg", "계획서")) {
            issue(AUTHOR, draftId, fileName, 10)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_UNSUPPORTED_TYPE"))
                    .andExpect(jsonPath("$.message").value("pdf·txt·md 파일만 올릴 수 있습니다."));
        }
        assertThat(presignedPuts).isEmpty();
    }

    @Test
    @DisplayName("합계가 정확히 20MB면 통과하고 1바이트라도 넘으면 413 — 서명 상한은 남은 용량이다")
    void totalSizeBoundary() throws Exception {
        saveAttachment(draftId, "앞선 자료.pdf", 15 * MB);

        issue(AUTHOR, draftId, "나머지.pdf", 5 * MB).andExpect(status().isCreated());
        assertThat(presignedPuts).hasSize(1);
        assertThat(presignedPuts.get(0).putObjectRequest().contentLength()).isEqualTo(5 * MB);

        issue(AUTHOR, draftId, "한 바이트.txt", 1)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_TOO_LARGE"))
                .andExpect(
                        jsonPath("$.message").value("첨부 자료는 합계 20MB까지 올릴 수 있습니다. 남은 용량은 0MB입니다."));
    }

    @Test
    @DisplayName("413 메시지는 남은 용량을 0.1MB 단위에서 내려 싣는다 — «남은 용량은 7.7MB입니다»")
    void tooLargeMessageCarriesRemainingCapacity() throws Exception {
        long used = 12_897_484L; // 12.3MB에서 1바이트 모자란 값 — 남은 용량이 정확히 7.7MB를 넘는다
        saveAttachment(draftId, "앞선 자료.pdf", used);

        issue(AUTHOR, draftId, "큰 자료.pdf", MAX_TOTAL - used + 1)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(
                        jsonPath("$.message")
                                .value("첨부 자료는 합계 20MB까지 올릴 수 있습니다. 남은 용량은 7.7MB입니다."));
    }

    @Test
    @DisplayName("파일 하나가 20MB여도 된다 — 파일당 상한은 없고 합계만 있다")
    void singleFileMayUseTheWholeCapacity() throws Exception {
        issue(AUTHOR, draftId, "전부.pdf", MAX_TOTAL).andExpect(status().isCreated());

        assertThat(presignedPuts).hasSize(1);
        assertThat(presignedPuts.get(0).putObjectRequest().contentLength()).isEqualTo(MAX_TOTAL);
    }

    @Test
    @DisplayName("지우면 용량이 돌아온다 — 20MB를 채운 뒤 하나를 지우면 다시 20MB를 올릴 수 있다")
    void deletingFreesCapacity() throws Exception {
        Long full = saveAttachment(draftId, "꽉 찬 자료.pdf", MAX_TOTAL);

        mockMvc.perform(authorized(delete(minePath(draftId) + "/" + full), AUTHOR))
                .andExpect(status().isOk());

        issue(AUTHOR, draftId, "새 자료.pdf", MAX_TOTAL).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("5개째는 통과하고 6개째는 409 PROPOSAL_ATTACHMENT_LIMIT_EXCEEDED")
    void countLimit() throws Exception {
        for (int i = 1; i <= 4; i++) {
            saveAttachment(draftId, "자료" + i + ".md", 100);
        }

        issue(AUTHOR, draftId, "다섯째.md", 100).andExpect(status().isCreated());

        issue(AUTHOR, draftId, "여섯째.md", 100)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("첨부 자료는 5개까지 올릴 수 있습니다."));
    }

    @Test
    @DisplayName("목록의 totalSize는 남아 있는 행의 크기 합이고 순서는 발급 순이다")
    void listTotalIsTheSumOfRemainingRows() throws Exception {
        Long first = saveAttachment(draftId, "a.pdf", 3 * MB);
        Long second = saveAttachment(draftId, "b.pdf", 4 * MB);
        Long third = saveAttachment(draftId, "c.txt", 1_234);

        mockMvc.perform(authorized(delete(minePath(draftId) + "/" + second), AUTHOR))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(minePath(draftId)), AUTHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(2))
                .andExpect(jsonPath("$.data.attachments[0].fileId").value(first))
                .andExpect(jsonPath("$.data.attachments[1].fileId").value(third))
                .andExpect(jsonPath("$.data.totalSize").value(3 * MB + 1_234));
    }

    /* ── 상태 잠금 ─────────────────────────────────────────────────── */

    @Test
    @DisplayName("수정요청(CHANGES_REQUESTED)은 올리기·지우기가 열린다 — 폼이 마감돼도")
    void changesRequestedAllowsUploadAndDeleteEvenAfterClosing() throws Exception {
        Long changesRequestedId =
                saveResponse(proposalForm, author, ResponseStatus.CHANGES_REQUESTED, 2);
        Long existing = saveAttachment(changesRequestedId, "고치기 전.pdf", 100);
        closeProposalForm();

        issue(AUTHOR, changesRequestedId, "고친 계획서.pdf", 100).andExpect(status().isCreated());
        mockMvc.perform(authorized(delete(minePath(changesRequestedId) + "/" + existing), AUTHOR))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("검토 중·승인·반려는 올리기·지우기가 409 PROPOSAL_ATTACHMENT_LOCKED — 목록·내려받기는 그대로 된다")
    void submittedAndConcludedResponsesAreLocked() throws Exception {
        List<Long> lockedIds =
                List.of(
                        saveResponse(proposalForm, author, ResponseStatus.SUBMITTED, 2),
                        saveResponse(proposalForm, author, ResponseStatus.ACCEPTED, 3),
                        saveResponse(proposalForm, author, ResponseStatus.REJECTED, 4));
        List<Long> fileIds = new ArrayList<>();
        for (Long id : lockedIds) {
            fileIds.add(saveAttachment(id, "검토받은 자료.pdf", 100));
        }

        // 읽기는 상태와 무관하다 — 작성자는 반려된 것도 본다
        for (int i = 0; i < lockedIds.size(); i++) {
            mockMvc.perform(authorized(get(minePath(lockedIds.get(i))), AUTHOR))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.attachments.length()").value(1));
            mockMvc.perform(
                            authorized(
                                    get(
                                            minePath(lockedIds.get(i))
                                                    + "/"
                                                    + fileIds.get(i)
                                                    + "/download-url"),
                                    AUTHOR))
                    .andExpect(status().isOk());
        }

        for (int i = 0; i < lockedIds.size(); i++) {
            issue(AUTHOR, lockedIds.get(i), "몰래 바꾼 자료.pdf", 100)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_LOCKED"));
            mockMvc.perform(
                            authorized(
                                    delete(minePath(lockedIds.get(i)) + "/" + fileIds.get(i)),
                                    AUTHOR))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_LOCKED"));
        }
    }

    @Test
    @DisplayName("접수가 마감된 폼의 초안은 409 — 자동 저장이 막히는 것과 같은 판정")
    void draftOfClosedFormIsLocked() throws Exception {
        Long existing = saveAttachment(draftId, "마감 전 자료.pdf", 100);
        closeProposalForm();

        issue(AUTHOR, draftId, "마감 뒤 자료.pdf", 100)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_LOCKED"))
                .andExpect(jsonPath("$.message", containsString("접수가 끝난")));
        mockMvc.perform(authorized(delete(minePath(draftId) + "/" + existing), AUTHOR))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_LOCKED"));
    }

    /* ── 기획안 한정 · 경계 ───────────────────────────────────────────── */

    @Test
    @DisplayName("기획안이 아닌 폼의 응답은 올리기가 400 PROPOSAL_ATTACHMENT_NOT_SUPPORTED — 읽기는 빈 목록이다")
    void nonProposalFormIsRejected() throws Exception {
        FormEntity plainForm =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                author, "동아리 설문", composition(), null, null, FormStatus.OPEN));
        Long plainDraftId = saveResponse(plainForm, author, ResponseStatus.DRAFT, 1);
        String plainPath =
                "/v1/forms/"
                        + plainForm.getId()
                        + "/responses/mine/"
                        + plainDraftId
                        + "/attachments";

        mockMvc.perform(authorized(get(plainPath), AUTHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(0));

        mockMvc.perform(authorized(post(plainPath), AUTHOR).content(body("계획서.pdf", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("남의 응답은 작성자 경로 넷 모두 404 FORM_RESPONSE_NOT_FOUND — 내 응답 상세와 같은 경계")
    void othersResponseIsNotFound() throws Exception {
        Long fileId = saveAttachment(draftId, "작성자 자료.pdf", 100);

        mockMvc.perform(authorized(get(minePath(draftId)), OTHER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
        mockMvc.perform(authorized(get(minePath(draftId) + "/" + fileId + "/download-url"), OTHER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
        issue(OTHER, draftId, "끼워 넣기.pdf", 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
        mockMvc.perform(authorized(delete(minePath(draftId) + "/" + fileId), OTHER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 응답의 fileId로는 내려받기·삭제가 404 PROPOSAL_ATTACHMENT_NOT_FOUND — 작성자·검토자 경로 모두(IDOR)")
    void foreignFileIdIsRejectedOnEveryPath() throws Exception {
        Long othersResponseId = saveResponse(proposalForm, other, ResponseStatus.SUBMITTED, 1);
        Long othersFile = saveAttachment(othersResponseId, "남의 자료.pdf", 100);
        saveAttachment(draftId, "내 자료.pdf", 100);

        mockMvc.perform(
                        authorized(
                                get(minePath(draftId) + "/" + othersFile + "/download-url"),
                                AUTHOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_NOT_FOUND"));
        mockMvc.perform(authorized(delete(minePath(draftId) + "/" + othersFile), AUTHOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_NOT_FOUND"));
        mockMvc.perform(
                        authorized(
                                get(reviewPath(draftId) + "/" + othersFile + "/download-url"),
                                REVIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_NOT_FOUND"));
    }

    /* ── 검토자 경로 ───────────────────────────────────────────────── */

    @Test
    @DisplayName("검토자는 상태와 무관하게 목록·내려받기를 받고, RESPONSE_REVIEW가 없으면 403")
    void reviewerReadsAndOthersAreForbidden() throws Exception {
        Long submittedId = saveResponse(proposalForm, other, ResponseStatus.SUBMITTED, 1);
        Long fileId = saveAttachment(submittedId, "검토 자료.pdf", 2_048);

        mockMvc.perform(authorized(get(reviewPath(submittedId)), REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andExpect(jsonPath("$.data.attachments[0].fileName").value("검토 자료.pdf"))
                .andExpect(jsonPath("$.data.totalSize").value(2_048));
        mockMvc.perform(
                        authorized(
                                get(reviewPath(submittedId) + "/" + fileId + "/download-url"),
                                REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url", containsString("X-Amz-Signature=stub")));

        // 작성자 본인이라도 검토자 경로는 RESPONSE_REVIEW다 — 이 경로의 문턱은 사람이 아니라 권한이다
        mockMvc.perform(authorized(get(reviewPath(submittedId)), AUTHOR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(
                        authorized(
                                get(reviewPath(submittedId) + "/" + fileId + "/download-url"),
                                AUTHOR))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("검토자 경로도 폼 경계를 본다 — 다른 폼의 응답 id는 404 FORM_RESPONSE_NOT_FOUND")
    void reviewerPathKeepsTheFormBoundary() throws Exception {
        FormEntity plainForm =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                author, "동아리 설문", composition(), null, null, FormStatus.OPEN));
        Long plainResponseId = saveResponse(plainForm, other, ResponseStatus.SUBMITTED, 1);

        mockMvc.perform(authorized(get(reviewPath(plainResponseId)), REVIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    /* ── 학술 활동 경로 ─────────────────────────────────────────────── */

    /*
     * 활동은 픽스처로 만든다 — 승인 이관 자체는 AcademicProgramMigration* 테스트가 보고, 여기서 볼 것은
     * «활동 → form_rspns_id → 그 응답의 첨부»라는 읽기 경로 하나다. 활동이 원 응답을 가리키는 것은 이관이
     * 만든 행과 같은 모양이고, 첨부를 활동 대상으로 복사하지 않으므로 응답에 붙인 행이 그대로 보여야 한다.
     */
    @Test
    @DisplayName("승인 뒤 활동 경로로 원 응답의 첨부가 보인다 — 가입한 회원이면 누구나, 남의 fileId는 404")
    void academicProgramPathShowsOriginalResponseAttachments() throws Exception {
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
                        "알고리즘 스터디",
                        author,
                        List.of("1회차"));
        Long originalResponseId = program.getFormResponse().getId();
        Long fileId = saveAttachment(originalResponseId, "알고리즘 커리큘럼.md", 4_096);
        Long foreignFile = saveAttachment(draftId, "다른 기획안 자료.pdf", 100);
        String path = "/v1/academic-programs/" + program.getId() + "/attachments";

        mockMvc.perform(authorized(get(path), OTHER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andExpect(jsonPath("$.data.attachments[0].fileId").value(fileId))
                .andExpect(jsonPath("$.data.attachments[0].fileName").value("알고리즘 커리큘럼.md"))
                .andExpect(jsonPath("$.data.totalSize").value(4_096))
                .andExpect(jsonPath("$.data.maxCount").value(5));
        mockMvc.perform(authorized(get(path + "/" + fileId + "/download-url"), OTHER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url", containsString("X-Amz-Signature=stub")))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(900));

        mockMvc.perform(authorized(get(path + "/" + foreignFile + "/download-url"), OTHER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ATTACHMENT_NOT_FOUND"));
        mockMvc.perform(authorized(get("/v1/academic-programs/999999/attachments"), OTHER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
        // 가입하지 않은 주체는 상세와 같이 403 SIGNUP_REQUIRED — 익명·미가입에게는 아무것도 내주지 않는다
        mockMvc.perform(authorized(get(path), UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    /* ── 재료 ─────────────────────────────────────────────────────── */

    private ResultActions issue(UUID who, Long responseId, String fileName, long fileSize)
            throws Exception {
        return mockMvc.perform(
                authorized(post(minePath(responseId)), who).content(body(fileName, fileSize)));
    }

    private String minePath(Long responseId) {
        return "/v1/forms/" + formId + "/responses/mine/" + responseId + "/attachments";
    }

    private String reviewPath(Long responseId) {
        return "/v1/forms/" + formId + "/responses/" + responseId + "/attachments";
    }

    private static String body(String fileName, long fileSize) {
        return """
               {"fileName": "%s", "fileSize": %d}
               """
                .formatted(fileName, fileSize);
    }

    /*
     * 기획안 시스템 폼의 축소 표본. 코드는 리터럴이 아니라 ProposalFormSeed의 상수다 — 첨부 서비스의
     * 판정(ATTACHABLE_SYSTEM_FORM_CODE)이 그 상수를 가리키므로 문자열을 다시 적으면 둘이 갈려도 초록이다.
     * 접수 기간을 비운 OPEN이라 «접수 중»이 시각에 달려 있지 않다.
     */
    private FormEntity saveProposalForm() {
        FormEntity form =
                FormEntity.create(
                        author, "스터디·프로젝트 기획안", composition(), null, null, FormStatus.OPEN, true);
        form.designateAsSystemForm(ProposalFormSeed.SYSTEM_FORM_CODE);
        return formRepository.saveAndFlush(form);
    }

    private void closeProposalForm() {
        proposalForm.changeStatus(FormStatusAction.CLOSE);
        formRepository.saveAndFlush(proposalForm);
    }

    /* 상태는 정상 경로(제출 → 검토 전이)로만 만든다 — 테스트 전용 생성 경로가 전이표를 비껴가지 않게 */
    private Long saveResponse(
            FormEntity form, MemberEntity respondent, ResponseStatus status, int responseSeq) {
        ResponseContent content = ResponseContent.of(Map.of("programTitle", "알고리즘 스터디"));
        FormResponseHistoryEntity response =
                status == ResponseStatus.DRAFT
                        ? FormResponseHistoryEntity.createDraft(
                                form, respondent, content, responseSeq)
                        : FormResponseHistoryEntity.createSubmitted(
                                form, respondent, content, Instant.now(), responseSeq);
        formResponseHistoryRepository.saveAndFlush(response);
        if (status != ResponseStatus.DRAFT && status != ResponseStatus.SUBMITTED) {
            response.changeStatus(status);
            formResponseHistoryRepository.flush();
        }
        return response.getId();
    }

    private Long saveAttachment(Long responseId, String fileName, long fileSize) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        return fileReferenceRepository
                .saveAndFlush(
                        FileReferenceEntity.attachment(
                                FileTargetType.FORM_RESPONSE,
                                responseId,
                                "form-responses/"
                                        + responseId
                                        + "/"
                                        + UUID.randomUUID()
                                        + "."
                                        + extension,
                                fileName,
                                fileSize,
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

    private static PresignedPutObjectRequest stubPut(String objectKey) {
        URI uri =
                URI.create(
                        "https://test-account.r2.cloudflarestorage.com/test-bucket/"
                                + objectKey
                                + "?X-Amz-Signature=stub");
        return PresignedPutObjectRequest.builder()
                .expiration(Instant.now().plusSeconds(600))
                .isBrowserExecutable(false)
                .signedHeaders(Map.of("host", List.of("test-account.r2.cloudflarestorage.com")))
                .httpRequest(SdkHttpRequest.builder().method(SdkHttpMethod.PUT).uri(uri).build())
                .build();
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
}
