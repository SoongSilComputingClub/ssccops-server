package org.sscc.ssccopsserver.domain.operation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkTagRelationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkTagRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 업무 태그 API (#624 · ssccops#565). 확인의 중심은 셋이다 — 지정이 «전체 교체»인가, 태그를 지우면
 * 지정만 떨어지고 업무는 남는가, 목록 필터가 커서·건수까지 태그를 따르는가.
 *
 * 트랜잭션 테스트라 실패하는 요청은 테스트마다 마지막 하나다(operation/AGENTS.md «테스트 함정»).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class WorkTagControllerTest {

    private static final UUID DIRECTOR_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();
    private static final String STAFF_ROLE = "국원";

    @Autowired private MockMvc mockMvc;
    @Autowired private WorkRepository workRepository;
    @Autowired private WorkTagRepository workTagRepository;
    @Autowired private WorkTagRelationRepository workTagRelationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        // 국장은 OPERATOR를 통해 WORK_MANAGE에 닿는다 (#9)
        MemberEntity director = saveMember(DIRECTOR_ID, "20200001", "김도현", "director@sscc.org");
        assignRole(director, MemberRoleFixture.DIRECTOR);
        ownerId = director.getId();

        // 국원은 WORK_READ만 — 태그 목록은 보지만 만들 수 없다
        MemberEntity staff = saveMember(STAFF_ID, "20200002", "이서연", "staff@sscc.org");
        // 시드된 '국원'이 WORK_READ를 갖는다(V3 · #101)
        assignRole(staff, STAFF_ROLE);
    }

    // ------------------------------------------------------------------ 태그 관리

    @Test
    void createsTagWithZeroUsage() throws Exception {
        mockMvc.perform(as(DIRECTOR_ID, post("/v1/work-tags")).content(tagBody("학술국")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workTagId").isNumber())
                .andExpect(jsonPath("$.data.tagNm").value("학술국"))
                .andExpect(jsonPath("$.data.usageCount").value(0));

        assertThat(workTagRepository.existsByName("학술국")).isTrue();
    }

    // 앞뒤 공백은 걷어 저장하므로 « 학술국 »도 같은 이름이다
    @Test
    void rejectsDuplicatedTagName() throws Exception {
        createTag("학술국");

        mockMvc.perform(as(DIRECTOR_ID, post("/v1/work-tags")).content(tagBody(" 학술국 ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_TAG_NAME_DUPLICATED"));
    }

    @Test
    void rejectsTagNameLongerThanFiftyCharacters() throws Exception {
        mockMvc.perform(as(DIRECTOR_ID, post("/v1/work-tags")).content(tagBody("가".repeat(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 이름을 바꾸면 이미 달린 업무의 칩도 새 이름이다 — 관계가 식별자를 들고 있어서다
    @Test
    void renamesTagAndWorksFollow() throws Exception {
        Long tagId = createTag("학술국");
        Long workId = createWork("정기 세미나");
        assign(workId, "[%d]".formatted(tagId));

        mockMvc.perform(as(DIRECTOR_ID, patch("/v1/work-tags/{id}", tagId)).content(tagBody("학술부")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagNm").value("학술부"))
                .andExpect(jsonPath("$.data.usageCount").value(1));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/works/{id}", workId)))
                .andExpect(jsonPath("$.data.tags[0].tagNm").value("학술부"));
    }

    // 자기 이름 그대로 저장은 중복이 아니다(멱등) — 다른 태그 이름과 겹칠 때만 409
    @Test
    void renameConflictsOnlyWithOtherTags() throws Exception {
        Long tagId = createTag("학술국");
        createTag("기획국");

        mockMvc.perform(as(DIRECTOR_ID, patch("/v1/work-tags/{id}", tagId)).content(tagBody("학술국")))
                .andExpect(status().isOk());

        mockMvc.perform(as(DIRECTOR_ID, patch("/v1/work-tags/{id}", tagId)).content(tagBody("기획국")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_TAG_NAME_DUPLICATED"));
    }

    @Test
    void renameUnknownTagReturns404() throws Exception {
        mockMvc.perform(
                        as(DIRECTOR_ID, patch("/v1/work-tags/{id}", 999_999L))
                                .content(tagBody("학술국")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_TAG_NOT_FOUND"));
    }

    /*
     * 태그를 지우면 지정이 함께 지워지고 업무는 그대로다(ssccops#565) — 폼 라벨이 use_yn을 내리는
     * 것과 갈리는 자리다.
     */
    @Test
    void deletingTagDetachesItButKeepsTheWork() throws Exception {
        Long academic = createTag("학술국");
        Long planning = createTag("기획국");
        Long workId = createWork("정기 세미나");
        assign(workId, "[%d, %d]".formatted(academic, planning));

        mockMvc.perform(as(DIRECTOR_ID, delete("/v1/work-tags/{id}", academic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(workTagRepository.existsById(academic)).isFalse();
        assertThat(workRepository.findById(workId)).isPresent();
        mockMvc.perform(as(DIRECTOR_ID, get("/v1/works/{id}", workId)))
                .andExpect(jsonPath("$.data.tags", hasSize(1)))
                .andExpect(jsonPath("$.data.tags[0].tagNm").value("기획국"));
    }

    @Test
    void deleteUnknownTagReturns404() throws Exception {
        mockMvc.perform(as(DIRECTOR_ID, delete("/v1/work-tags/{id}", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_TAG_NOT_FOUND"));
    }

    // 목록은 이름 오름차순이고 usageCount는 살아 있는 업무만 센다 — 지운 업무는 빠진다
    @Test
    void listsTagsByNameWithLiveUsageCount() throws Exception {
        Long academic = createTag("학술국");
        createTag("기획국");
        Long alive = createWork("정기 세미나");
        Long removed = createWork("지운 업무");
        assign(alive, "[%d]".formatted(academic));
        assign(removed, "[%d]".formatted(academic));
        mockMvc.perform(as(DIRECTOR_ID, delete("/v1/works/{id}", removed)))
                .andExpect(status().isOk());

        mockMvc.perform(as(STAFF_ID, get("/v1/work-tags")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].tagNm", contains("기획국", "학술국")))
                .andExpect(jsonPath("$.data[0].usageCount").value(0))
                .andExpect(jsonPath("$.data[1].usageCount").value(1));
    }

    // 국원(WORK_READ)은 목록은 보지만 만들 수 없다 — 403이지 404로 감추지 않는다
    @Test
    void staffCannotCreateTag() throws Exception {
        mockMvc.perform(as(STAFF_ID, get("/v1/work-tags"))).andExpect(status().isOk());

        mockMvc.perform(as(STAFF_ID, post("/v1/work-tags")).content(tagBody("학술국")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 지정 교체

    /*
     * 전체 교체 — 요청에 없는 것은 떨어지고, 유지되는 지정은 다시 만들지 않아 식별자(=지정 시각)가
     * 그대로다. 같은 태그가 두 번 실려 와도 한 번으로 본다.
     */
    @Test
    void replacesTagsWholesaleAndKeepsSurvivingAssignments() throws Exception {
        Long academic = createTag("학술국");
        Long planning = createTag("기획국");
        Long pr = createTag("홍보국");
        Long workId = createWork("정기 세미나");

        String first =
                assign(workId, "[%d, %d, %d]".formatted(academic, planning, academic))
                        .andExpect(jsonPath("$.data", hasSize(2)))
                        .andExpect(jsonPath("$.data[*].tagNm", contains("기획국", "학술국")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long keptRelationId = JsonPath.parse(first).read("$.data[1].workTagRelId", Long.class);

        assign(workId, "[%d, %d]".formatted(academic, pr))
                .andExpect(jsonPath("$.data[*].tagNm", contains("학술국", "홍보국")))
                .andExpect(jsonPath("$.data[0].workTagRelId").value(keptRelationId));

        assign(workId, "[]").andExpect(jsonPath("$.data", hasSize(0)));
        assertThat(workTagRelationRepository.count()).isZero();
    }

    @Test
    void assigningUnknownTagReturns404() throws Exception {
        Long workId = createWork("정기 세미나");

        mockMvc.perform(
                        as(DIRECTOR_ID, put("/v1/works/{id}/tags", workId))
                                .content(tagIds("[999999]")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_TAG_NOT_FOUND"));
    }

    // 필드가 빠진 요청은 «건드리지 마라»인지 «전부 지워라»인지 알 수 없어 400이다
    @Test
    void assigningWithoutTagIdsReturns400() throws Exception {
        Long workId = createWork("정기 세미나");

        mockMvc.perform(as(DIRECTOR_ID, put("/v1/works/{id}/tags", workId)).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void assigningToUnknownWorkReturns404() throws Exception {
        mockMvc.perform(as(DIRECTOR_ID, put("/v1/works/{id}/tags", 999_999L)).content(tagIds("[]")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 목록·상세

    /*
     * 목록 필터 — 그 태그가 달린 업무만, 건수도 필터 결과를 말한다. 카드와 상세에는 태그 칩이 실린다.
     * 태그가 없는 업무의 tags는 null이 아니라 빈 배열이다.
     */
    @Test
    void filtersWorkListByTagAndCarriesChips() throws Exception {
        Long academic = createTag("학술국");
        Long planning = createTag("기획국");
        Long seminar = createWork("정기 세미나");
        Long festival = createWork("축제 부스");
        createWork("태그 없는 업무");
        assign(seminar, "[%d, %d]".formatted(academic, planning));
        assign(festival, "[%d]".formatted(planning));

        mockMvc.perform(as(STAFF_ID, get("/v1/works")).param("tagId", academic.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].workId").value(seminar))
                .andExpect(jsonPath("$.data[0].tags[*].tagNm", contains("기획국", "학술국")))
                .andExpect(jsonPath("$.page.totalCount").value(1));

        mockMvc.perform(as(STAFF_ID, get("/v1/works")).param("tagId", planning.toString()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.page.totalCount").value(2));

        mockMvc.perform(as(STAFF_ID, get("/v1/works")))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].tags", hasSize(0)));

        // 지운 태그의 칩으로 조회해도 오류가 아니라 빈 결과다
        mockMvc.perform(as(STAFF_ID, get("/v1/works")).param("tagId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // 태그 필터를 건 채로 커서를 넘겨도 다른 태그의 업무가 섞이지 않는다
    @Test
    void tagFilterHoldsAcrossCursorPages() throws Exception {
        Long academic = createTag("학술국");
        for (int index = 0; index < 3; index++) {
            Long tagged = createWork("학술 업무 " + index);
            assign(tagged, "[%d]".formatted(academic));
            createWork("다른 업무 " + index);
        }

        String firstPage =
                mockMvc.perform(
                                as(STAFF_ID, get("/v1/works"))
                                        .param("tagId", academic.toString())
                                        .param("size", "2"))
                        .andExpect(jsonPath("$.data", hasSize(2)))
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(firstPage).read("$.page.nextCursor", String.class);

        mockMvc.perform(
                        as(STAFF_ID, get("/v1/works"))
                                .param("tagId", academic.toString())
                                .param("size", "2")
                                .param("cursor", cursor))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title", startsWith("학술 업무")))
                .andExpect(jsonPath("$.page.hasNext").value(false));
    }

    // ------------------------------------------------------------------ 도우미

    private Long createTag(String name) throws Exception {
        String response =
                mockMvc.perform(as(DIRECTOR_ID, post("/v1/work-tags")).content(tagBody(name)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.workTagId", Long.class);
    }

    private Long createWork(String title) throws Exception {
        String body =
                """
                {"title": "%s", "itemType": "EVENT", "ownerId": %d}
                """
                        .formatted(title, ownerId);
        String response =
                mockMvc.perform(as(DIRECTOR_ID, post("/v1/works")).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.workId", Long.class);
    }

    private org.springframework.test.web.servlet.ResultActions assign(Long workId, String ids)
            throws Exception {
        return mockMvc.perform(
                        as(DIRECTOR_ID, put("/v1/works/{id}/tags", workId)).content(tagIds(ids)))
                .andExpect(status().isOk());
    }

    private static String tagBody(String name) {
        return "{\"tagNm\": \"%s\"}".formatted(name);
    }

    private static String tagIds(String ids) {
        return "{\"tagIds\": %s}".formatted(ids);
    }

    private static MockHttpServletRequestBuilder as(
            UUID authUserId, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                email);
    }

    private void assignRole(MemberEntity member, String roleName) {
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                member,
                roleName);
    }
}
