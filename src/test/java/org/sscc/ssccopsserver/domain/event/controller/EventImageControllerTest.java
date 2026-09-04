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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

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
 *
 * **요청이 신고하는 것은 확장자와 크기뿐이다** (#210 · ssccops#157). contentType은 서버가 정해
 * 서명과 응답에 함께 싣는데, 여기서 확인해야 하는 것은 그 둘이 **같은 값**이라는 사실이다 —
 * 갈리면 브라우저의 PUT만 R2에서 조용히 거절되고 서버 로그에는 아무것도 남지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
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
     * 발급의 기본형. 키 규칙(events/{eventId}/{uuid}.{ext})과 응답 다섯 필드, 그리고 서명을
     * 요청한 내용(버킷·키·contentType·유효기간)까지 한자리에서 못 박는다 — 웹과 합의한 계약이
     * 이것이고, 하나만 어긋나도 브라우저의 PUT이 R2에서 거절된다.
     */
    @Test
    void issueUploadUrlReturns201WithKeyRuleAndFiveFields() throws Exception {
        Long eventId = createEvent();

        String response =
                mockMvc.perform(
                                authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                        .content(imageBody("png", 204_800)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.contentType").value("image/png"))
                        .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String objectKey = JsonPath.parse(response).read("$.data.objectKey", String.class);
        String imageUrl = JsonPath.parse(response).read("$.data.imageUrl", String.class);
        String uploadUrl = JsonPath.parse(response).read("$.data.uploadUrl", String.class);

        assertThat(objectKey).startsWith("events/" + eventId + "/").endsWith(".png");
        // 키의 이름 부분은 UUID다 — 같은 파일을 두 번 올려도 앞의 것이 덮이지 않는다
        String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
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
        /*
         * contentType까지 서명에 넣지 않으면 허가받은 URL로 아무 형식이나 올릴 수 있다. 그리고
         * 서명한 그 값이 곧 응답의 contentType이어야 한다 (#210) — 웹은 그것을 PUT 헤더에 옮겨
         * 적을 뿐이고, 둘이 갈리면 R2가 PUT을 거절한다.
         */
        assertThat(presignRequest.putObjectRequest().contentType()).isEqualTo("image/png");
        assertThat(JsonPath.parse(response).read("$.data.contentType", String.class))
                .isEqualTo(presignRequest.putObjectRequest().contentType());
        assertThat(presignRequest.signatureDuration().toMinutes()).isEqualTo(10);
    }

    /*
     * jpg·jpeg는 둘 다 받되 키에 쓰는 확장자는 하나로 굳힌다 — 통용되는 확장자를 그대로 쓰면
     * 같은 형식이 두 벌로 쌓이고 대소문자까지 섞이면 네 벌이 된다.
     *
     * **정규화를 서버가 한다** (#210) — 앞의 점·대문자·앞뒤 공백은 화면이 무엇을 붙여 보내든
     * 서버가 떼어 낸다. 웹에 맡기면 규칙이 두 벌이 되고, 한쪽만 바뀌는 날 멀쩡한 파일이 400으로
     * 튕긴다(그것이 ssccops#157에서 실제로 난 일이다).
     */
    @Test
    void jpegVariantsShareOneCanonicalExtension() throws Exception {
        Long eventId = createEvent();

        assertThat(issued(eventId, "jpeg").objectKey()).endsWith(".jpg");
        assertThat(issued(eventId, "JPG").objectKey()).endsWith(".jpg");
        assertThat(issued(eventId, ".jpg").objectKey()).endsWith(".jpg");
        assertThat(issued(eventId, " .JPEG ").objectKey()).endsWith(".jpg");

        // 넷 모두 같은 형식이므로 서버가 정하는 contentType도 하나다
        assertThat(issued(eventId, "jpeg").contentType()).isEqualTo("image/jpeg");
        assertThat(issued(eventId, ".JPG").contentType()).isEqualTo("image/jpeg");
    }

    /*
     * 응답의 contentType은 **그 확장자의 표준 값**이다 (#210). 웹이 이 값을 PUT 헤더에 그대로
     * 쓰므로, 브라우저가 파일에서 읽는 비표준 값(image/jpg 같은)이 여기 실리면 안 된다 —
     * 서명은 표준 값으로 되어 있어 그 PUT은 R2에서 거절된다.
     */
    @Test
    void responseContentTypeIsTheStandardValueOfTheExtension() throws Exception {
        Long eventId = createEvent();

        assertThat(issued(eventId, "png").contentType()).isEqualTo("image/png");
        assertThat(issued(eventId, "jpg").contentType()).isEqualTo("image/jpeg");
        assertThat(issued(eventId, "webp").contentType()).isEqualTo("image/webp");
        assertThat(issued(eventId, "gif").contentType()).isEqualTo("image/gif");
    }

    /*
     * 확장자 거절. **이제 거절 사유는 하나다** (#210) — "허용 목록에 없다". 예전에는
     * "contentType과 확장자가 서로 어긋난다"가 같은 코드로 함께 왔는데, 요청이 신고하는 값이
     * 하나뿐이라 어긋날 짝이 없어졌다.
     */
    @Test
    void unsupportedFileExtensionReturns400() throws Exception {
        Long eventId = createEvent();

        // SVG는 이미지이면서 스크립트를 담을 수 있는 문서라 의도적으로 뺐다(ImageFileType)
        expectImageBadRequest(eventId, "svg");
        expectImageBadRequest(eventId, "exe");
        expectImageBadRequest(eventId, "bmp");
        // 점 하나만 보내면 정규화 후 빈 문자열이다 — 어떤 허용 형식과도 맞지 않는다
        expectImageBadRequest(eventId, ".");
    }

    /*
     * fileExt가 비면 형식 판정에 닿기 전에 400이다(@NotBlank · VALIDATION_FAILED). 학술
     * 인증사진(#137)과 같은 제약이라 같은 자리에서 끊긴다 — 코드가 UNSUPPORTED_IMAGE_TYPE이
     * 아닌 것은 "고를 수 없는 형식"이 아니라 요청이 값을 아예 빠뜨린 경우이기 때문이다.
     */
    @Test
    void blankFileExtReturns400() throws Exception {
        Long eventId = createEvent();

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                .content(imageBody(" ", 1024)))
                .andExpect(status().isBadRequest());
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
                                .content(imageBody("png", limit)))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                .content(imageBody("png", limit + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    /* 없는 행사에는 키를 발급하지 않는다 — 경로의 행사가 형식 검사보다 먼저다 */
    @Test
    void issueUploadUrlForUnknownEventReturns404() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS + "/999999/images"), managerToken)
                                .content(imageBody("png", 1024)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* 클래스 레벨 EVENT_MANAGE다 — 발급도 예외가 아니다 */
    @Test
    void issueUploadUrlWithoutEventManageIsForbidden() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS + "/1/images"), outsiderToken)
                                .content(imageBody("png", 1024)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* ── 도우미 ───────────────────────────────────────────── */

    /* 발급 한 번의 결과 중 이 테스트가 보는 두 값 — 키의 확장자와 서버가 정한 형식 */
    private record Issued(String objectKey, String contentType) {}

    private Issued issued(Long eventId, String fileExt) throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                        .content(imageBody(fileExt, 1024)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new Issued(
                JsonPath.parse(response).read("$.data.objectKey", String.class),
                JsonPath.parse(response).read("$.data.contentType", String.class));
    }

    private void expectImageBadRequest(Long eventId, String fileExt) throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/images"), managerToken)
                                .content(imageBody(fileExt, 1024)))
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
}
