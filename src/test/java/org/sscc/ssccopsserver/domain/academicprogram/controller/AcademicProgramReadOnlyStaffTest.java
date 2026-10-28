package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
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
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * **국원은 학술 화면을 전부 읽고, 아무것도 쓰지 못한다** (#694 · ssccops#614 · ADR-0068).
 *
 * 국원은 **시드된 그 역할을 실제로 받은 회원**이다(MemberRoleFixture.STAFF) — 권한을 붙인 새 역할로 보면
 * V34가 국원에게 ACADEMIC_PROGRAM_READ를 붙였는지를 못 본다. ADR-0067 사건이 그 자리에서 났다(시험의
 * 리더가 역할 없는 맨 회원이라 «리더 역할에 권한이 붙으면»을 아무도 두드리지 않았다).
 *
 * 표가 둘이다 — ReadPath(국원 200 · 다른 권한만 가진 회원 403)와 WritePath(국원 403). **학술에 조회
 * 경로나 쓰기 경로를 새로 만들면 여기에 한 줄을 더한다** — 조회가 …OrManager를 고르면 첫 표에서,
 * 쓰기가 …OrReader를 고르면 둘째 표에서 빨개진다. 인증만으로 열린 상세·커리큘럼(#118)은 표에 없다.
 *
 * 활동은 API로 만든다 — 미래 접수 창으로 모집 시작(활동 ONGOING · 폼 SCHEDULED라 모집 폼 isEditable이
 * 리더에게 참이다) → 회차 제출 둘(하나는 수정요청) → 리더 본인을 팀원으로 추가.
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다 — 서비스가 @Transactional이라 예외가 테스트
 * 트랜잭션을 rollback-only로 표시한다(domain/academicprogram/AGENTS.md). 애노테이션 구성은
 * AcademicProgramCompletionControllerTest와 같아 스프링 컨텍스트를 함께 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramReadOnlyStaffTest {

    private static final String PROGRAMS = "/v1/academic-programs";
    private static final String SESSIONS = "/v1/academic-sessions";
    private static final String REVISION_REASON = "출석을 다시 확인해 주세요";

    @Autowired private MockMvc mockMvc;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    private UUID managerToken;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID staffToken;
    private UUID outsiderToken;

    private AcademicProgramEntity program;

    /** 수정요청을 받은 회차 — 상세에 latestOpinion이 실린다 */
    private Long sessionUnderRevision;

    /** 검토를 기다리는 회차 — 승인 대기 목록에 오른다 */
    private Long sessionAwaitingReview;

    /** 실적이 아직 없는 계획 — 회차 제출이 통과할 자리다 */
    private Long vacantCurriculumItemId;

    @BeforeEach
    void setUp() throws Exception {
        managerToken = UUID.randomUUID();
        grant(saveMember(managerToken, "20261101", "학술국장"), AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20261102", "스터디장");

        staffToken = UUID.randomUUID();
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                saveMember(staffToken, "20261103", "국원"),
                MemberRoleFixture.STAFF);

        // 권한이 아예 없는 것이 아니라 '다른 권한만' 가져야 인증만으로는 통과하지 못한다는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20261104", "다른 운영진"), AuthorityCode.WORK_MANAGE);

        program =
                AcademicProgramFixture.save(
                        eventRepository,
                        eventClassificationRepository,
                        academicProgramRepository,
                        academicProgramTypeRepository,
                        curriculumItemRepository,
                        formRepository,
                        formResponseHistoryRepository,
                        "STUDY",
                        "국원이 읽는 스터디",
                        leader,
                        List.of("OT", "1주차", "2주차"));
        linkForm(program);
        startRecruitment(laterBy(7), laterBy(14));

        List<CurriculumItemEntity> items =
                curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(program.getId());
        sessionUnderRevision = submitSession(items.get(0).getId(), "2026-09-05");
        requestRevision(sessionUnderRevision);
        sessionAwaitingReview = submitSession(items.get(1).getId(), "2026-09-12");
        vacantCurriculumItemId = items.get(2).getId();

        mockMvc.perform(
                        authorized(post(programPath() + "/members"), leaderToken)
                                .content("{\"mbrId\": %d}".formatted(leader.getId())))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 권한

    // 세션이 그리는 값이다 — 어드민 학술 메뉴(nav.ts)가 이 목록의 READ를 보고 열린다
    @Test
    void staffRoleCarriesReadButNotManage() throws Exception {
        mockMvc.perform(authorized(get("/v1/auth/session"), staffToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data.member.capabilities",
                                Matchers.hasItem(AuthorityCode.ACADEMIC_PROGRAM_READ.code())))
                .andExpect(
                        jsonPath(
                                "$.data.member.capabilities",
                                Matchers.not(
                                        Matchers.hasItem(
                                                AuthorityCode.ACADEMIC_PROGRAM_MANAGE.code()))));
    }

    // 학술국장은 아무것도 받지 않고 READ를 갖는다 — MANAGE의 자식이라 펼침으로 따라온다
    @Test
    void managerHoldsReadThroughTheTree() throws Exception {
        mockMvc.perform(authorized(get("/v1/auth/session"), managerToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data.member.capabilities",
                                Matchers.hasItems(
                                        AuthorityCode.ACADEMIC_PROGRAM_MANAGE.code(),
                                        AuthorityCode.ACADEMIC_PROGRAM_READ.code())));
    }

    // ------------------------------------------------------------------ 조회

    @ParameterizedTest
    @EnumSource(ReadPath.class)
    void staffReadsEveryAcademicView(ReadPath path) throws Exception {
        mockMvc.perform(authorized(path.request(this), staffToken)).andExpect(status().isOk());
    }

    // 조회가 그냥 열린 것이 아니다 — READ가 없으면 같은 경로가 403이다
    @ParameterizedTest
    @EnumSource(ReadPath.class)
    void readPathsStayClosedWithoutRead(ReadPath path) throws Exception {
        mockMvc.perform(authorized(path.request(this), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 수정요청 사유는 리더·학술국장·국원이 본다 — 승인 이력 API가 같은 의견을 내주므로 갈리지 않는다
    @Test
    void staffSeesTheRevisionReason() throws Exception {
        mockMvc.perform(authorized(get(sessionPath(sessionUnderRevision)), staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestOpinion").value(REVISION_REASON));
        mockMvc.perform(authorized(get(SESSIONS + "/" + sessionUnderRevision), staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestOpinion").value(REVISION_REASON));
    }

    // ------------------------------------------------------------------ 버튼

    /*
     * «버튼을 켤까»는 읽기 자격이 아니라 쓰기 자격을 본다. 리더와 나란히 본다 — 같은 화면이 리더에게
     * 켜져 있어야 국원에게 꺼진 것이 «상태 때문»이 아니라 «자격 때문»이 된다.
     *
     * 모집 폼이 요점이다: 그전에는 조회 자격과 쓰기 자격이 같아 isEditable이 창·활동 상태만 봤고,
     * 국원에게 조회가 열리며 «고칠 수 있음»이 그대로 나갈 뻔했다.
     */
    @Test
    void editabilityFollowsWriteQualificationNotRead() throws Exception {
        mockMvc.perform(authorized(get(programPath() + "/recruitment/form"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isEditable").value(true));
        mockMvc.perform(authorized(get(programPath() + "/recruitment/form"), staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isEditable").value(false));

        mockMvc.perform(authorized(get(programPath() + "/members"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].isEditable").value(true));
        mockMvc.perform(authorized(get(programPath() + "/members"), staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].isEditable").value(false));

        mockMvc.perform(authorized(get(programPath() + "/curriculum-items"), staffToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].isEditable", Matchers.everyItem(Matchers.is(false))));
    }

    // ------------------------------------------------------------------ 쓰기

    @ParameterizedTest
    @EnumSource(WritePath.class)
    void staffWritesNothing(WritePath path) throws Exception {
        mockMvc.perform(authorized(path.request(this), staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 회차 공유 발급은 READ로도 된다 — «볼 수 있는 사람이 공유한다»(관계자 판정)를 그대로 따르고, 토큰은
     * 미리보기까지라 내용을 바꾸지 않는다(ADR-0016 · ADR-0068). 중지는 WritePath에 있다.
     */
    @Test
    void staffMayIssueSessionShareLink() throws Exception {
        mockMvc.perform(
                        authorized(
                                post(SESSIONS + "/" + sessionAwaitingReview + "/share"),
                                staffToken))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 표

    /*
     * 국원이 읽는 학술 조회 전부(어드민 학술 일곱 화면과 그 상세가 부르는 것). 인증만으로 열린
     * 상세·커리큘럼(#118)은 «READ가 없으면 403»이 성립하지 않아 넣지 않는다.
     */
    private enum ReadPath {
        ALL_PROGRAMS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(PROGRAMS);
            }
        },
        CROSS_SESSIONS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(PROGRAMS + "/sessions");
            }
        },
        PENDING_SESSIONS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(PROGRAMS + "/reviews/sessions");
            }
        },
        PROGRAM_SESSIONS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.programPath() + "/sessions");
            }
        },
        SESSION_DETAIL {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.sessionPath(test.sessionAwaitingReview));
            }
        },
        SESSION_BY_ID {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(SESSIONS + "/" + test.sessionAwaitingReview);
            }
        },
        ATTENDANCES {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.sessionPath(test.sessionAwaitingReview) + "/attendances");
            }
        },
        MEMBERS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.programPath() + "/members");
            }
        },
        MEMBER_HISTORY {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.programPath() + "/members/history");
            }
        },
        APPROVALS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.programPath() + "/approvals");
            }
        },
        APPLICATIONS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.programPath() + "/recruitment/applications");
            }
        },
        RECRUITMENT_FORM {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.programPath() + "/recruitment/form");
            }
        },
        RECRUITMENT_SCHEDULE {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return get(test.programPath() + "/recruitment/schedule");
            }
        };

        abstract MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test);
    }

    /*
     * 학술 쓰기 전부. 경로마다 «자격이 있으면 통과할 요청»을 보낸다 — 본문이 틀려 400이 먼저 나면
     * 자격을 본 것이 아니다(@Valid가 애스펙트보다 먼저 돈다 · RequireAuthorityAspect 주석).
     */
    private enum WritePath {
        TRANSITION_PROGRAM {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return post(test.programPath() + "/transitions")
                        .content("{\"transition\": \"APPROVE_COMPLETION\"}");
            }
        },
        TRANSITION_SESSION {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return post(test.sessionPath(test.sessionAwaitingReview) + "/transitions")
                        .content("{\"transition\": \"APPROVE\"}");
            }
        },
        SUBMIT_SESSION {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return post(test.programPath() + "/sessions")
                        .content(submitBody(test.vacantCurriculumItemId, "2026-09-19"));
            }
        },
        CORRECT_ATTENDANCES {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return patch(test.sessionPath(test.sessionAwaitingReview) + "/attendances")
                        .content("{\"attendances\": [{\"eventPtcpId\": 1, \"atndYn\": true}]}");
            }
        },
        SELECT_MEMBERS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return post(test.programPath() + "/recruitment/select")
                        .content(
                                "{\"selections\": [{\"formRspnsId\": 1, \"ptcpSttsCd\":"
                                        + " \"CONFIRMED\"}]}");
            }
        },
        UPDATE_RECRUITMENT_SCHEDULE {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return patch(test.programPath() + "/recruitment/schedule")
                        .content(
                                "{\"rcptBgngDt\": \"%s\", \"rcptEndDt\": \"%s\"}"
                                        .formatted(laterBy(1), laterBy(30)));
            }
        },
        UPDATE_EVENT_POST {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                // 스터디장의 행사 게시물 수정(ssccops#618 · ADR-0069) — 리더 본인만이다
                return put(test.programPath() + "/event-post")
                        .content(
                                "{\"eventTtl\": \"고친 제목\", \"mtxtCn\": \"본문\","
                                        + " \"thmbUrlAddr\": null, \"plcNm\": null}");
            }
        },
        ISSUE_EVENT_POST_IMAGE {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return post(test.programPath() + "/event-post/images")
                        .content("{\"fileExt\": \"png\", \"fileSize\": 1024}");
            }
        },
        UPDATE_RECRUITMENT_FORM {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return put(test.programPath() + "/recruitment/form")
                        .content(
                                "{\"qitemCpstCn\": {\"pages\": [{\"pageTtl\": \"기본 정보\"}],"
                                        + " \"qitems\": [{\"qitemId\": \"q1\", \"qitemLblNm\":"
                                        + " \"지원 동기\", \"qitemTypeCd\": \"LONG_TEXT\","
                                        + " \"reqYn\": true, \"pageSeq\": 0}]}}");
            }
        },
        ADD_MEMBER {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return post(test.programPath() + "/members")
                        .content("{\"mbrId\": %d}".formatted(test.leader.getId()));
            }
        },
        CHANGE_MEMBER_STATUS {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return patch(test.programPath() + "/members/1")
                        .content("{\"ptcpSttsCd\": \"CANCELLED\"}");
            }
        },
        REVOKE_PROGRAM_SHARE {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return delete(test.programPath() + "/share");
            }
        },
        REVOKE_SESSION_SHARE {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return delete(SESSIONS + "/" + test.sessionAwaitingReview + "/share");
            }
        },
        CREATE_PROGRAM_TYPE {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test) {
                return post("/v1/academic-program-types")
                        .content(
                                "{\"typeCd\": \"SEMINAR\", \"typeNm\": \"세미나\", \"indctSeqno\":"
                                        + " 3}");
            }
        };

        abstract MockHttpServletRequestBuilder request(AcademicProgramReadOnlyStaffTest test);
    }

    // ------------------------------------------------------------------ 헬퍼

    private String programPath() {
        return PROGRAMS + "/" + program.getId();
    }

    private String sessionPath(Long sessionId) {
        return programPath() + "/sessions/" + sessionId;
    }

    private static String submitBody(Long curriculumItemId, String realDate) {
        return """
               {"curriculumItemId": %d, "actlYmd": "%s", "prgrsCn": "진행 내용", "attendances": []}
               """
                .formatted(curriculumItemId, realDate);
    }

    private Long submitSession(Long curriculumItemId, String realDate) throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(programPath() + "/sessions"), leaderToken)
                                        .content(submitBody(curriculumItemId, realDate)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.sessionId", Long.class);
    }

    private void requestRevision(Long sessionId) throws Exception {
        mockMvc.perform(
                        authorized(post(sessionPath(sessionId) + "/transitions"), managerToken)
                                .content(
                                        """
                                        {"transition": "REQUEST_REVISION", "reason": "%s"}
                                        """
                                                .formatted(REVISION_REASON)))
                .andExpect(status().isOk());
    }

    // 미래 접수 창 — 활동은 ONGOING이 되고 폼은 SCHEDULED라 모집 폼 문항을 아직 고칠 수 있다
    private void startRecruitment(String beginAt, String endAt) throws Exception {
        mockMvc.perform(
                        authorized(post(programPath() + "/transitions"), managerToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT",
                                         "recruitmentStartDt": "%s",
                                         "recruitmentEndDt": "%s"}
                                        """
                                                .formatted(beginAt, endAt)))
                .andExpect(status().isOk());
    }

    /*
     * 고정 Clock을 두지 않고 지금을 기준으로 잡는다 — @MockitoBean Clock은 스프링 컨텍스트를
     * 하나 더 만든다(AcademicProgramCompletionControllerTest와 같은 이유).
     */
    private static String laterBy(int days) {
        return OffsetDateTime.now(ZoneOffset.ofHours(9))
                .plusDays(days)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    // 문항이 하나 있는 DRAFT 모집 폼 — 승인 후속 처리가 만드는 빈 폼으로는 모집을 시작할 수 없다
    private void linkForm(AcademicProgramEntity target) {
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                leader,
                                "모집 폼",
                                new QuestionCompositionContent(
                                        List.of(new Page("기본 정보", null)),
                                        List.of(
                                                new QuestionItem(
                                                        "q1",
                                                        "지원 동기",
                                                        QuestionItemType.LONG_TEXT,
                                                        true,
                                                        0,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null))),
                                null,
                                null,
                                FormStatus.DRAFT));

        EventEntity event = target.getEvent();
        event.linkForm(form);
        eventRepository.saveAndFlush(event);
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org");
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
