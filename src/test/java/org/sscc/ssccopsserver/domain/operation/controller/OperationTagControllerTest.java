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

import jakarta.persistence.EntityManager;

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
import org.sscc.ssccopsserver.domain.operation.repository.OperationTagRelationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.OperationTagRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 운영 태그 API (#637 · ssccops#576 · 처음 #624). 확인의 중심은 넷이다 — 지정이 «전체 교체»인가, 태그를
 * 지우면 지정만 떨어지고 운영 건은 남는가, 업무·하위 업무·회의가 **같은 태그를 각자의 운영 건에** 다는가,
 * 목록·운영 통합 필터가 (커서·건수까지) 태그를 따르는가.
 *
 * 트랜잭션 테스트라 실패하는 요청은 테스트마다 마지막 하나다(operation/AGENTS.md «테스트 함정»).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class OperationTagControllerTest {

    private static final UUID DIRECTOR_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();
    private static final String STAFF_ROLE = "국원";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private WorkRepository workRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private OperationTagRepository operationTagRepository;
    @Autowired private OperationTagRelationRepository operationTagRelationRepository;
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
        mockMvc.perform(as(DIRECTOR_ID, post("/v1/operation-tags")).content(tagBody("학술국")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.operationTagId").isNumber())
                .andExpect(jsonPath("$.data.tagNm").value("학술국"))
                .andExpect(jsonPath("$.data.usageCount").value(0));

        assertThat(operationTagRepository.existsByName("학술국")).isTrue();
    }

    // 앞뒤 공백은 걷어 저장하므로 « 학술국 »도 같은 이름이다
    @Test
    void rejectsDuplicatedTagName() throws Exception {
        createTag("학술국");

        mockMvc.perform(as(DIRECTOR_ID, post("/v1/operation-tags")).content(tagBody(" 학술국 ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_TAG_NAME_DUPLICATED"));
    }

    @Test
    void rejectsTagNameLongerThanFiftyCharacters() throws Exception {
        mockMvc.perform(
                        as(DIRECTOR_ID, post("/v1/operation-tags"))
                                .content(tagBody("가".repeat(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 이름을 바꾸면 이미 달린 운영 건의 칩도 새 이름이다 — 관계가 식별자를 들고 있어서다
    @Test
    void renamesTagAndOperationsFollow() throws Exception {
        Long tagId = createTag("학술국");
        Created work = createWork("정기 세미나");
        assign(work.operationId(), "[%d]".formatted(tagId));

        mockMvc.perform(
                        as(DIRECTOR_ID, patch("/v1/operation-tags/{id}", tagId))
                                .content(tagBody("학술부")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagNm").value("학술부"))
                .andExpect(jsonPath("$.data.usageCount").value(1));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/works/{id}", work.id())))
                .andExpect(jsonPath("$.data.tags[0].tagNm").value("학술부"));
    }

    // 자기 이름 그대로 저장은 중복이 아니다(멱등) — 다른 태그 이름과 겹칠 때만 409
    @Test
    void renameConflictsOnlyWithOtherTags() throws Exception {
        Long tagId = createTag("학술국");
        createTag("기획국");

        mockMvc.perform(
                        as(DIRECTOR_ID, patch("/v1/operation-tags/{id}", tagId))
                                .content(tagBody("학술국")))
                .andExpect(status().isOk());

        mockMvc.perform(
                        as(DIRECTOR_ID, patch("/v1/operation-tags/{id}", tagId))
                                .content(tagBody("기획국")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_TAG_NAME_DUPLICATED"));
    }

    @Test
    void renameUnknownTagReturns404() throws Exception {
        mockMvc.perform(
                        as(DIRECTOR_ID, patch("/v1/operation-tags/{id}", 999_999L))
                                .content(tagBody("학술국")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPERATION_TAG_NOT_FOUND"));
    }

    /*
     * 태그를 지우면 지정이 함께 지워지고 운영 건은 그대로다(ssccops#565) — 폼 라벨이 use_yn을 내리는
     * 것과 갈리는 자리다.
     */
    @Test
    void deletingTagDetachesItButKeepsTheOperation() throws Exception {
        Long academic = createTag("학술국");
        Long planning = createTag("기획국");
        Created work = createWork("정기 세미나");
        assign(work.operationId(), "[%d, %d]".formatted(academic, planning));

        mockMvc.perform(as(DIRECTOR_ID, delete("/v1/operation-tags/{id}", academic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(operationTagRepository.existsById(academic)).isFalse();
        assertThat(workRepository.findById(work.id())).isPresent();
        mockMvc.perform(as(DIRECTOR_ID, get("/v1/works/{id}", work.id())))
                .andExpect(jsonPath("$.data.tags", hasSize(1)))
                .andExpect(jsonPath("$.data.tags[0].tagNm").value("기획국"));
    }

    @Test
    void deleteUnknownTagReturns404() throws Exception {
        mockMvc.perform(as(DIRECTOR_ID, delete("/v1/operation-tags/{id}", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPERATION_TAG_NOT_FOUND"));
    }

    /*
     * 목록은 이름 오름차순이고 usageCount는 살아 있는 운영 건만 센다 — 업무·하위 업무·회의를 합치고
     * 지운 건은 빠진다.
     */
    @Test
    void listsTagsByNameWithLiveUsageCountAcrossOperationTypes() throws Exception {
        Long academic = createTag("학술국");
        createTag("기획국");
        Created alive = createWork("정기 세미나");
        Created removed = createWork("지운 업무");
        Created subWork = createSubWork(alive.id(), "발표자 섭외");
        Created meeting = createMeeting("학술국 회의");
        assign(alive.operationId(), "[%d]".formatted(academic));
        assign(removed.operationId(), "[%d]".formatted(academic));
        assign(subWork.operationId(), "[%d]".formatted(academic));
        assign(meeting.operationId(), "[%d]".formatted(academic));
        mockMvc.perform(as(DIRECTOR_ID, delete("/v1/works/{id}", removed.id())))
                .andExpect(status().isOk());

        mockMvc.perform(as(STAFF_ID, get("/v1/operation-tags")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].tagNm", contains("기획국", "학술국")))
                .andExpect(jsonPath("$.data[0].usageCount").value(0))
                .andExpect(jsonPath("$.data[1].usageCount").value(3));
    }

    // 국원(WORK_READ)은 목록은 보지만 만들 수 없다 — 403이지 404로 감추지 않는다
    @Test
    void staffCannotCreateTag() throws Exception {
        mockMvc.perform(as(STAFF_ID, get("/v1/operation-tags"))).andExpect(status().isOk());

        mockMvc.perform(as(STAFF_ID, post("/v1/operation-tags")).content(tagBody("학술국")))
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
        Created work = createWork("정기 세미나");

        String first =
                assign(work.operationId(), "[%d, %d, %d]".formatted(academic, planning, academic))
                        .andExpect(jsonPath("$.data", hasSize(2)))
                        .andExpect(jsonPath("$.data[*].tagNm", contains("기획국", "학술국")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long keptRelationId = JsonPath.parse(first).read("$.data[1].operationTagRelId", Long.class);

        assign(work.operationId(), "[%d, %d]".formatted(academic, pr))
                .andExpect(jsonPath("$.data[*].tagNm", contains("학술국", "홍보국")))
                .andExpect(jsonPath("$.data[0].operationTagRelId").value(keptRelationId))
                .andExpect(jsonPath("$.data[0].operationTagId").value(academic));

        assign(work.operationId(), "[]").andExpect(jsonPath("$.data", hasSize(0)));
        assertThat(operationTagRelationRepository.count()).isZero();
    }

    @Test
    void assigningUnknownTagReturns404() throws Exception {
        Created work = createWork("정기 세미나");

        mockMvc.perform(
                        as(DIRECTOR_ID, put("/v1/operations/{id}/tags", work.operationId()))
                                .content(tagIds("[999999]")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPERATION_TAG_NOT_FOUND"));
    }

    // 필드가 빠진 요청은 «건드리지 마라»인지 «전부 지워라»인지 알 수 없어 400이다
    @Test
    void assigningWithoutTagIdsReturns400() throws Exception {
        Created work = createWork("정기 세미나");

        mockMvc.perform(
                        as(DIRECTOR_ID, put("/v1/operations/{id}/tags", work.operationId()))
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void assigningToUnknownOperationReturns404() throws Exception {
        mockMvc.perform(
                        as(DIRECTOR_ID, put("/v1/operations/{id}/tags", 999_999L))
                                .content(tagIds("[]")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 지운 운영 건에는 달 수 없다 — 목록·상세에서 보이지 않는 건에 태그를 달 길을 열어 두지 않는다
    @Test
    void assigningToDeletedOperationReturns404() throws Exception {
        Created work = createWork("지운 업무");
        mockMvc.perform(as(DIRECTOR_ID, delete("/v1/works/{id}", work.id())))
                .andExpect(status().isOk());

        mockMvc.perform(
                        as(DIRECTOR_ID, put("/v1/operations/{id}/tags", work.operationId()))
                                .content(tagIds("[]")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 국원(WORK_READ)은 지정도 못 한다 — 관리와 지정이 같은 WORK_MANAGE다
    @Test
    void staffCannotAssignTags() throws Exception {
        Created work = createWork("정기 세미나");

        mockMvc.perform(
                        as(STAFF_ID, put("/v1/operations/{id}/tags", work.operationId()))
                                .content(tagIds("[]")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 업무 목록·상세

    /*
     * 목록 필터 — 그 태그가 달린 업무만, 건수도 필터 결과를 말한다. 카드와 상세에는 태그 칩이 실린다.
     * 태그가 없는 업무의 tags는 null이 아니라 빈 배열이다.
     */
    @Test
    void filtersWorkListByTagAndCarriesChips() throws Exception {
        Long academic = createTag("학술국");
        Long planning = createTag("기획국");
        Created seminar = createWork("정기 세미나");
        Created festival = createWork("축제 부스");
        createWork("태그 없는 업무");
        assign(seminar.operationId(), "[%d, %d]".formatted(academic, planning));
        assign(festival.operationId(), "[%d]".formatted(planning));

        mockMvc.perform(as(STAFF_ID, get("/v1/works")).param("tagId", academic.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].workId").value(seminar.id()))
                .andExpect(jsonPath("$.data[0].tags[*].tagNm", contains("기획국", "학술국")))
                .andExpect(jsonPath("$.data[0].tags[1].operationTagId").value(academic))
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
            Created tagged = createWork("학술 업무 " + index);
            assign(tagged.operationId(), "[%d]".formatted(academic));
            createWork("다른 업무 " + index);
        }
        /*
         * 커서는 DB에서 읽은 시각으로 만들어야 한다 (#632). 이 테스트는 한 트랜잭션 안에서 만들고 곧바로
         * 넘기므로, 비우지 않으면 첫 페이지가 영속성 컨텍스트에 남은 엔티티의 createdAt(Instant.now() —
         * 마이크로초 아래 자릿수가 있다)으로 커서를 만들고, 둘째 페이지는 timestamp(6)로 반올림된 DB 값과
         * 비교해 첫 페이지 마지막 행이 다시 나왔다(간헐 실패). 운영은 요청마다 DB에서 읽어 이 어긋남이 없다.
         */
        entityManager.flush();
        entityManager.clear();

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

    // ------------------------------------------------------------------ 하위 업무·회의

    /*
     * 하위 업무는 자기 운영 건의 태그를 본다 — 상위 업무에 «학술국»이 달려도 하위 업무가 물려받지
     * 않는다. 목록 필터·건수·행 칩·상세 칩이 같은 규칙이다.
     */
    @Test
    void subWorksCarryTheirOwnTagsAndFilterByThem() throws Exception {
        Long academic = createTag("학술국");
        Created work = createWork("정기 세미나");
        Created tagged = createSubWork(work.id(), "발표자 섭외");
        createSubWork(work.id(), "장소 예약");
        // 상위 업무에도 단다 — 하위 업무 «장소 예약»은 그래도 걸리지 않아야 한다
        assign(work.operationId(), "[%d]".formatted(academic));
        assign(tagged.operationId(), "[%d]".formatted(academic));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(as(STAFF_ID, get("/v1/sub-works")).param("tagId", academic.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].subWorkId").value(tagged.id()))
                .andExpect(jsonPath("$.data[0].tags[0].operationTagId").value(academic))
                .andExpect(jsonPath("$.data[0].tags[0].tagNm").value("학술국"))
                .andExpect(jsonPath("$.page.totalCount").value(1));

        mockMvc.perform(as(STAFF_ID, get("/v1/sub-works")))
                .andExpect(jsonPath("$.data", hasSize(2)));

        mockMvc.perform(as(STAFF_ID, get("/v1/sub-works/{id}", tagged.id())))
                .andExpect(jsonPath("$.data.operationId").value(tagged.operationId()))
                .andExpect(jsonPath("$.data.tags[*].tagNm", contains("학술국")));
    }

    // 회의 목록은 tagId로 거르고 행·상세마다 칩을 싣는다. 없는 태그 id는 빈 결과다
    @Test
    void meetingsCarryTagsAndFilterByThem() throws Exception {
        Long academic = createTag("학술국");
        Created tagged = createMeeting("학술국 회의");
        createMeeting("전체 회의");
        assign(tagged.operationId(), "[%d]".formatted(academic));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/meetings")).param("tagId", academic.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].meetingId").value(tagged.id()))
                .andExpect(jsonPath("$.data[0].tags[0].operationTagId").value(academic));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/meetings")))
                .andExpect(jsonPath("$.data", hasSize(2)));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/meetings/{id}", tagged.id())))
                .andExpect(jsonPath("$.data.tags[*].tagNm", contains("학술국")));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/meetings")).param("tagId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ------------------------------------------------------------------ 운영 통합

    /*
     * 운영 통합은 세 배열 모두 행마다 칩을 싣고, tagId로 세 배열을 같은 기준으로 거른다. 하위 업무
     * 행은 자기 태그대로 남는다 — 상위 업무가 걸러져도(태그 없음) 태그가 달린 하위 업무는 나온다.
     */
    @Test
    void operationHubFiltersEveryRowKindByTag() throws Exception {
        Long academic = createTag("학술국");
        Created work = createWork("정기 세미나");
        Created untaggedWork = createWork("축제 부스");
        Created subWork = createSubWork(untaggedWork.id(), "발표자 섭외");
        createSubWork(untaggedWork.id(), "장소 예약");
        Created meeting = createMeeting("학술국 회의");
        createMeeting("전체 회의");
        assign(work.operationId(), "[%d]".formatted(academic));
        assign(subWork.operationId(), "[%d]".formatted(academic));
        assign(meeting.operationId(), "[%d]".formatted(academic));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/operations")).param("tagId", academic.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.works", hasSize(1)))
                .andExpect(jsonPath("$.data.works[0].workId").value(work.id()))
                .andExpect(jsonPath("$.data.works[0].tags[0].tagNm").value("학술국"))
                .andExpect(jsonPath("$.data.subWorks", hasSize(1)))
                .andExpect(jsonPath("$.data.subWorks[0].subWorkId").value(subWork.id()))
                .andExpect(jsonPath("$.data.subWorks[0].tags[0].operationTagId").value(academic))
                .andExpect(jsonPath("$.data.meetings", hasSize(1)))
                .andExpect(jsonPath("$.data.meetings[0].meetingId").value(meeting.id()))
                .andExpect(jsonPath("$.data.meetings[0].tags[0].tagNm").value("학술국"));

        mockMvc.perform(as(DIRECTOR_ID, get("/v1/operations")))
                .andExpect(jsonPath("$.data.works", hasSize(2)))
                .andExpect(jsonPath("$.data.subWorks", hasSize(2)))
                .andExpect(jsonPath("$.data.meetings", hasSize(2)));
    }

    // ------------------------------------------------------------------ 도우미

    // 만든 건의 자기 id(workId·subWorkId·meetingId)와 태그를 다는 운영 건 id
    private record Created(Long id, Long operationId) {}

    private Long createTag(String name) throws Exception {
        String response =
                mockMvc.perform(as(DIRECTOR_ID, post("/v1/operation-tags")).content(tagBody(name)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.operationTagId", Long.class);
    }

    private Created createWork(String title) throws Exception {
        String body =
                """
                {"title": "%s", "itemType": "EVENT", "ownerId": %d}
                """
                        .formatted(title, ownerId);
        return created(post("/v1/works"), body, "$.data.workId");
    }

    private Created createSubWork(Long workId, String title) throws Exception {
        Long typeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
        String body =
                """
                {"workId": %d, "title": "%s", "subWorkTypeId": %d, "ownerId": %d,
                 "dueAt": "2099-01-01T23:59:00+09:00"}
                """
                        .formatted(workId, title, typeId, ownerId);
        return created(post("/v1/sub-works"), body, "$.data.subWorkId");
    }

    private Created createMeeting(String title) throws Exception {
        String body =
                """
                {"title": "%s", "meetingCategory": "REGULAR", "personInChargeId": %d,
                 "startAt": "2026-09-03T19:00:00+09:00"}
                """
                        .formatted(title, ownerId);
        return created(post("/v1/meetings"), body, "$.data.meetingId");
    }

    private Created created(MockHttpServletRequestBuilder request, String body, String idPath)
            throws Exception {
        String response =
                mockMvc.perform(as(DIRECTOR_ID, request).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new Created(
                JsonPath.parse(response).read(idPath, Long.class),
                JsonPath.parse(response).read("$.data.operationId", Long.class));
    }

    private org.springframework.test.web.servlet.ResultActions assign(Long operationId, String ids)
            throws Exception {
        return mockMvc.perform(
                        as(DIRECTOR_ID, put("/v1/operations/{id}/tags", operationId))
                                .content(tagIds(ids)))
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
