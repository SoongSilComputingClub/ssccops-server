package org.sscc.ssccopsserver.domain.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;
import org.sscc.ssccopsserver.domain.content.repository.ContentPostRepository;
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

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 포스트 갤러리 (ssccops#381) — 발급이 file_rfrnc(CONTENT_POST) 행을 남기고 fileId·영구 주소를
 * 돌려주는지, 표지 지정·삭제가 그 행을 기준으로 도는지, 익명 리다이렉트가 게시본에서만 열리는지.
 * 서명은 EventImageControllerTest와 같은 방식으로 흉내 낸다(키를 URL에 실어 돌려준다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class ContentPostImageControllerTest {

    private static final String POSTS = "/v1/content/posts";
    private static final String APP_BASE_URL = "https://api.test.local";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @Autowired private ContentPostRepository postRepository;

    @MockitoBean private S3Presigner r2Presigner;

    private UUID editorToken;

    @BeforeEach
    void setUp() {
        editorToken = UUID.randomUUID();
        MemberEntity editor = saveMember(editorToken, "20260501", "홍보국원");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                editor,
                AuthorityCode.CONTENT_MANAGE);

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
    @DisplayName("발급하면 file_rfrnc에 CONTENT_POST 행이 남고 fileId·영구 주소·키 규칙이 응답에 실린다")
    void issueCreatesFileReferenceRow() throws Exception {
        Long postId = createPost("gallery-1");

        String response =
                mockMvc.perform(
                                authorized(post(POSTS + "/" + postId + "/images"), editorToken)
                                        .content(imageBody("png", 204_800)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.fileId").isNumber())
                        .andExpect(jsonPath("$.data.contentType").value("image/png"))
                        .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long fileId = JsonPath.parse(response).read("$.data.fileId", Long.class);
        String objectKey = JsonPath.parse(response).read("$.data.objectKey", String.class);
        String imageUrl = JsonPath.parse(response).read("$.data.imageUrl", String.class);
        String uploadUrl = JsonPath.parse(response).read("$.data.uploadUrl", String.class);

        assertThat(objectKey).startsWith("content-posts/" + postId + "/").endsWith(".png");
        assertThat(imageUrl)
                .isEqualTo(APP_BASE_URL + "/public/v1/posts/" + postId + "/images/" + fileId);
        assertThat(uploadUrl).contains(objectKey);

        FileReferenceEntity row = fileReferenceRepository.findById(fileId).orElseThrow();
        assertThat(row.getTargetType()).isEqualTo(FileTargetType.CONTENT_POST);
        assertThat(row.getTargetId()).isEqualTo(postId);
        assertThat(row.objectKey()).isEqualTo(objectKey);

        // 상세의 갤러리에 발급 순서로 실린다 — 두 장을 올리면 두 항목
        mockMvc.perform(
                        authorized(post(POSTS + "/" + postId + "/images"), editorToken)
                                .content(imageBody("jpg", 1024)))
                .andExpect(status().isCreated());
        mockMvc.perform(authorized(get(POSTS + "/" + postId), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gallery.length()").value(2))
                .andExpect(jsonPath("$.data.gallery[0].fileId").value(fileId))
                .andExpect(jsonPath("$.data.gallery[0].imageUrl").value(imageUrl));
    }

    @Test
    @DisplayName("갤러리의 파일은 표지가 되고, 그 장을 지우면 표지가 비고 행이 사라진다")
    void coverFollowsGalleryLifecycle() throws Exception {
        Long postId = createPost("gallery-2");
        Long fileId = issue(postId);

        mockMvc.perform(
                        authorized(patch(POSTS + "/" + postId), editorToken)
                                .content(postBodyWithCover("gallery-2", fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverFileId").value(fileId));

        mockMvc.perform(authorized(delete(POSTS + "/" + postId + "/images/" + fileId), editorToken))
                .andExpect(status().isOk());

        assertThat(fileReferenceRepository.findById(fileId)).isEmpty();
        ContentPostEntity post = postRepository.findById(postId).orElseThrow();
        assertThat(post.getCoverFileId()).isNull();
    }

    @Test
    @DisplayName("게시된 포스트의 갤러리 이미지는 익명에게 302로 열리고 서명은 그 행의 키로 만든다")
    void publishedGalleryImageRedirectsAnonymously() throws Exception {
        Long postId = createPost("gallery-3");
        Long fileId = issue(postId);
        mockMvc.perform(authorized(post(POSTS + "/" + postId + "/publish"), editorToken))
                .andExpect(status().isOk());
        String objectKey = fileReferenceRepository.findById(fileId).orElseThrow().objectKey();

        mockMvc.perform(get("/public/v1/posts/" + postId + "/images/" + fileId))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(header().string("Cache-Control", containsString("max-age=600")))
                .andExpect(header().string("Location", containsString(objectKey)));
    }

    @Test
    @DisplayName("다른 포스트의 파일 id는 없는 파일과 같은 404 CONTENT_IMAGE_NOT_FOUND")
    void foreignFileIdIsNotFound() throws Exception {
        Long mine = createPost("gallery-4");
        Long theirs = createPost("gallery-5");
        Long theirFile = issue(theirs);

        mockMvc.perform(
                        authorized(
                                delete(POSTS + "/" + mine + "/images/" + theirFile), editorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_IMAGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("허용 목록 밖 확장자는 400 UNSUPPORTED_IMAGE_TYPE")
    void unsupportedExtensionIsBadRequest() throws Exception {
        Long postId = createPost("gallery-6");

        mockMvc.perform(
                        authorized(post(POSTS + "/" + postId + "/images"), editorToken)
                                .content(imageBody("svg", 1024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    private Long issue(Long postId) throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(POSTS + "/" + postId + "/images"), editorToken)
                                        .content(imageBody("png", 1024)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.fileId", Long.class);
    }

    private Long createPost(String slug) throws Exception {
        String body =
                mockMvc.perform(
                                authorized(post(POSTS), editorToken)
                                        .content(
                                                """
                                                {"slug":"%s","cntntClsfCd":"NEWS","ttl":"갤러리",
                                                 "mtxt":"# g","actvYmd":"2026-03-01"}
                                                """
                                                        .formatted(slug)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(body).read("$.data.postId", Long.class);
    }

    private static String postBodyWithCover(String slug, Long coverFileId) {
        return """
                {"slug":"%s","cntntClsfCd":"NEWS","ttl":"갤러리","mtxt":"# g",
                 "actvYmd":"2026-03-01","coverFileId":%d}
                """
                .formatted(slug, coverFileId);
    }

    private static String imageBody(String fileExt, long fileSize) {
        return """
               {"fileExt": "%s", "fileSize": %d}
               """
                .formatted(fileExt, fileSize);
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
