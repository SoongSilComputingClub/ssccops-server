package org.sscc.ssccopsserver.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
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

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 행사 본문 이미지 업로드 URL 발급(#161 · wave2 D6) 통합 검증.
 *
 * **S3Presigner를 목으로 갈아 끼운다** — 진짜 빈을 두면 테스트가 R2 서명 키를 요구하고(그
 * 값은 저장소에 없다) 무엇보다 이 테스트가 확인하려는 것은 서명 알고리즘이 아니라 서버가
 * 무엇에 서명을 요청하는가(버킷·키·contentType·유효기간)와 그 결과를 어떤 계약으로 내리는가다.
 * 목은 요청받은 키를 그대로 URL에 실어 돌려주므로 "발급한 키로 서명했는가"까지 드러난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EventImageControllerTest.StubJwtDecoderConfig.class)
@Transactional
class EventImageControllerTest {

    private static final String EVENTS = "/v1/events";

    /** application-test.yaml의 app.public-base-url과 같은 값이어야 한다 */
    private static final String APP_BASE_URL = "https://api.test.local";

    private static final String BUCKET = "test-bucket";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @MockitoBean private S3Presigner r2Presigner;

    private UUID managerToken;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        MemberEntity manager = saveMember(managerToken, "20260101", "행사운영자");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                manager,
                MemberRoleFixture.DIRECTOR);

        // EVENT_MANAGE가 없는 회원. '다른 권한만' 가진 쪽이어야 403이 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260102", "업무담당");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                outsider,
                AuthorityCode.WORK_MANAGE);

        // 서명은 흉내만 낸다 — 요청받은 키를 URL에 실어 돌려주므로 '무엇에 서명했는가'가 드러난다
        when(r2Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            PutObjectPresignRequest presignRequest = invocation.getArgument(0);
                            String key = presignRequest.putObjectRequest().key();
                            return stubPresignedPutObject(key);
                        });
    }

    /*
     * 발급의 기본형. 키 규칙(events/{eventId}/{uuid}.{ext})과 응답 네 필드, 그리고 서명을
     * 요청한 내용(버킷·키·contentType·유효기간)까지 한자리에서 못 박는다 — 웹과 합의한 계약이
     * 이 넷이고, 셋 중 하나만 어긋나도 브라우저의 PUT이 R2에서 거절된다.
     */
    @Test
    void issueUploadUrlReturns201WithKeyRuleAndFourFields() throws Exception {
        Long eventId = createEvent();

        String response =
                mockMvc.perform(
                                authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                        .content(imageBody("poster.png", "image/png", 204_800)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String objectKey = JsonPath.parse(response).read("$.data.objectKey", String.class);
        String imageUrl = JsonPath.parse(response).read("$.data.imageUrl", String.class);
        String uploadUrl = JsonPath.parse(response).read("$.data.uploadUrl", String.class);

        assertThat(objectKey).startsWith("events/" + eventId + "/").endsWith(".png");
        // 파일명이 아니라 UUID다 — 같은 이름을 두 번 올려도 앞의 것이 덮이지 않는다
        String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        assertThat(fileName).doesNotContain("poster");
        assertThat(UUID.fromString(fileName.substring(0, fileName.length() - ".png".length())))
                .isNotNull();

        /*
         * 본문 마크다운에 박힐 값은 R2의 주소가 아니라 **우리 API의 리다이렉트 주소**다 (#208).
         * 버킷이 비공개라 읽기에도 서명이 필요한데, 서명은 만료되고 이 문자열은 본문에 굳는다.
         */
        assertThat(imageUrl)
                .isEqualTo(APP_BASE_URL + "/public/v1/events/" + eventId + "/images/" + fileName);
        assertThat(uploadUrl).contains(objectKey);

        ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(r2Presigner).presignPutObject(captor.capture());
        PutObjectPresignRequest presignRequest = captor.getValue();
        assertThat(presignRequest.putObjectRequest().bucket()).isEqualTo(BUCKET);
        assertThat(presignRequest.putObjectRequest().key()).isEqualTo(objectKey);
        // contentType까지 서명에 넣지 않으면 허가받은 URL로 아무 형식이나 올릴 수 있다
        assertThat(presignRequest.putObjectRequest().contentType()).isEqualTo("image/png");
        assertThat(presignRequest.signatureDuration().toMinutes()).isEqualTo(10);
    }

    /*
     * jpg·jpeg는 둘 다 받되 키에 쓰는 확장자는 하나로 굳힌다 — 통용되는 확장자를 그대로 쓰면
     * 같은 형식이 두 벌로 쌓이고 대소문자까지 섞이면 네 벌이 된다.
     */
    @Test
    void jpegVariantsShareOneCanonicalExtension() throws Exception {
        Long eventId = createEvent();

        assertThat(issuedObjectKey(eventId, "photo.jpeg", "image/jpeg")).endsWith(".jpg");
        assertThat(issuedObjectKey(eventId, "photo.JPG", "IMAGE/JPEG")).endsWith(".jpg");
    }

    /*
     * 형식 거절 네 경우를 한자리에 둔다 — 확인하려는 것이 "둘 다 보고 서로 맞아야 한다"라
     * 경우 간 비교가 곧 규칙이다. 넷 모두 같은 코드인 것은 운영자가 할 일이 같기 때문이다.
     */
    @Test
    void unsupportedOrMismatchedImageTypeReturns400() throws Exception {
        Long eventId = createEvent();

        // 허용 목록에 없는 형식. SVG는 스크립트를 담을 수 있어 의도적으로 뺐다
        expectImageBadRequest(eventId, "logo.svg", "image/svg+xml");
        // 확장자만 허용 목록 밖
        expectImageBadRequest(eventId, "poster.bmp", "image/png");
        // contentType과 확장자가 서로 어긋난다
        expectImageBadRequest(eventId, "poster.png", "image/jpeg");
        // 확장자가 아예 없다
        expectImageBadRequest(eventId, "poster", "image/png");
    }

    /*
     * 크기 상한(10MB). 경계는 통과하고 한 바이트만 넘어도 413이어야 한다 — 이 판정의 근거는
     * 요청이 신고한 크기이며(서버는 바이트를 보지 않는다) 실제 강제는 버킷 정책의 몫이다.
     */
    @Test
    void imageAtSizeLimitPassesButOverLimitReturns413() throws Exception {
        Long eventId = createEvent();
        long limit = 10L * 1024 * 1024;

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                .content(imageBody("poster.png", "image/png", limit)))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                .content(imageBody("poster.png", "image/png", limit + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    /* 없는 행사에는 키를 발급하지 않는다 — 경로의 행사가 형식 검사보다 먼저다 */
    @Test
    void issueUploadUrlForUnknownEventReturns404() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS + "/999999/images"), managerToken)
                                .content(imageBody("poster.png", "image/png", 1024)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* 클래스 레벨 EVENT_MANAGE다 — 발급도 예외가 아니다 */
    @Test
    void issueUploadUrlWithoutEventManageIsForbidden() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS + "/1/images"), outsiderToken)
                                .content(imageBody("poster.png", "image/png", 1024)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* ── 도우미 ───────────────────────────────────────────── */

    private String issuedObjectKey(Long eventId, String fileName, String contentType)
            throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                        .content(imageBody(fileName, contentType, 1024)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.objectKey", String.class);
    }

    private void expectImageBadRequest(Long eventId, String fileName, String contentType)
            throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                .content(imageBody(fileName, contentType, 1024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    private Long createEvent() throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(EVENTS), managerToken)
                                        .content(
                                                """
                                                {"eventClsfCd": "RECRUIT", "eventTtl": "이미지 붙일 행사",
                                                 "mtxtCn": "# 모집 요강"}
                                                """))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.eventId", Long.class);
    }

    private static String imageBody(String fileName, String contentType, long fileSize) {
        return """
               {"fileName": "%s", "contentType": "%s", "fileSize": %d}
               """
                .formatted(fileName, contentType, fileSize);
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

    /* 진짜 서명 대신 키를 그대로 실은 URL을 돌려준다 (PresignedRequest.url()은 httpRequest에서 온다) */
    private static PresignedPutObjectRequest stubPresignedPutObject(String objectKey) {
        URI uri =
                URI.create(
                        "https://test-account.r2.cloudflarestorage.com/"
                                + BUCKET
                                + "/"
                                + objectKey
                                + "?X-Amz-Signature=stub");
        return PresignedPutObjectRequest.builder()
                .expiration(Instant.now().plusSeconds(600))
                .isBrowserExecutable(false)
                .signedHeaders(Map.of("host", List.of("test-account.r2.cloudflarestorage.com")))
                .httpRequest(SdkHttpRequest.builder().method(SdkHttpMethod.PUT).uri(uri).build())
                .build();
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", token + "@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
