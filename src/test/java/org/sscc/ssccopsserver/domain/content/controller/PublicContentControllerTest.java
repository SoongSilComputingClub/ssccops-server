package org.sscc.ssccopsserver.domain.content.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageEntity;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;
import org.sscc.ssccopsserver.domain.content.repository.ContentPageRepository;
import org.sscc.ssccopsserver.domain.content.repository.ContentPostRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.PublicCacheControl;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 익명 콘텐츠 셋 (ssccops#381 · ADR-0038) — 페이지·포스트·접수 중 폼. 토큰 없이 부르고, 게시본만
 * 보이고, 초안은 없는 것과 같은 404이고, 응답에 수정자·이력이 없고, Cache-Control이 실리는지를
 * 본다. 폼 쪽 핸들러는 form 도메인(PublicFormMetaController)에 있지만 «익명 콘텐츠 셋»의 계약이라
 * 여기서 함께 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, PublicContentControllerTest.FixedClockConfig.class})
@Transactional
class PublicContentControllerTest {

    /** 고정 기준 시각 (2026-09-19 12:00 KST) */
    private static final Instant NOW = Instant.parse("2026-09-19T03:00:00Z");

    private static final Instant PAST = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NEAR_FUTURE = Instant.parse("2026-09-25T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private ContentPageRepository pageRepository;
    @Autowired private ContentPostRepository postRepository;
    @Autowired private FormRepository formRepository;

    private MemberEntity editor;

    @BeforeEach
    void setUp() {
        editor =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260401",
                        "홍보국장",
                        "20260401@soongsil.ac.kr");
    }

    @Test
    @DisplayName("게시된 페이지는 토큰 없이 열리고 수정자·이력·상태가 없다")
    void publishedPageIsOpenAndCarriesOnlyPublicFields() throws Exception {
        savePage("about", "소개", true);

        mockMvc.perform(get("/public/v1/pages/about"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", PublicCacheControl.HEADER_VALUE))
                .andExpect(jsonPath("$.data.slug").value("about"))
                .andExpect(jsonPath("$.data.ttl").value("소개"))
                .andExpect(jsonPath("$.data.mtxt").value("# 소개"))
                .andExpect(jsonPath("$.data.pubDt").isNotEmpty())
                .andExpect(jsonPath("$.data.pageId").doesNotExist())
                .andExpect(jsonPath("$.data.pubSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.mdfcnMbrId").doesNotExist())
                .andExpect(jsonPath("$.data.mdfcnMbrNm").doesNotExist())
                .andExpect(jsonPath("$.data.regDt").doesNotExist());
    }

    @Test
    @DisplayName("초안 페이지와 없는 slug는 같은 404 PAGE_NOT_FOUND이고 공개 캐시가 붙지 않는다")
    void draftPageLooksLikeMissingPage() throws Exception {
        savePage("draft-page", "초안", false);

        mockMvc.perform(get("/public/v1/pages/draft-page"))
                .andExpect(status().isNotFound())
                // 404에는 공개 캐시가 붙지 않는다 — 남는 것은 시큐리티 기본 no-store뿐이다
                .andExpect(header().string("Cache-Control", not(containsString("s-maxage"))))
                .andExpect(jsonPath("$.code").value("PAGE_NOT_FOUND"));
        mockMvc.perform(get("/public/v1/pages/no-such-page"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("포스트 목록은 게시본만, 활동일 최신순, 분류 필터와 커서를 받는다")
    void postListShowsPublishedOnlyNewestActivityFirst() throws Exception {
        savePost("old-study", ContentCategory.ACADEMIC, LocalDate.of(2026, 3, 1), true);
        savePost("homecoming", ContentCategory.EVENT, LocalDate.of(2026, 9, 12), true);
        savePost("award", ContentCategory.NEWS, LocalDate.of(2026, 6, 1), true);
        savePost("secret-draft", ContentCategory.NEWS, LocalDate.of(2026, 9, 18), false);

        String page1 =
                mockMvc.perform(get("/public/v1/posts?size=2"))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string("Cache-Control", PublicCacheControl.HEADER_VALUE))
                        .andExpect(jsonPath("$.data.length()").value(2))
                        .andExpect(jsonPath("$.data[0].slug").value("homecoming"))
                        .andExpect(jsonPath("$.data[1].slug").value("award"))
                        .andExpect(jsonPath("$.data[0].mtxt").doesNotExist())
                        .andExpect(jsonPath("$.data[0].pubSttsCd").doesNotExist())
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andExpect(jsonPath("$.page.totalCount").value(3))
                        .andExpect(jsonPath("$.page.overallCount").value(3))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(page1).read("$.page.nextCursor", String.class);

        mockMvc.perform(get("/public/v1/posts?size=2&cursor=" + cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].slug").value("old-study"))
                .andExpect(jsonPath("$.page.hasNext").value(false));

        mockMvc.perform(get("/public/v1/posts?category=NEWS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].slug").value("award"))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(3));
    }

    @Test
    @DisplayName("포스트 상세는 게시본만 열리고 초안은 404 POST_NOT_FOUND")
    void postDetailIsPublishedOnly() throws Exception {
        savePost("homecoming", ContentCategory.EVENT, LocalDate.of(2026, 9, 12), true);
        savePost("secret-draft", ContentCategory.NEWS, LocalDate.of(2026, 9, 18), false);

        mockMvc.perform(get("/public/v1/posts/homecoming"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", PublicCacheControl.HEADER_VALUE))
                .andExpect(jsonPath("$.data.slug").value("homecoming"))
                .andExpect(jsonPath("$.data.cntntClsfCd").value("EVENT"))
                .andExpect(jsonPath("$.data.mtxt").value("# homecoming"))
                .andExpect(jsonPath("$.data.gallery").isArray())
                .andExpect(jsonPath("$.data.postId").doesNotExist())
                .andExpect(jsonPath("$.data.mdfcnMbrId").doesNotExist())
                .andExpect(jsonPath("$.data.pubSttsCd").doesNotExist());

        mockMvc.perform(get("/public/v1/posts/secret-draft"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    @DisplayName("잘못된 커서는 첫 페이지로 되돌리지 않고 400이다")
    void invalidCursorIsBadRequest() throws Exception {
        mockMvc.perform(get("/public/v1/posts?cursor=not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("초안 포스트의 갤러리 이미지는 주소만으로 열리지 않는다 — 404 POST_NOT_FOUND")
    void draftGalleryImageIsNotFound() throws Exception {
        Long draft =
                savePost("secret-draft", ContentCategory.NEWS, LocalDate.of(2026, 9, 18), false);

        mockMvc.perform(get("/public/v1/posts/" + draft + "/images/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    @DisplayName("접수 중인 폼 목록 — OPEN이고 기간 안인 폼만, 마감 가까운 순, 키·제목·마감뿐")
    void openFormsListsAcceptingFormsOnly() throws Exception {
        saveForm("마감 먼 지원서", FormStatus.OPEN, FUTURE);
        saveForm("마감 가까운 지원서", FormStatus.OPEN, NEAR_FUTURE);
        saveForm("마감 없는 설문", FormStatus.OPEN, null);
        saveForm("지난 지원서", FormStatus.OPEN, PAST);
        saveForm("초안 폼", FormStatus.DRAFT, FUTURE);
        saveForm("닫힌 폼", FormStatus.CLOSED, FUTURE);

        mockMvc.perform(get("/public/v1/forms/open"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", PublicCacheControl.HEADER_VALUE))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].formTtlNm").value("마감 가까운 지원서"))
                .andExpect(jsonPath("$.data[1].formTtlNm").value("마감 먼 지원서"))
                .andExpect(jsonPath("$.data[2].formTtlNm").value("마감 없는 설문"))
                .andExpect(jsonPath("$.data[2].rcptEndDt").isEmpty())
                .andExpect(jsonPath("$.data[0].formKey").isNotEmpty())
                .andExpect(jsonPath("$.data[0].formId").doesNotExist())
                .andExpect(jsonPath("$.data[0].formSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data[0].qitemCpstCn").doesNotExist())
                .andExpect(jsonPath("$.data[0].creatrMbrId").doesNotExist());
    }

    private void savePage(String slug, String title, boolean published) {
        ContentPageEntity page = ContentPageEntity.create(slug, title, "# " + title, editor);
        if (published) {
            page.publish(NOW, editor);
        }
        pageRepository.saveAndFlush(page);
    }

    private Long savePost(
            String slug, ContentCategory category, LocalDate activityDate, boolean published) {
        ContentPostEntity post =
                ContentPostEntity.create(
                        slug, category, slug, "요약", "# " + slug, activityDate, null, editor);
        if (published) {
            post.publish(NOW, editor);
        }
        return postRepository.saveAndFlush(post).getId();
    }

    private void saveForm(String title, FormStatus status, Instant receiptEndAt) {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new Page("기본 정보", null)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "이름",
                                        QuestionItemType.SHORT_TEXT,
                                        true,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        formRepository.saveAndFlush(
                FormEntity.create(editor, title, composition, null, receiptEndAt, status));
    }

    @TestConfiguration
    static class FixedClockConfig {

        /** 접수 중 판정(FormReceiptPolicy)이 주입된 Clock에서 오므로 시각을 고정한다 */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }
    }
}
