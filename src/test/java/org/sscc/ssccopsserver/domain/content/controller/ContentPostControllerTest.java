package org.sscc.ssccopsserver.domain.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.content.repository.ContentPostHistoryRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
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

/*
 * 포스트 관리 API (ssccops#381). 페이지와 같은 규칙(ContentPageControllerTest)에 더해 표지 검증·
 * from-event 복사·분류 필터를 본다. 갤러리 발급·삭제는 S3Presigner 스텁이 필요해
 * ContentPostImageControllerTest에 따로 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class ContentPostControllerTest {

    private static final String POSTS = "/v1/content/posts";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private ContentPostHistoryRepository historyRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;

    private UUID editorToken;
    private MemberEntity editor;

    @BeforeEach
    void setUp() {
        editorToken = UUID.randomUUID();
        editor = saveMember(editorToken, "20260301", "홍보국원");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                editor,
                AuthorityCode.CONTENT_MANAGE);
    }

    @Test
    @DisplayName("생성·수정·게시가 이력을 남기고 표지 없는 초안으로 시작한다")
    void createUpdatePublishWithHistory() throws Exception {
        String created =
                mockMvc.perform(
                                authorized(post(POSTS), editorToken)
                                        .content(
                                                postBody(
                                                        "homecoming-2026",
                                                        "EVENT",
                                                        "홈커밍 2026",
                                                        "2026-09-12")))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.pubSttsCd").value("DRAFT"))
                        .andExpect(jsonPath("$.data.cntntClsfCd").value("EVENT"))
                        .andExpect(jsonPath("$.data.actvYmd").value("2026-09-12"))
                        .andExpect(jsonPath("$.data.coverFileId").isEmpty())
                        .andExpect(jsonPath("$.data.gallery").isArray())
                        .andExpect(jsonPath("$.data.gallery.length()").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long postId = JsonPath.parse(created).read("$.data.postId", Long.class);
        assertThat(historyRepository.findAllByPostId(postId)).hasSize(1);

        mockMvc.perform(
                        authorized(patch(POSTS + "/" + postId), editorToken)
                                .content(
                                        postBody(
                                                "homecoming-2026",
                                                "NEWS",
                                                "홈커밍 2026 후기",
                                                "2026-09-13")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cntntClsfCd").value("NEWS"))
                .andExpect(jsonPath("$.data.ttl").value("홈커밍 2026 후기"));

        mockMvc.perform(authorized(post(POSTS + "/" + postId + "/publish"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pubSttsCd").value("PUBLISHED"));

        mockMvc.perform(authorized(get(POSTS + "/" + postId + "/history"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].pubSttsCd").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[2].cntntClsfCd").value("EVENT"))
                .andExpect(jsonPath("$.data[2].actvYmd").value("2026-09-12"));
    }

    @Test
    @DisplayName("from-event — 행사의 제목·일시·장소·본문을 복사한 초안이 생기고 eventId가 연결된다")
    void fromEventCopiesTitleDatePlaceAndBody() throws Exception {
        Long eventId =
                saveEvent(
                        "2026 홈커밍",
                        "# 홈커밍 안내\n\n선배님들을 모십니다.",
                        Instant.parse("2026-09-12T09:00:00Z"),
                        "학생회관 2층");

        String created =
                mockMvc.perform(authorized(post(POSTS + "/from-event/" + eventId), editorToken))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.pubSttsCd").value("DRAFT"))
                        .andExpect(jsonPath("$.data.cntntClsfCd").value("EVENT"))
                        .andExpect(jsonPath("$.data.ttl").value("2026 홈커밍"))
                        .andExpect(jsonPath("$.data.slug").value("event-" + eventId))
                        .andExpect(jsonPath("$.data.eventId").value(eventId))
                        // 행사 시작 09:00Z = 18:00 KST 같은 날
                        .andExpect(jsonPath("$.data.actvYmd").value("2026-09-12"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String body = JsonPath.parse(created).read("$.data.mtxt", String.class);
        assertThat(body)
                .startsWith("> 일시: 2026-09-12 18:00 ~ 2026-09-12 21:00 · 장소: 학생회관 2층")
                .contains("# 홈커밍 안내")
                .contains("선배님들을 모십니다.");
        Long postId = JsonPath.parse(created).read("$.data.postId", Long.class);
        assertThat(historyRepository.findAllByPostId(postId)).hasSize(1);

        // 같은 행사를 두 번 갈무리하면 slug에 -2가 붙는다
        mockMvc.perform(authorized(post(POSTS + "/from-event/" + eventId), editorToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value("event-" + eventId + "-2"));
    }

    @Test
    @DisplayName("from-event — 없는 행사는 행사 도메인과 같은 404 EVENT_NOT_FOUND")
    void fromEventWithUnknownEventIsNotFound() throws Exception {
        mockMvc.perform(authorized(post(POSTS + "/from-event/999999"), editorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("목록은 분류·상태 필터를 받는다")
    void listFiltersByCategoryAndStatus() throws Exception {
        Long academic = create("study-1", "ACADEMIC", "스터디", "2026-03-01");
        Long news = create("news-1", "NEWS", "수상", "2026-03-02");
        mockMvc.perform(authorized(post(POSTS + "/" + news + "/publish"), editorToken))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(POSTS + "?cntntClsfCd=ACADEMIC"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].postId").value(academic))
                .andExpect(jsonPath("$.data[0].mtxt").doesNotExist());

        mockMvc.perform(
                        authorized(
                                get(POSTS + "?pubSttsCd=PUBLISHED&cntntClsfCd=NEWS"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].postId").value(news))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    @Test
    @DisplayName("갤러리에 없는 파일을 표지로 주면 400 COVER_NOT_IN_GALLERY")
    void coverOutsideGalleryIsBadRequest() throws Exception {
        Long postId = create("p-1", "NEWS", "포스트", "2026-03-01");

        mockMvc.perform(
                        authorized(patch(POSTS + "/" + postId), editorToken)
                                .content(
                                        """
                                        {"slug":"p-1","cntntClsfCd":"NEWS","ttl":"포스트",
                                         "mtxt":"# x","actvYmd":"2026-03-01","coverFileId":424242}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COVER_NOT_IN_GALLERY"));
    }

    @Test
    @DisplayName("본문이 10만 자를 넘으면 413 CONTENT_TOO_LARGE")
    void oversizedBodyIsPayloadTooLarge() throws Exception {
        String huge = "a".repeat(100_001);
        mockMvc.perform(
                        authorized(post(POSTS), editorToken)
                                .content(
                                        """
                                        {"slug":"big","cntntClsfCd":"NEWS","ttl":"큰 글",
                                         "mtxt":"%s","actvYmd":"2026-03-01"}
                                        """
                                                .formatted(huge)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LARGE"));
    }

    private Long create(String slug, String category, String title, String activityDate)
            throws Exception {
        String body =
                mockMvc.perform(
                                authorized(post(POSTS), editorToken)
                                        .content(postBody(slug, category, title, activityDate)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(body).read("$.data.postId", Long.class);
    }

    private static String postBody(
            String slug, String category, String title, String activityDate) {
        return """
                {"slug":"%s","cntntClsfCd":"%s","ttl":"%s","smry":"요약","mtxt":"# %s",
                 "actvYmd":"%s"}
                """
                .formatted(slug, category, title, title, activityDate);
    }

    private Long saveEvent(String title, String markdown, Instant beginAt, String place) {
        EventClassificationEntity classification =
                eventClassificationRepository.findById("EVENT").orElseThrow();
        EventEntity event =
                EventEntity.create(
                        classification,
                        editor,
                        title,
                        markdown,
                        null,
                        null,
                        beginAt,
                        beginAt == null ? null : beginAt.plusSeconds(3600 * 3),
                        place,
                        null);
        return eventRepository.saveAndFlush(event).getId();
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
