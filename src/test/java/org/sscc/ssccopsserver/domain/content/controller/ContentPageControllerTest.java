package org.sscc.ssccopsserver.domain.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.sscc.ssccopsserver.domain.content.repository.ContentPageHistoryRepository;
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
 * 페이지 관리 API (ssccops#381). CONTENT_MANAGE 뒤의 생성·수정·게시·게시 취소·이력·목록과
 * 인가(403)·slug 충돌(409)을 본다. 트랜잭션 테스트라 **거절되는 요청은 한 테스트에 하나, 마지막에**
 * 둔다(참여 트랜잭션의 rollback-only 표시 — event/AGENTS.md 테스트 함정).
 *
 * V16 시드가 test 프로필의 data-locations에 들어 있어야 AuthorityFixture가 CONTENT_MANAGE를 찾는다 —
 * 이 클래스가 뜨는 것 자체가 그 등록의 확인이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class ContentPageControllerTest {

    private static final String PAGES = "/v1/content/pages";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private ContentPageHistoryRepository historyRepository;

    private UUID editorToken;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        editorToken = UUID.randomUUID();
        MemberEntity editor = saveMember(editorToken, "20260201", "홍보국장");
        grant(editor, AuthorityCode.CONTENT_MANAGE);

        // 다른 권한만 가진 회원 — 403이 «권한 때문»임이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260202", "행사운영자");
        grant(outsider, AuthorityCode.EVENT_MANAGE);
    }

    @Test
    @DisplayName("생성 → 수정 → 게시 → 게시 취소가 각각 이력 한 행을 남긴다")
    void everyMutationAppendsOneHistoryRow() throws Exception {
        String created =
                mockMvc.perform(
                                authorized(post(PAGES), editorToken)
                                        .content(pageBody("about", "소개", "# 소개")))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.pubSttsCd").value("DRAFT"))
                        .andExpect(jsonPath("$.data.pubDt").isEmpty())
                        .andExpect(jsonPath("$.data.mdfcnMbrNm").value("홍보국장"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long pageId = JsonPath.parse(created).read("$.data.pageId", Long.class);
        assertThat(historyRepository.findAllByPageId(pageId)).hasSize(1);

        mockMvc.perform(
                        authorized(patch(PAGES + "/" + pageId), editorToken)
                                .content(pageBody("about", "소개(개정)", "# 소개 v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ttl").value("소개(개정)"))
                .andExpect(jsonPath("$.data.pubSttsCd").value("DRAFT"));
        assertThat(historyRepository.findAllByPageId(pageId)).hasSize(2);

        mockMvc.perform(authorized(post(PAGES + "/" + pageId + "/publish"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pubSttsCd").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.pubDt").isNotEmpty());
        assertThat(historyRepository.findAllByPageId(pageId)).hasSize(3);

        mockMvc.perform(authorized(post(PAGES + "/" + pageId + "/unpublish"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pubSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.pubDt").isEmpty());
        assertThat(historyRepository.findAllByPageId(pageId)).hasSize(4);

        // 이력은 최신이 먼저이고 한 행이 그 시점의 전체 스냅샷이다
        mockMvc.perform(authorized(get(PAGES + "/" + pageId + "/history"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].pubSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data[1].pubSttsCd").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[1].mtxt").value("# 소개 v2"))
                .andExpect(jsonPath("$.data[3].ttl").value("소개"))
                .andExpect(jsonPath("$.data[3].chgMbrNm").value("홍보국장"));
    }

    @Test
    @DisplayName("목록은 상태 필터와 커서를 받고 본문을 싣지 않는다")
    void listFiltersByStatusAndPages() throws Exception {
        Long first = create("one", "하나");
        Long second = create("two", "둘");
        Long third = create("three", "셋");
        mockMvc.perform(authorized(post(PAGES + "/" + second + "/publish"), editorToken))
                .andExpect(status().isOk());

        String page1 =
                mockMvc.perform(authorized(get(PAGES + "?size=2"), editorToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(2))
                        .andExpect(jsonPath("$.data[0].pageId").value(third))
                        .andExpect(jsonPath("$.data[0].mtxt").doesNotExist())
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andExpect(jsonPath("$.page.totalCount").value(3))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(page1).read("$.page.nextCursor", String.class);

        mockMvc.perform(authorized(get(PAGES + "?size=2&cursor=" + cursor), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pageId").value(first))
                .andExpect(jsonPath("$.page.hasNext").value(false));

        mockMvc.perform(authorized(get(PAGES + "?pubSttsCd=PUBLISHED"), editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pageId").value(second))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(3));
    }

    @Test
    @DisplayName("같은 slug는 409 CONTENT_SLUG_DUPLICATED")
    void duplicateSlugIsConflict() throws Exception {
        create("about", "소개");

        mockMvc.perform(
                        authorized(post(PAGES), editorToken)
                                .content(pageBody("about", "다른 소개", "# x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_SLUG_DUPLICATED"));
    }

    @Test
    @DisplayName("slug 규칙을 어기면 400 — 대문자·공백·한글은 받지 않는다")
    void invalidSlugIsBadRequest() throws Exception {
        mockMvc.perform(
                        authorized(post(PAGES), editorToken)
                                .content(pageBody("About Us", "소개", "# x")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미 게시된 페이지를 다시 게시하면 409")
    void publishingTwiceIsConflict() throws Exception {
        Long pageId = create("about", "소개");
        mockMvc.perform(authorized(post(PAGES + "/" + pageId + "/publish"), editorToken))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(post(PAGES + "/" + pageId + "/publish"), editorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_ALREADY_PUBLISHED"));
    }

    @Test
    @DisplayName("CONTENT_MANAGE가 없으면 403이고 404로 감추지 않는다")
    void outsiderIsForbidden() throws Exception {
        mockMvc.perform(
                        authorized(post(PAGES), outsiderToken)
                                .content(pageBody("about", "소개", "# x")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰이 없으면 401 — 어드민 경로는 익명에게 닫혀 있다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PAGES)).andExpect(status().isUnauthorized());
    }

    private Long create(String slug, String title) throws Exception {
        String body =
                mockMvc.perform(
                                authorized(post(PAGES), editorToken)
                                        .content(pageBody(slug, title, "# " + title)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(body).read("$.data.pageId", Long.class);
    }

    private static String pageBody(String slug, String title, String markdown) {
        return """
                {"slug": "%s", "ttl": "%s", "mtxt": "%s"}
                """
                .formatted(slug, title, markdown);
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

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
