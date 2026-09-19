package org.sscc.ssccopsserver.domain.operation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
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
 * 운영 건 첨부 (#493). 콘텐츠 갤러리 테스트와 같은 뼈대 — R2 presigner만 스텁하고 나머지는 실제
 * 컨텍스트다. 업무 하나를 REST로 만들어(WORK_MANAGE 국장) 그 operationId에 붙인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class OperationAttachmentControllerTest {

    private static final UUID DIRECTOR = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @MockitoBean private S3Presigner r2Presigner;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        MemberEntity director = saveMember(DIRECTOR, "20260601", "국장");
        ownerId = director.getId();
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                director,
                MemberRoleFixture.DIRECTOR);
        saveMember(OUTSIDER, "20260602", "부원");

        when(r2Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            PutObjectPresignRequest request = invocation.getArgument(0);
                            return stubPut(request.putObjectRequest().key());
                        });
        when(r2Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            GetObjectPresignRequest request = invocation.getArgument(0);
                            return stubGet(request.getObjectRequest().key());
                        });
    }

    @Test
    @DisplayName("발급 → 목록 → 내려받기(302) → 삭제 — file_rfrnc에 OPERATION 행이 메타와 함께 남았다 사라진다")
    void lifecycle() throws Exception {
        Long operationId = createWorkOperation();

        String issued =
                mockMvc.perform(
                                authorized(post(url(operationId)), DIRECTOR)
                                        .content(body("결과 보고서.pdf", 204_800)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.fileId").isNumber())
                        .andExpect(jsonPath("$.data.contentType").value("application/pdf"))
                        .andExpect(
                                jsonPath(
                                        "$.data.uploadUrl",
                                        containsString("operations/" + operationId + "/")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long fileId = JsonPath.parse(issued).read("$.data.fileId", Long.class);

        FileReferenceEntity row = fileReferenceRepository.findById(fileId).orElseThrow();
        assertThat(row.getTargetType()).isEqualTo(FileTargetType.OPERATION);
        assertThat(row.getTargetId()).isEqualTo(operationId);
        assertThat(row.getOriginalFileName()).isEqualTo("결과 보고서.pdf");
        assertThat(row.getFileSize()).isEqualTo(204_800L);
        assertThat(row.getUploaderId()).isEqualTo(ownerId);
        assertThat(row.getCreatedAt()).isNotNull();

        mockMvc.perform(authorized(get(url(operationId)), DIRECTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("결과 보고서.pdf"))
                .andExpect(jsonPath("$.data[0].uploader.memberId").value(ownerId))
                .andExpect(jsonPath("$.data[0].uploadedAt").isNotEmpty());

        mockMvc.perform(authorized(get(url(operationId) + "/" + fileId + "/download"), DIRECTOR))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("X-Amz-Signature=stub")));

        mockMvc.perform(authorized(delete(url(operationId) + "/" + fileId), DIRECTOR))
                .andExpect(status().isOk());
        assertThat(fileReferenceRepository.findById(fileId)).isEmpty();
        mockMvc.perform(authorized(delete(url(operationId) + "/" + fileId), DIRECTOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("업무 첨부는 WORK_MANAGE가 없으면 올릴 수도 볼 수도 없다(WORK_READ도 없는 부원)")
    void requiresWorkAuthorities() throws Exception {
        Long operationId = createWorkOperation();
        mockMvc.perform(authorized(post(url(operationId)), OUTSIDER).content(body("a.pdf", 10)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorized(get(url(operationId)), OUTSIDER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("모르는 확장자는 400, 25MB 초과는 413, 없는 운영 건은 404")
    void rejectsBadRequests() throws Exception {
        Long operationId = createWorkOperation();
        mockMvc.perform(authorized(post(url(operationId)), DIRECTOR).content(body("run.exe", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_ATTACHMENT_TYPE"));
        mockMvc.perform(
                        authorized(post(url(operationId)), DIRECTOR)
                                .content(body("big.zip", 26L * 1024 * 1024)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
        mockMvc.perform(authorized(post(url(999_999L)), DIRECTOR).content(body("a.pdf", 10)))
                .andExpect(status().isNotFound());
    }

    /* ── 재료 ─────────────────────────────────────────────────── */

    private Long createWorkOperation() throws Exception {
        String body =
                """
                {
                  "title": "첨부 대상 업무",
                  "itemType": "EVENT",
                  "ownerId": %d,
                  "startAt": "2026-09-01T18:00:00+09:00",
                  "endAt": "2026-09-01T20:00:00+09:00"
                }
                """
                        .formatted(ownerId);
        String response =
                mockMvc.perform(authorized(post("/v1/works"), DIRECTOR).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.operationId", Long.class);
    }

    private static String url(Long operationId) {
        return "/v1/operations/" + operationId + "/attachments";
    }

    private static String body(String fileName, long fileSize) {
        return """
               {"fileName": "%s", "fileSize": %d}
               """
                .formatted(fileName, fileSize);
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
