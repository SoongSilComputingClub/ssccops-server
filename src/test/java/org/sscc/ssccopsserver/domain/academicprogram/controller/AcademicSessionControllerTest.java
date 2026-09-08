package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 회차를 회차 id 하나로 읽는 경로 (#316 · GET /v1/academic-sessions/{sessionId}).
 *
 * 이 클래스가 확인하는 것은 **공유 착지가 막혔던 지점 하나**다 — 토큰이 들고 오는 것은 회차
 * id 하나인데 lms로 사람을 보내려면 활동 id가 필요했고, 그 값을 얻을 조회가 활동 id를 함께
 * 요구했다(ssccops#253). 그래서 여기서 못 박는 것은 셋이다: 활동 id 없이 읽히는가 · 응답에
 * 활동 id가 실리는가 · **익명에게 열려 있지 않은가**.
 *
 * 마지막 것에 토큰 없는 요청이 하나 있는 이유가 있다. 이 경로를 `/public/v1` 아래에 두지
 * 않기로 한 것이 이 이슈의 결정이고(컨트롤러 주석), 그 결정은 필터체인을 실제로 태워 봐야만
 * 확인된다 — 전부 토큰을 붙여 부르면 나중에 누가 접두사를 옮겨도 초록으로 남는다
 * (AcademicProgramShareControllerTest와 같은 이유).
 *
 * 중첩 경로(활동 문맥)의 규칙은 여기서 다시 보지 않는다 — 그쪽은
 * AcademicProgramSessionControllerTest가 갖고 있고, 이 클래스는 **두 경로가 같은 회차를 같은
 * 모양으로 내리는지**만 한 번 대조한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicSessionControllerTest {

    private static final String SESSIONS = "/v1/academic-sessions";
    private static final String PROGRAMS = "/v1/academic-programs";

    private static final String NOTICE = "다음 주까지 3장 예제 풀어 오기";
    private static final String CONTENT = "3회차 진행 내용";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private SessionRepository sessionRepository;

    private MemberEntity leader;
    private UUID leaderToken;
    private UUID otherToken;

    private AcademicProgramEntity academicProgram;
    private SessionEntity session;

    @BeforeEach
    void setUp() {
        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20260401", "스터디장");
        otherToken = UUID.randomUUID();
        saveMember(otherToken, "20260402", "다른회원");

        academicProgram = createAcademicProgram("알고리즘 스터디", "재귀와 동적계획법");
        session = createSession(academicProgram);
    }

    /*
     * 이 경로가 생긴 이유 그대로다 — 부르는 쪽이 아는 것은 회차 id 하나이고, 응답에서 활동
     * id를 얻는다. 그 한 값이 빠지면 착지 화면은 회차를 읽고도 갈 곳을 조립하지 못한다.
     */
    @Test
    void getAcademicSessionCarriesAcademicProgramId() throws Exception {
        mockMvc.perform(authorized(get(sessionPath(session)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(session.getId()))
                .andExpect(jsonPath("$.data.academicProgramId").value(academicProgram.getId()))
                .andExpect(jsonPath("$.data.curriculumItemId").isNumber())
                .andExpect(jsonPath("$.data.seqno").value(1))
                .andExpect(jsonPath("$.data.curriculumTtl").value("재귀와 동적계획법"))
                .andExpect(jsonPath("$.data.prgrsCn").value(CONTENT))
                .andExpect(jsonPath("$.data.ntcCn").value(NOTICE))
                .andExpect(jsonPath("$.data.sttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.rgtrMbrNm").value("스터디장"));
    }

    /*
     * 두 경로가 같은 회차를 같은 모양으로 내린다. 응답을 두 벌로 두면 같은 회차가 경로에 따라
     * 다르게 보이고, 특히 인증사진 자격 판정이 갈리는 자리가 생긴다 — 조립을 한 곳(detailOf)에
     * 두었다는 것을 문자열 대조로 못 박는다.
     */
    @Test
    void getAcademicSessionMatchesNestedRoute() throws Exception {
        String flat =
                mockMvc.perform(authorized(get(sessionPath(session)), leaderToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String nested =
                mockMvc.perform(
                                authorized(
                                        get(nestedSessionPath(academicProgram, session)),
                                        leaderToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(flat).isEqualTo(nested);
    }

    /*
     * 인증만 요구한다 — 중첩 경로의 회차 상세와 같은 판단이다. 링크를 받은 부원은 그 활동의
     * 스터디장도 팀원도 아닐 수 있고, 활동으로 좁히지 않는다고 해서 볼 수 있는 것이 늘지 않는다
     * (중첩 경로도 인증만으로 열려 있다).
     */
    @Test
    void getAcademicSessionAsOtherMemberReturns200() throws Exception {
        mockMvc.perform(authorized(get(sessionPath(session)), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramId").value(academicProgram.getId()))
                // 인증사진은 그 활동의 관계자에게만 실린다 — 좁히는 것은 여전히 사진 하나다
                .andExpect(jsonPath("$.data.fileReference").value(Matchers.nullValue()));
    }

    /*
     * **"삭제된 활동의 세션"은 성립하지 않는다.** acdm_actv에도 sesn에도 소프트 삭제가 없고
     * (활동 상세가 "존재하면 항상 조회된다"고 말하는 그 이유), crclm_artcl.acdm_actv_id가
     * NOT NULL이라 회차가 있으면 그 활동은 정의상 있다. 그래서 실제로 확인할 수 있는 것은
     * **끝난 활동과 검토 중인 회차를 상태로 감추지 않는가**이며, 그것이 감춰야 할 것이
     * 없다는 위 판단의 관찰 가능한 형태다.
     *
     * 감췄다면 공유 링크가 회차마다 살았다 죽었다 한다 — 뿌린 뒤에 상태가 바뀌는 것이 회차의
     * 일상이라(제출 → 수정요청 → 승인) 그 링크는 언제 열릴지 알 수 없는 링크가 된다.
     */
    @Test
    void getAcademicSessionOfCompletedProgramReturns200() throws Exception {
        changeProgramStatus(academicProgram, AcademicProgramStatus.COMPLETED);
        changeSessionStatus(session, SessionStatus.REVISION_REQUESTED);

        mockMvc.perform(authorized(get(sessionPath(session)), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramId").value(academicProgram.getId()))
                .andExpect(jsonPath("$.data.sttsCd").value("REVISION_REQUESTED"));
    }

    /*
     * 활동을 경로에서 받지 않으므로 "활동이 없다"와 "회차가 없다"를 가를 자리가 없다 —
     * 없는 회차는 중첩 경로와 같은 404 SESSION_NOT_FOUND 하나다.
     */
    @Test
    void getUnknownAcademicSessionReturns404() throws Exception {
        mockMvc.perform(authorized(get(SESSIONS + "/999999"), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    /*
     * 익명에게 열지 않았다는 것 — 이 이슈의 층 결정이다. 착지 화면에 필요한 미리보기는
     * /public/v1/share/{token}이 이미 주고, 이 경로가 내리는 것은 출석부까지 실린 상세다.
     */
    @Test
    void getAcademicSessionWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(sessionPath(session))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 헬퍼

    private SessionEntity createSession(AcademicProgramEntity program) {
        CurriculumItemEntity item =
                curriculumItemRepository
                        .findByAcademicProgramIdOrderBySeqnoAsc(program.getId())
                        .get(0);
        return sessionRepository.save(
                SessionEntity.submit(item, LocalDate.of(2026, 9, 15), CONTENT, NOTICE, leader));
    }

    /*
     * 벌크 UPDATE로 상태만 심고 영속성 컨텍스트를 비운다 — 전이 API(#133·#136)를 태우면 이
     * 클래스가 국장 권한 부여와 전이 규칙까지 지고 가게 되고, 그 규칙이 바뀔 때 조회 경로
     * 테스트가 함께 빨개진다(AcademicProgramSessionControllerTest와 같은 판단).
     */
    private void changeProgramStatus(AcademicProgramEntity program, AcademicProgramStatus status) {
        entityManager.flush();
        entityManager
                .createQuery(
                        "update AcademicProgramEntity a set a.status = :status where a.id = :id")
                .setParameter("status", status)
                .setParameter("id", program.getId())
                .executeUpdate();
        entityManager.clear();
    }

    private void changeSessionStatus(SessionEntity target, SessionStatus status) {
        entityManager.flush();
        entityManager
                .createQuery("update SessionEntity s set s.status = :status where s.id = :id")
                .setParameter("status", status)
                .setParameter("id", target.getId())
                .executeUpdate();
        entityManager.clear();
    }

    private String sessionPath(SessionEntity target) {
        return SESSIONS + "/" + target.getId();
    }

    private String nestedSessionPath(AcademicProgramEntity program, SessionEntity target) {
        return PROGRAMS + "/" + program.getId() + "/sessions/" + target.getId();
    }

    private AcademicProgramEntity createAcademicProgram(String title, String... curriculumTitles) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                "STUDY",
                title,
                leader,
                List.of(curriculumTitles));
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

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId);
    }
}
