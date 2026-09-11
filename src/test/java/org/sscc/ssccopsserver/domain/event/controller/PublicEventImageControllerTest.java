package org.sscc.ssccopsserver.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/*
 * 행사 이미지 읽기(#208 · GET /public/v1/events/{eventId}/images/{fileName}) 통합 검증.
 *
 * **모든 요청에 Authorization 헤더가 없다.** PublicEventControllerTest와 같은 이유다 —
 * permitAll이 실제로 걸려 있는지는 필터체인을 통째로 태워 봐야 확인되고, 토큰을 붙이면
 * SecurityConfig의 규칙이 사라져도 테스트는 계속 초록으로 남는다. 행사 상세가 익명에게 열리는데
 * 그 본문의 이미지가 토큰을 요구하면 화면이 성립하지 않는다.
 *
 * **S3Presigner를 목으로 갈아 끼운다** — 확인하려는 것은 서명 알고리즘이 아니라 서버가 무엇에
 * 서명을 요청하는가(버킷·키·유효기간)와, **무엇을 확인한 뒤에** 서명하는가다. 목은 요청받은 키를
 * URL에 실어 돌려주므로 302의 Location만 봐도 어느 오브젝트를 지목했는지 드러난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class PublicEventImageControllerTest {

    private static final String PUBLIC_EVENTS = "/public/v1/events";

    /** application-test.yaml의 r2.bucket-name과 같은 값이어야 한다 */
    private static final String BUCKET = "test-bucket";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;

    @MockitoBean private S3Presigner r2Presigner;

    private MemberEntity creator;
    private int studentNumberSeq = 1;

    /** 발급 규칙과 같은 형태의 파일명 — 소문자 UUID + 허용 확장자 */
    private String fileName;

    @BeforeEach
    void setUp() {
        creator = saveMember("행사운영자");
        fileName = UUID.randomUUID() + ".png";

        when(r2Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            GetObjectPresignRequest presignRequest = invocation.getArgument(0);
                            return stubPresignedGetObject(presignRequest.getObjectRequest().key());
                        });
    }

    /*
     * 기본형. 302이고 Location이 방금 서명한 R2 주소이며, 서명을 요청한 내용(버킷·키·15분)까지
     * 한자리에서 못 박는다.
     *
     * **본문이 비어 있다는 것도 계약이다** — 서버가 바이트를 중계하면 이미지가 전부 컨테이너
     * 메모리를 지난다(#107). 여기가 프록시로 바뀌면 이 단언이 먼저 깨진다.
     */
    @Test
    void publishedEventImageRedirectsToSignedUrl() throws Exception {
        Long eventId = saveEvent("게시된 모집", true);

        var response =
                mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId + "/images/" + fileName))
                        .andExpect(status().isFound())
                        .andExpect(header().exists("Location"))
                        .andReturn()
                        .getResponse();

        assertThat(response.getHeader("Location"))
                .contains("events/" + eventId + "/" + fileName)
                .contains("X-Amz-Signature");
        assertThat(response.getContentAsByteArray()).isEmpty();

        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(r2Presigner).presignGetObject(captor.capture());
        GetObjectPresignRequest presignRequest = captor.getValue();
        assertThat(presignRequest.getObjectRequest().bucket()).isEqualTo(BUCKET);
        // 키 조립은 발급 쪽과 한 곳이다 (EventImageLocation) — 규칙이 갈리면 발급한 주소가 빈다
        assertThat(presignRequest.getObjectRequest().key())
                .isEqualTo("events/" + eventId + "/" + fileName);
        // 학술 인증사진(SessionFileReferenceViewer)과 같은 15분이다
        assertThat(presignRequest.signatureDuration().toMinutes()).isEqualTo(15);
    }

    /*
     * 302에 캐시가 붙고, 그 수명이 **서명보다 짧다** (ssccops ADR-0010).
     *
     * 두 단언을 한자리에 두는 것이 요점이다 — 확인하려는 것은 "캐시가 붙었다"가 아니라 "캐시가
     * 서명보다 짧다"이고, 그 관계가 깨지면 캐시된 302가 이미 죽은 서명을 가리켜 이미지가
     * 깨진다. 값을 상수로 못 박지 않고 **실제 서명 유효기간과 비교**하는 것은 그래서다: 나중에
     * FilePresigner의 VIEW_URL_TTL이 줄어도 이 테스트가 관계를 계속 지킨다(둘 중 하나만 고치면
     * 여기서 걸린다).
     */
    @Test
    void redirectIsCacheableForLessThanTheSignatureLifetime() throws Exception {
        Long eventId = saveEvent("게시된 모집", true);

        var response =
                mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId + "/images/" + fileName))
                        .andExpect(status().isFound())
                        .andExpect(header().exists("Cache-Control"))
                        .andReturn()
                        .getResponse();

        String cacheControl = response.getHeader("Cache-Control");
        assertThat(cacheControl).contains("public").contains("max-age=");

        // 이 정규식의 백트래킹(Sonar S8786)은 실질이 없다 — 입력이 서버가 만든 한 줄짜리
        // Cache-Control 헤더이지 요청 값이 아니다 (#357)
        long maxAge = Long.parseLong(cacheControl.replaceAll(".*max-age=(\\d+).*", "$1"));
        assertThat(maxAge).isPositive();

        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(r2Presigner).presignGetObject(captor.capture());
        long signatureSeconds = captor.getValue().signatureDuration().toSeconds();
        assertThat(maxAge).isLessThan(signatureSeconds);
    }

    /*
     * 404는 캐시되지 않는다. 게시 직전에 열어 본 사람이 404를 들고 있으면 게시한 뒤에도 한동안
     * 이미지가 안 보인다 — 캐시를 여는 결정이 성공 응답 하나에만 걸려 있다는 것을 못 박아 둔다.
     *
     * 헤더가 아예 없는 것을 요구하지 않는 것은 시큐리티가 기본으로 `no-store, max-age=0`을
     * 써 넣기 때문이다. 확인하려는 것은 그 유무가 아니라 **0보다 큰 max-age가 없다**는 것이다.
     */
    @Test
    void notFoundResponsesAreNotCacheable() throws Exception {
        Long draft = saveEvent("작성 중 모집", false);

        var response =
                mockMvc.perform(get(PUBLIC_EVENTS + "/" + draft + "/images/" + fileName))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse();

        String cacheControl = response.getHeader("Cache-Control");
        if (cacheControl != null) {
            assertThat(cacheControl).doesNotContainPattern("max-age=[1-9]");
        }
    }

    /*
     * 미게시(DRAFT)·보관(ARCHIVED)·없는 행사가 **모두 같은 404**다. 지금까지는 주소만 알면
     * 미공개 행사의 포스터가 열렸다 — 판정을 리다이렉트 앞에 두는 것이 이 이슈의 절반이다.
     *
     * 셋을 한자리에 두는 이유는 확인하려는 것이 "구별되지 않는다"라서 경우 간 비교가 곧 규칙이기
     * 때문이다. 코드가 갈리면 그 차이가 곧 "그 번호에 무엇인가 있다"가 된다.
     */
    @Test
    void draftArchivedAndUnknownEventsAllReturnSame404() throws Exception {
        Long draft = saveEvent("작성 중 모집", false);
        Long archived = saveEvent("보관된 세미나", true);
        archive(archived);

        for (Long eventId : List.of(draft, archived, 999999L)) {
            mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId + "/images/" + fileName))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        }

        // 자격이 없으면 서명 자체를 만들지 않는다 — 만들어 두고 응답에서 빼는 구조가 아니다
        verifyNoInteractions(r2Presigner);
    }

    /*
     * 발급한 적 없는 형태의 파일명은 404다. **이 검사가 지키는 것은 오브젝트 키다** — 버킷에는
     * 학술 출석 인증사진이 같이 들어 있어(ssccops#156) 파일명이 키를 조작할 수 있으면 그것이 곧
     * 남의 얼굴 사진이다.
     *
     * 게시된 행사로 확인하는 것이 요점이다: 행사가 보이는데도 막힌다는 것이 파일명 검사가 실제로
     * 도는 증거다. 서명이 한 번도 만들어지지 않는 것까지 함께 본다.
     */
    @Test
    void fileNamesWeNeverIssuedReturn404() throws Exception {
        Long eventId = saveEvent("게시된 모집", true);

        List<String> rejected =
                List.of(
                        // 확장자가 허용 목록 밖 — SVG는 스크립트를 담을 수 있어 애초에 뺐다
                        UUID.randomUUID() + ".svg",
                        // UUID가 아니다
                        "poster.png",
                        // 확장자가 없다
                        UUID.randomUUID().toString(),
                        // 상위 경로로 넘어가려는 시도
                        "..png");

        for (String candidate : rejected) {
            mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId + "/images/" + candidate))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_IMAGE_NOT_FOUND"));
        }

        verifyNoInteractions(r2Presigner);
    }

    /*
     * 경로 구분자가 낀 파일명은 302가 되지 않는다.
     *
     * 인코딩하지 않은 `/`는 스프링이 파일명 하나로 읽지 않아 우리 핸들러에 닿기 전에 끊기고,
     * 인코딩한 `%2F`는 시큐리티의 요청 방화벽이 먼저 거절한다. **어느 쪽이든 끊긴다는 사실을
     * 못 박아 둔다** — 라우팅이나 방화벽 설정이 바뀌어 이 요청이 핸들러에 닿게 되면 그때
     * 키 조작이 성립하는지를 다시 봐야 한다.
     */
    @Test
    void pathSeparatorInFileNameNeverRedirects() throws Exception {
        Long eventId = saveEvent("게시된 모집", true);
        String base = PUBLIC_EVENTS + "/" + eventId + "/images/";

        for (String candidate :
                List.of("../../academic-programs/1.png", "..%2Facademic-programs%2F1.png")) {
            mockMvc.perform(get(base + candidate))
                    .andExpect(
                            result ->
                                    assertThat(result.getResponse().getStatus()).isNotEqualTo(302));
        }

        verifyNoInteractions(r2Presigner);
    }

    /* ── 도우미 ───────────────────────────────────────────── */

    private Long saveEvent(String title, boolean published) {
        EventClassificationEntity classification =
                eventClassificationRepository.findById("RECRUIT").orElseThrow();
        EventEntity event =
                EventEntity.create(
                        classification,
                        creator,
                        title,
                        "# 모집 요강",
                        null,
                        null,
                        null,
                        null,
                        "학생회관 2층",
                        null);
        if (published) {
            event.changeStatus(EventStatusAction.PUBLISH);
        }
        return eventRepository.saveAndFlush(event).getId();
    }

    private void archive(Long eventId) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        event.changeStatus(EventStatusAction.ARCHIVE);
        eventRepository.flush();
    }

    private MemberEntity saveMember(String name) {
        String studentNumber = "2026%04d".formatted(studentNumberSeq++);
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }

    /* 진짜 서명 대신 키를 그대로 실은 URL을 돌려준다 (PresignedRequest.url()은 httpRequest에서 온다) */
    private static PresignedGetObjectRequest stubPresignedGetObject(String objectKey) {
        URI uri =
                URI.create(
                        "https://test-account.r2.cloudflarestorage.com/"
                                + BUCKET
                                + "/"
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
