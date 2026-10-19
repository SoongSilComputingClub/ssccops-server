package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentUploadRequest;
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
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditLog;
import org.sscc.ssccopsserver.global.logging.EcsJsonEncoder;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/*
 * 기획안 첨부 중 **커밋이 있어야 보이는 것** (server#685) — 동시 발급 · 삭제 감사 줄 · 초안 일괄 삭제의
 * 첨부 정리. 셋 다 @Transactional 테스트로는 볼 수 없다: 두 스레드가 서로의 커밋을 봐야 경합이 재현되고,
 * 감사 줄과 R2 삭제는 afterCommit에서만 돈다.
 *
 * ── 컨텍스트를 새로 띄우지 않는다 ─────────────────────────────────────
 * 애노테이션과 @MockitoBean(S3Client 하나)을 EventDuplicateControllerTest와 똑같이 둬 그 캐시를 함께 쓴다
 * (MemberLinkConcurrencyTest 주석 — 컨텍스트가 하나 늘면 그것만으로 무관한 테스트가 터질 수 있다). 그래서
 * 전용 DB가 아니라 공용 testdb이고, 커밋한 것은 여기서 전부 지운다 — 남기면 «mbr이 비어 있다»를
 * 전제하는 테스트와 기획안 시스템 폼(sys_form_cd UNIQUE)을 만드는 테스트가 순서에 따라 깨진다.
 * presigner는 스텁하지 않는다 — 서명은 네트워크 없이 계산만 하므로 실제 빈으로 충분하다. S3Client만
 * 스텁하는 것은 지우기(FileEraser)가 실제 호출이고, 그 호출을 «커밋 뒤에 그 키로» 확인하기 위해서다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProposalAttachmentCommitTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final long MB = 1024L * 1024;
    private static final int CONCURRENT_ISSUES = 2;

    @Autowired private MockMvc mockMvc;
    @Autowired private ProposalAttachmentService proposalAttachmentService;
    @Autowired private FormDraftRetentionService formDraftRetentionService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @MockitoBean private S3Client r2Client;

    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    private final EcsJsonEncoder encoder = new EcsJsonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<Long> createdForms = new ArrayList<>();
    private final List<Long> createdResponses = new ArrayList<>();
    private MemberEntity author;
    private MemberEntity applicant;
    private FormEntity proposalForm;

    /* 트랜잭션이 없어 픽스처가 DB에 남는다 — 회원·폼은 클래스당 한 번 세우고 @AfterAll이 지운다 */
    @BeforeAll
    void fixtures() {
        author =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        AUTHOR,
                        "20269001",
                        "작성자",
                        "author-685@sscc.org");
        applicant =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20269002",
                        "지원자",
                        "applicant-685@sscc.org");
        FormEntity form =
                FormEntity.create(
                        author, "스터디·프로젝트 기획안", composition(), null, null, FormStatus.OPEN, true);
        form.designateAsSystemForm(ProposalFormSeed.SYSTEM_FORM_CODE);
        proposalForm = formRepository.saveAndFlush(form);
        createdForms.add(proposalForm.getId());
    }

    @BeforeEach
    void captureAudit() {
        audit.list.clear();
        audit.start();
        ((Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME)).addAppender(audit);
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        clearInvocations(r2Client);
    }

    @AfterEach
    void cleanUpResponses() {
        ((Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME)).detachAppender(audit);
        audit.stop();
        encoder.stop();
        fileReferenceRepository.deleteAll(
                fileReferenceRepository.findAllByTargetTypeAndTargetIdIn(
                        FileTargetType.FORM_RESPONSE, createdResponses));
        formResponseHistoryRepository.deleteAllById(
                createdResponses.stream()
                        .filter(formResponseHistoryRepository::existsById)
                        .toList());
        createdResponses.clear();
    }

    @AfterAll
    void cleanUpFixtures() {
        formRepository.deleteAllById(createdForms);
        memberRepository.deleteAllById(List.of(author.getId(), applicant.getId()));
    }

    @Test
    @DisplayName("동시 발급 둘이 합쳐 20MB를 넘으면 하나만 통과한다 — 응답 행 잠금이 둘을 줄 세운다")
    void concurrentIssuesCannotJointlyExceedTheTotal() throws Exception {
        warmUp();
        Long responseId = saveChangesRequested(2);

        List<Throwable> failures = issueConcurrently(responseId, 15 * MB, "큰 자료.pdf");

        assertThat(failures).hasSize(CONCURRENT_ISSUES - 1);
        assertThat(failures)
                .allSatisfy(
                        e ->
                                assertThat(((GeneralException) e).getErrorCode())
                                        .isEqualTo(FormErrorCode.PROPOSAL_ATTACHMENT_TOO_LARGE));
        List<FileReferenceEntity> rows =
                fileReferenceRepository.findAllByTargetTypeAndTargetIdOrderByIdAsc(
                        FileTargetType.FORM_RESPONSE, responseId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getFileSize()).isEqualTo(15 * MB);
    }

    @Test
    @DisplayName("4개가 붙은 응답에 동시 발급 둘이 오면 5개째 하나만 통과하고 나머지는 409")
    void concurrentIssuesCannotJointlyExceedTheCount() throws Exception {
        warmUp();
        Long responseId = saveChangesRequested(2);
        for (int i = 1; i <= 4; i++) {
            saveAttachment(responseId, "자료" + i + ".md", 100);
        }

        List<Throwable> failures = issueConcurrently(responseId, 100, "다섯째.md");

        assertThat(failures).hasSize(CONCURRENT_ISSUES - 1);
        assertThat(failures)
                .allSatisfy(
                        e ->
                                assertThat(((GeneralException) e).getErrorCode())
                                        .isEqualTo(
                                                FormErrorCode.PROPOSAL_ATTACHMENT_LIMIT_EXCEEDED));
        assertThat(
                        fileReferenceRepository.findAllByTargetTypeAndTargetIdOrderByIdAsc(
                                FileTargetType.FORM_RESPONSE, responseId))
                .hasSize(5);
    }

    @Test
    @DisplayName("삭제는 커밋 뒤 감사 줄을 남긴다 — 응답 id·형식·크기는 있고 원본 파일 이름은 없다, R2 오브젝트도 그때 지운다")
    void deleteIsAuditedWithoutTheFileName() throws Exception {
        Long responseId = saveChangesRequested(2);
        String objectKey = "form-responses/" + responseId + "/" + UUID.randomUUID() + ".pdf";
        Long fileId =
                fileReferenceRepository
                        .saveAndFlush(
                                FileReferenceEntity.attachment(
                                        FileTargetType.FORM_RESPONSE,
                                        responseId,
                                        objectKey,
                                        "홍길동_자기소개서.pdf",
                                        3_072,
                                        author.getId(),
                                        Instant.now()))
                        .getId();

        mockMvc.perform(
                        delete(
                                        "/v1/forms/"
                                                + proposalForm.getId()
                                                + "/responses/mine/"
                                                + responseId
                                                + "/attachments/"
                                                + fileId)
                                .header("Authorization", "Bearer " + AUTHOR))
                .andExpect(status().isOk());

        Map<String, Object> line = onlyAuditLine("form.response.attachment.delete");
        assertThat(section(line, "event")).containsEntry("outcome", "success");
        Map<String, Object> auditSection = section(line, "audit");
        assertThat(section(auditSection, "target"))
                .containsEntry("type", "file_rfrnc")
                .containsEntry("id", String.valueOf(fileId));
        assertThat(String.valueOf(auditSection.get("decision")))
                .isEqualTo("formRspnsId=" + responseId + " type=PDF size=3072");
        // 이름에 실명이 들어갈 수 있다(ADR-0024) — 줄의 어느 필드에도 없어야 한다
        assertThat(mapper.writeValueAsString(line)).doesNotContain("홍길동").doesNotContain("자기소개서");

        ArgumentCaptor<DeleteObjectRequest> erased =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(r2Client).deleteObject(erased.capture());
        assertThat(erased.getValue().key()).isEqualTo(objectKey);
    }

    @Test
    @DisplayName("접수가 끝난 지 90일이 지난 폼의 초안을 지우면 그 첨부 행과 R2 오브젝트도 함께 지운다 — 제출된 응답의 첨부는 남는다")
    void draftPurgeRemovesTheDraftsAttachments() {
        FormEntity closedLongAgo =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                author,
                                "지난 기획안 접수",
                                composition(),
                                null,
                                Instant.parse("2020-01-01T00:00:00Z"),
                                FormStatus.CLOSED));
        createdForms.add(closedLongAgo.getId());
        Long draftId = saveResponse(closedLongAgo, author, ResponseStatus.DRAFT, 1);
        Long submittedId = saveResponse(closedLongAgo, applicant, ResponseStatus.SUBMITTED, 1);
        FileReferenceEntity draftFileA = saveAttachment(draftId, "초안 자료 1.pdf", 100);
        FileReferenceEntity draftFileB = saveAttachment(draftId, "초안 자료 2.md", 200);
        FileReferenceEntity submittedFile = saveAttachment(submittedId, "제출 자료.pdf", 300);

        int deleted = formDraftRetentionService.purge();

        assertThat(deleted).isEqualTo(1);
        assertThat(formResponseHistoryRepository.existsById(draftId)).isFalse();
        assertThat(formResponseHistoryRepository.existsById(submittedId)).isTrue();
        assertThat(fileReferenceRepository.findById(draftFileA.getId())).isEmpty();
        assertThat(fileReferenceRepository.findById(draftFileB.getId())).isEmpty();
        assertThat(fileReferenceRepository.findById(submittedFile.getId())).isPresent();

        ArgumentCaptor<DeleteObjectRequest> erased =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(r2Client, Mockito.times(2)).deleteObject(erased.capture());
        assertThat(erased.getAllValues())
                .extracting(DeleteObjectRequest::key)
                .containsExactlyInAnyOrder(draftFileA.objectKey(), draftFileB.objectKey());
    }

    /* ── 재료 ─────────────────────────────────────────────────────── */

    /*
     * 동시에 출발시켜 실패만 모은다. 서비스를 직접 부르는 것은 MemberLinkConcurrencyTest와 같다 — 보는 것이
     * 잠금·재확인이지 HTTP 배선이 아니고(그쪽은 ProposalAttachmentControllerTest), MockMvc를 여러 스레드에서
     * 함께 쓰는 것보다 경합이 서비스 트랜잭션 둘로 곧바로 드러난다. 진 쪽이 도메인 오류가 아니면(잠금
     * 타임아웃 등) 그대로 실패로 둔다.
     */
    private List<Throwable> issueConcurrently(Long responseId, long fileSize, String fileName)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_ISSUES);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_ISSUES; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    startTogether.await();
                                    return proposalAttachmentService.issueUploadUrl(
                                            proposalForm.getId(),
                                            responseId,
                                            new ProposalAttachmentUploadRequest(fileName, fileSize),
                                            author);
                                }));
            }
            startTogether.countDown();
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(GeneralException.class);
                    failures.add(ex.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /*
     * 경합 전에 한 번 돌린다. 첫 호출은 클래스 로딩·서명기 초기화로 느려, 그동안 다른 스레드가 잠금을 기다리다
     * H2의 잠금 대기 상한에 닿을 수 있다 — 그러면 보려던 것(도메인 거절)이 아니라 잠금 타임아웃이 나온다.
     */
    private void warmUp() {
        Long warmUpId = saveChangesRequested(1);
        proposalAttachmentService.issueUploadUrl(
                proposalForm.getId(),
                warmUpId,
                new ProposalAttachmentUploadRequest("준비.txt", 1L),
                author);
    }

    /* 수정요청 응답은 접수 여부와 무관하게 첨부가 열린다 — 경합 테스트가 폼 상태에 기대지 않게 */
    private Long saveChangesRequested(int responseSeq) {
        return saveResponse(proposalForm, author, ResponseStatus.CHANGES_REQUESTED, responseSeq);
    }

    private Long saveResponse(
            FormEntity form, MemberEntity respondent, ResponseStatus status, int responseSeq) {
        ResponseContent content = ResponseContent.of(Map.of("programTitle", "알고리즘 스터디"));
        FormResponseHistoryEntity response =
                status == ResponseStatus.DRAFT
                        ? FormResponseHistoryEntity.createDraft(
                                form, respondent, content, responseSeq)
                        : FormResponseHistoryEntity.createSubmitted(
                                form, respondent, content, Instant.now(), responseSeq);
        if (status == ResponseStatus.CHANGES_REQUESTED) {
            response.changeStatus(status);
        }
        Long id = formResponseHistoryRepository.saveAndFlush(response).getId();
        createdResponses.add(id);
        return id;
    }

    private FileReferenceEntity saveAttachment(Long responseId, String fileName, long fileSize) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        return fileReferenceRepository.saveAndFlush(
                FileReferenceEntity.attachment(
                        FileTargetType.FORM_RESPONSE,
                        responseId,
                        "form-responses/" + responseId + "/" + UUID.randomUUID() + "." + extension,
                        fileName,
                        fileSize,
                        author.getId(),
                        Instant.now()));
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

    private Map<String, Object> onlyAuditLine(String action) {
        List<Map<String, Object>> lines =
                audit.list.stream()
                        .map(this::asJson)
                        .filter(m -> action.equals(section(m, "event").get("action")))
                        .toList();
        assertThat(lines).as("audit lines for " + action).hasSize(1);
        return lines.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> line, String name) {
        Object value = line.get(name);
        assertThat(value).as(name).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asJson(ILoggingEvent event) {
        try {
            return mapper.readValue(encoder.encode(event), Map.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
