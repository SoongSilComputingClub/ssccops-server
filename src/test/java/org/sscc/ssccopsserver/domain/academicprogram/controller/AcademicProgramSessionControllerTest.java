package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AttendanceRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 회차 기록 작성·조회 API (#135). 쓰기 둘은 스터디장/팀장 본인만, 조회 둘은 인증만 요구한다.
 *
 * REVISION_REQUESTED·APPROVED는 이제 회차 승인 API(#136)로 만들 수 있지만, 재제출 테스트는
 * 여전히 그 상태를 벌크 UPDATE로 심는다(revertToRevisionRequested) — 이 클래스가 확인하는 것은
 * 재제출 규칙이고, 픽스처가 국장 권한 부여와 승인 전이까지 지고 가면 그 규칙과 무관한 실패가
 * 이 테스트로 흘러든다. 승인 전이 뒤의 회차가 실제로 어떤 상태인지는 #136의
 * AcademicProgramReviewControllerTest가 API로 확인한다.
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다 — 서비스가 @Transactional이라
 * 예외가 테스트 트랜잭션을 rollback-only로 표시하고, 그 뒤 이어지는 요청은
 * UnexpectedRollbackException을 만난다(AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramSessionControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AcademicProgramSessionControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private AttendanceRepository attendanceRepository;

    private UUID leaderToken;
    private MemberEntity leader;
    private UUID otherToken;

    private AcademicProgramEntity academicProgram;
    private CurriculumItemEntity firstItem;
    private CurriculumItemEntity secondItem;
    private EventParticipantEntity confirmedMember;

    @BeforeEach
    void setUp() {
        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20260401", "스터디장");
        otherToken = UUID.randomUUID();
        saveMember(otherToken, "20260402", "다른회원");

        academicProgram = createAcademicProgram("알고리즘 스터디", "OT", "1주차");
        List<CurriculumItemEntity> items = curriculumItems(academicProgram);
        firstItem = items.get(0);
        secondItem = items.get(1);

        confirmedMember =
                confirmParticipant(
                        academicProgram, saveMember(UUID.randomUUID(), "20260403", "팀원"));
    }

    // ------------------------------------------------------------------ 제출 (POST)

    @Test
    void submitSessionReturns201WithDetail() throws Exception {
        mockMvc.perform(
                        authorized(sessions(academicProgram), leaderToken)
                                .content(submitBody(firstItem, "2026-09-05", "1회차 진행 내용", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").isNumber())
                .andExpect(jsonPath("$.data.curriculumItemId").value(firstItem.getId()))
                .andExpect(jsonPath("$.data.seqno").value(1))
                .andExpect(jsonPath("$.data.curriculumTtl").value("OT"))
                .andExpect(jsonPath("$.data.realDt").value("2026-09-05"))
                .andExpect(jsonPath("$.data.cn").value("1회차 진행 내용"))
                .andExpect(jsonPath("$.data.noticeCn").value("다음 주 준비물"))
                // 제출과 동시에 국장 검토 대기다 — NOT_SUBMITTED는 행이 없는 상태를 가리키는 파생 값이다
                .andExpect(jsonPath("$.data.sttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.rgtrMbrId").value(leader.getId()))
                .andExpect(jsonPath("$.data.rgtrMbrNm").value("스터디장"))
                .andExpect(jsonPath("$.data.attendances", Matchers.hasSize(1)))
                .andExpect(
                        jsonPath("$.data.attendances[0].eventPtcpId")
                                .value(confirmedMember.getId()))
                .andExpect(jsonPath("$.data.attendances[0].mbrNm").value("팀원"))
                .andExpect(jsonPath("$.data.attendances[0].presentYn").value(true))
                .andExpect(jsonPath("$.data.presentCount").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                // 인증사진(#137)·검토 의견(#136)은 아직 채우는 쪽이 없어 늘 비어 있다
                .andExpect(jsonPath("$.data.fileReference").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.latestOpinion").value(Matchers.nullValue()));
    }

    // 결석도 출석부의 한 줄이다 — 명단에는 있고 presentCount에만 빠진다
    @Test
    void submitSessionCountsAbsenceInTotalOnly() throws Exception {
        mockMvc.perform(
                        authorized(sessions(academicProgram), leaderToken)
                                .content(submitBody(firstItem, "2026-09-05", "결석자 있는 회차", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attendances[0].presentYn").value(false))
                .andExpect(jsonPath("$.data.presentCount").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(1));
    }

    // 확정 팀원이 없는 활동의 첫 회차 — 빈 명단이 "전원 출석 여부"의 정상적인 답이다
    @Test
    void submitSessionWithEmptyAttendancesReturns201() throws Exception {
        AcademicProgramEntity empty = createAcademicProgram("팀원 없는 스터디", "OT");
        CurriculumItemEntity item = curriculumItems(empty).get(0);

        mockMvc.perform(
                        authorized(sessions(empty), leaderToken)
                                .content(
                                        """
                                        {"curriculumItemId": %d, "realDt": "2026-09-05",
                                         "cn": "OT", "attendances": []}
                                        """
                                                .formatted(item.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attendances").isEmpty())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    // 계획 1개당 실적은 최대 1개다 (uk_session_curriculum_item)
    @Test
    void submitSessionTwiceOnSameCurriculumItemReturns409() throws Exception {
        String body = submitBody(firstItem, "2026-09-05", "1회차", true);

        mockMvc.perform(authorized(sessions(academicProgram), leaderToken).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(authorized(sessions(academicProgram), leaderToken).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ALREADY_EXISTS"));
    }

    // 다른 활동의 커리큘럼에 실적을 매달 수 없다 — 질의가 활동으로 좁혀 찾으므로 없는 것과 같은 404다
    @Test
    void submitSessionWithCurriculumItemOfAnotherProgramReturns404() throws Exception {
        AcademicProgramEntity another = createAcademicProgram("남의 스터디", "남의 OT");
        CurriculumItemEntity foreignItem = curriculumItems(another).get(0);

        mockMvc.perform(
                        authorized(sessions(academicProgram), leaderToken)
                                .content(submitBody(foreignItem, "2026-09-05", "1회차", true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CURRICULUM_ITEM_NOT_FOUND"));
    }

    // 대기자는 아직 팀원이 아니다 — 출석부에 존재할 수 없다(설계 결정 #3)
    @Test
    void submitSessionWithWaitlistedParticipantReturns400() throws Exception {
        EventParticipantEntity waitlisted =
                eventParticipantRepository.save(
                        EventParticipantEntity.register(
                                academicProgram.getEvent(),
                                saveMember(UUID.randomUUID(), "20260404", "대기자"),
                                EventParticipantStatus.WAITLISTED,
                                null,
                                leader));

        mockMvc.perform(
                        authorized(sessions(academicProgram), leaderToken)
                                .content(
                                        submitBody(
                                                firstItem, "2026-09-05", "1회차", waitlisted, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTENDANCE_TARGET"));
    }

    // 다른 활동의 확정 팀원도 이 활동의 출석 대상이 아니다
    @Test
    void submitSessionWithParticipantOfAnotherProgramReturns400() throws Exception {
        AcademicProgramEntity another = createAcademicProgram("남의 스터디", "남의 OT");
        EventParticipantEntity foreign =
                confirmParticipant(another, saveMember(UUID.randomUUID(), "20260405", "남의 팀원"));

        mockMvc.perform(
                        authorized(sessions(academicProgram), leaderToken)
                                .content(submitBody(firstItem, "2026-09-05", "1회차", foreign, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTENDANCE_TARGET"));
    }

    /*
     * 같은 참가자가 두 줄로 실려 오면 400이다. 폼 라벨 교체처럼 한 번으로 접지 않는 것은 출석이
     * 값이 딸린 체크라 두 줄이 서로 다른 답을 실을 수 있기 때문이다.
     */
    @Test
    void submitSessionWithDuplicatedParticipantReturns400() throws Exception {
        mockMvc.perform(
                        authorized(sessions(academicProgram), leaderToken)
                                .content(
                                        """
                                        {"curriculumItemId": %d, "realDt": "2026-09-05", "cn": "1회차",
                                         "attendances": [{"eventPtcpId": %d, "presentYn": true},
                                                         {"eventPtcpId": %d, "presentYn": false}]}
                                        """
                                                .formatted(
                                                        firstItem.getId(),
                                                        confirmedMember.getId(),
                                                        confirmedMember.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTENDANCE_TARGET"));
    }

    // 스터디장/팀장 본인이 아니면 403이다 — 정적 권한이 아니라 이 활동 한정 소유권 판정이다
    @Test
    void submitSessionAsOtherMemberReturns403() throws Exception {
        mockMvc.perform(
                        authorized(sessions(academicProgram), otherToken)
                                .content(submitBody(firstItem, "2026-09-05", "1회차", true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void submitSessionWithoutRequiredFieldsReturns400() throws Exception {
        mockMvc.perform(
                        authorized(sessions(academicProgram), leaderToken)
                                .content(
                                        """
                                        {"curriculumItemId": %d, "cn": "", "attendances": []}
                                        """
                                                .formatted(firstItem.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitSessionWithoutTokenReturns401() throws Exception {
        mockMvc.perform(sessions(academicProgram).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 재제출 (PUT)

    /*
     * 재제출은 전체 교체다 — 진행 내용·전달사항·출석이 통째로 이번 요청의 값으로 바뀌고, 이전
     * 제출의 흔적은 어디에도 남지 않는다(데이터모델 §7). 상태는 다시 SUBMITTED로 돌아간다.
     */
    @Test
    void resubmitSessionReplacesEverything() throws Exception {
        EventParticipantEntity replacement =
                confirmParticipant(
                        academicProgram, saveMember(UUID.randomUUID(), "20260406", "새 팀원"));
        Long sessionId = submitSession(firstItem, "2026-09-05", "처음 낸 내용", confirmedMember, true);
        revertToRevisionRequested(sessionId);

        mockMvc.perform(
                        authorized(put(sessionPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        """
                                        {"curriculumItemId": %d, "realDt": "2026-09-12",
                                         "cn": "고쳐 낸 내용", "noticeCn": null,
                                         "attendances": [{"eventPtcpId": %d, "presentYn": false}]}
                                        """
                                                .formatted(firstItem.getId(), replacement.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.realDt").value("2026-09-12"))
                .andExpect(jsonPath("$.data.cn").value("고쳐 낸 내용"))
                .andExpect(jsonPath("$.data.noticeCn").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.sttsCd").value("SUBMITTED"))
                // 이전 출석 줄은 남지 않는다 — 새 명단이 통째로 자리를 대신한다
                .andExpect(jsonPath("$.data.attendances", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.attendances[0].eventPtcpId").value(replacement.getId()))
                .andExpect(jsonPath("$.data.presentCount").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(1));

        // 이력을 남기지 않는다 — session도 attendance도 행이 늘지 않는다
        entityManager.flush();
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(attendanceRepository.count()).isEqualTo(1);
    }

    // 남는 참가자의 출석 행은 지웠다 새로 만들지 않고 체크 값만 갈아 끼운다
    @Test
    void resubmitSessionKeepsAttendanceRowOfRemainingParticipant() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "처음", confirmedMember, true);
        Long attendanceId =
                attendanceRepository.findAll().stream().findFirst().orElseThrow().getId();
        revertToRevisionRequested(sessionId);

        mockMvc.perform(
                        authorized(put(sessionPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        submitBody(
                                                firstItem,
                                                "2026-09-05",
                                                "다시",
                                                confirmedMember,
                                                false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attendances[0].presentYn").value(false));

        entityManager.flush();
        assertThat(attendanceRepository.findAll())
                .singleElement()
                .satisfies(
                        attendance -> {
                            assertThat(attendance.getId()).isEqualTo(attendanceId);
                            assertThat(attendance.isPresent()).isFalse();
                        });
    }

    // 회차를 잘못 골라 기록한 것은 재제출에서 바로잡을 수 있다 (전체 교체)
    @Test
    void resubmitSessionCanMoveToVacantCurriculumItem() throws Exception {
        Long sessionId =
                submitSession(firstItem, "2026-09-05", "1회차인 줄 알았던 기록", confirmedMember, true);
        revertToRevisionRequested(sessionId);

        mockMvc.perform(
                        authorized(put(sessionPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        submitBody(
                                                secondItem,
                                                "2026-09-12",
                                                "사실 2회차",
                                                confirmedMember,
                                                true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.curriculumItemId").value(secondItem.getId()))
                .andExpect(jsonPath("$.data.seqno").value(2))
                .andExpect(jsonPath("$.data.curriculumTtl").value("1주차"));
    }

    // 옮겨 갈 자리에 이미 실적이 있으면 신규 제출과 같은 409다
    @Test
    void resubmitSessionOntoOccupiedCurriculumItemReturns409() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        submitSession(secondItem, "2026-09-12", "2회차", confirmedMember, true);
        revertToRevisionRequested(sessionId);

        mockMvc.perform(
                        authorized(put(sessionPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        submitBody(
                                                secondItem,
                                                "2026-09-12",
                                                "겹치는 기록",
                                                confirmedMember,
                                                true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ALREADY_EXISTS"));
    }

    // 국장 검토 대기(SUBMITTED)는 손대지 않는다 — 재제출은 REVISION_REQUESTED 전용이다
    @Test
    void resubmitSubmittedSessionReturns409() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);

        mockMvc.perform(
                        authorized(put(sessionPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        submitBody(
                                                firstItem,
                                                "2026-09-05",
                                                "고쳐보기",
                                                confirmedMember,
                                                true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_EDITABLE"));
    }

    // 승인된 회차는 확정 이력이라 되돌리지 않는다
    @Test
    void resubmitApprovedSessionReturns409() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        changeStatus(sessionId, SessionStatus.APPROVED);

        mockMvc.perform(
                        authorized(put(sessionPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        submitBody(
                                                firstItem,
                                                "2026-09-05",
                                                "고쳐보기",
                                                confirmedMember,
                                                true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_EDITABLE"));
    }

    @Test
    void resubmitSessionAsOtherMemberReturns403() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        revertToRevisionRequested(sessionId);

        mockMvc.perform(
                        authorized(put(sessionPath(academicProgram, sessionId)), otherToken)
                                .content(
                                        submitBody(
                                                firstItem,
                                                "2026-09-05",
                                                "남의 기록 고치기",
                                                confirmedMember,
                                                true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 다른 활동 경로로 부르면 404다 — 회차 식별자만 보면 남의 활동 기록을 고칠 수 있다
    @Test
    void resubmitSessionThroughAnotherProgramReturns404() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        revertToRevisionRequested(sessionId);
        AcademicProgramEntity another = createAcademicProgram("남의 스터디", "남의 OT");
        CurriculumItemEntity foreignItem = curriculumItems(another).get(0);

        mockMvc.perform(
                        authorized(put(sessionPath(another, sessionId)), leaderToken)
                                .content(
                                        submitBody(
                                                foreignItem,
                                                "2026-09-05",
                                                "엉뚱한 기록",
                                                confirmedMember,
                                                true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 상세 조회 (GET)

    // 조회는 인증만 요구한다 — 팀원도 일반 회원도 자기 활동의 회차를 본다
    @Test
    void getSessionAsOtherMemberReturns200() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차 내용", confirmedMember, true);

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.cn").value("1회차 내용"))
                .andExpect(jsonPath("$.data.sttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.attendances", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.presentCount").value(1));
    }

    @Test
    void getSessionThroughAnotherProgramReturns404() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        AcademicProgramEntity another = createAcademicProgram("남의 스터디", "남의 OT");

        mockMvc.perform(authorized(get(sessionPath(another, sessionId)), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    // 활동 자체가 없으면 회차 404가 아니라 활동 404다 — 화면이 둘을 구별해야 한다
    @Test
    void getSessionOfUnknownProgramReturns404() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS + "/999999/sessions/1"), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    @Test
    void getSessionWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(PROGRAMS + "/1/sessions/1")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 목록 조회 (GET)

    @Test
    void searchSessionsReturnsListEnvelopeOrderedBySeqno() throws Exception {
        submitSession(secondItem, "2026-09-12", "2회차", confirmedMember, false);
        submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);

        mockMvc.perform(authorized(get(sessionsPath(academicProgram)), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                // 등록 순서가 아니라 회차 번호가 줄 순서다
                .andExpect(jsonPath("$.data[0].seqno").value(1))
                .andExpect(jsonPath("$.data[0].curriculumTtl").value("OT"))
                .andExpect(jsonPath("$.data[0].realDt").value("2026-09-05"))
                .andExpect(jsonPath("$.data[0].sttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[0].rgtrMbrNm").value("스터디장"))
                .andExpect(jsonPath("$.data[0].presentCount").value(1))
                .andExpect(jsonPath("$.data[0].totalCount").value(1))
                .andExpect(jsonPath("$.data[1].seqno").value(2))
                .andExpect(jsonPath("$.data[1].presentCount").value(0))
                .andExpect(jsonPath("$.data[1].totalCount").value(1))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.sort").value("seqno"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.totalCount").value(2))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    // 다른 활동의 회차는 섞이지 않는다 — 질의가 애초에 경로의 활동 것만 읽는다
    @Test
    void searchSessionsExcludesOtherProgramsSessions() throws Exception {
        submitSession(firstItem, "2026-09-05", "내 1회차", confirmedMember, true);
        AcademicProgramEntity another = createAcademicProgram("남의 스터디", "남의 OT");
        submitSession(
                another, curriculumItems(another).get(0), "2026-09-05", "남의 1회차", null, false);

        mockMvc.perform(authorized(get(sessionsPath(academicProgram)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].curriculumTtl").value("OT"));
    }

    @Test
    void searchSessionsWithStatusFilterNarrowsResult() throws Exception {
        Long approved = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        submitSession(secondItem, "2026-09-12", "2회차", confirmedMember, true);
        changeStatus(approved, SessionStatus.APPROVED);

        mockMvc.perform(
                        authorized(get(sessionsPath(academicProgram)), leaderToken)
                                .param("sttsCd", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].sessionId").value(approved))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                // overallCount는 필터와 무관한 활동 전체의 회차 수다
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    /*
     * NOT_SUBMITTED는 session 행이 없다는 사실을 가리키는 파생 값이라 이 목록에서는 언제나 빈
     * 결과다 — 400으로 거절하지는 않는다(어휘에 있는 값이고 답이 거짓이 아니다).
     */
    @Test
    void searchSessionsWithNotSubmittedFilterReturnsEmptyArray() throws Exception {
        submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);

        mockMvc.perform(
                        authorized(get(sessionsPath(academicProgram)), leaderToken)
                                .param("sttsCd", "NOT_SUBMITTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void searchSessionsPaginatesWithCursor() throws Exception {
        submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        submitSession(secondItem, "2026-09-12", "2회차", confirmedMember, true);

        String firstPage =
                mockMvc.perform(
                                authorized(get(sessionsPath(academicProgram)), leaderToken)
                                        .param("size", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                        .andExpect(jsonPath("$.data[0].seqno").value(1))
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(firstPage).read("$.page.nextCursor", String.class);

        mockMvc.perform(
                        authorized(get(sessionsPath(academicProgram)), leaderToken)
                                .param("size", "1")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].seqno").value(2))
                .andExpect(jsonPath("$.page.hasNext").value(false));
    }

    // 진행일 내림차순 — 최근에 열린 회차부터 본다
    @Test
    void searchSessionsSortsByRealDateDescending() throws Exception {
        submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        submitSession(secondItem, "2026-09-12", "2회차", confirmedMember, true);

        mockMvc.perform(
                        authorized(get(sessionsPath(academicProgram)), leaderToken)
                                .param("sort", "-realDt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].realDt").value("2026-09-12"))
                .andExpect(jsonPath("$.data[1].realDt").value("2026-09-05"))
                .andExpect(jsonPath("$.page.sort").value("-realDt"));
    }

    @Test
    void searchSessionsWithUnknownSortReturns400() throws Exception {
        mockMvc.perform(
                        authorized(get(sessionsPath(academicProgram)), leaderToken)
                                .param("sort", "무효"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void searchSessionsWithMalformedCursorReturns400() throws Exception {
        mockMvc.perform(
                        authorized(get(sessionsPath(academicProgram)), leaderToken)
                                .param("cursor", "!!not-a-cursor!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchSessionsOfProgramWithoutSessionsReturnsEmptyArray() throws Exception {
        mockMvc.perform(authorized(get(sessionsPath(academicProgram)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalCount").value(0));
    }

    @Test
    void searchSessionsOfUnknownProgramReturns404() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS + "/999999/sessions"), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 계획 조회와의 연결 (#134)

    /*
     * 실적이 생기면 계획 조회의 그 줄이 함께 바뀐다 — 제출된 회차는 NOT_SUBMITTED가 아니고,
     * 스터디장 본인이어도 SUBMITTED인 회차의 편집 버튼은 꺼진다(SessionStatus.allowsRecording).
     */
    @Test
    void curriculumItemsCarrySubmittedSession() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차 진행 내용", confirmedMember, true);

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/" + academicProgram.getId() + "/curriculum-items"),
                                leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$.data[0].sessionSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[0].realDt").value("2026-09-05"))
                .andExpect(jsonPath("$.data[0].cn").value("1회차 진행 내용"))
                .andExpect(jsonPath("$.data[0].isEditable").value(false))
                // 실적이 없는 줄은 그대로 NOT_SUBMITTED이고 스터디장에게는 편집 가능하다
                .andExpect(jsonPath("$.data[1].sessionId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[1].sessionSttsCd").value("NOT_SUBMITTED"))
                .andExpect(jsonPath("$.data[1].isEditable").value(true));
    }

    // 수정요청 상태의 회차는 다시 쓸 수 있다 — 편집 버튼이 켜진다
    @Test
    void curriculumItemsMarkRevisionRequestedSessionEditable() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05", "1회차", confirmedMember, true);
        revertToRevisionRequested(sessionId);

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/" + academicProgram.getId() + "/curriculum-items"),
                                leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sessionSttsCd").value("REVISION_REQUESTED"))
                .andExpect(jsonPath("$.data[0].isEditable").value(true));
    }

    // ------------------------------------------------------------------ 헬퍼

    private Long submitSession(
            CurriculumItemEntity curriculumItem,
            String realDate,
            String content,
            EventParticipantEntity participant,
            boolean present) {
        return submitSession(
                academicProgram, curriculumItem, realDate, content, participant, present);
    }

    /*
     * 제출 API를 그대로 태워 실적을 만든다 — 리포지토리로 심으면 이 이슈가 만든 저장 규칙을
     * 지나친 데이터로 나머지를 검증하게 된다.
     */
    private Long submitSession(
            AcademicProgramEntity program,
            CurriculumItemEntity curriculumItem,
            String realDate,
            String content,
            EventParticipantEntity participant,
            boolean present) {
        try {
            String response =
                    mockMvc.perform(
                                    authorized(sessions(program), leaderToken)
                                            .content(
                                                    submitBody(
                                                            curriculumItem,
                                                            realDate,
                                                            content,
                                                            participant,
                                                            present)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            return JsonPath.parse(response).read("$.data.sessionId", Long.class);
        } catch (Exception ex) {
            throw new IllegalStateException("회차 제출 픽스처 실패", ex);
        }
    }

    /*
     * 벌크 UPDATE로 상태만 심고 영속성 컨텍스트를 비워 다음 조회가 DB를 다시 읽게 한다 —
     * 승인 API(#136)를 태우지 않는 이유는 클래스 주석에 있다.
     */
    private void changeStatus(Long sessionId, SessionStatus status) {
        entityManager.flush();
        entityManager
                .createQuery("update SessionEntity s set s.status = :status where s.id = :id")
                .setParameter("status", status)
                .setParameter("id", sessionId)
                .executeUpdate();
        entityManager.clear();
    }

    private void revertToRevisionRequested(Long sessionId) {
        changeStatus(sessionId, SessionStatus.REVISION_REQUESTED);
    }

    private String submitBody(
            CurriculumItemEntity curriculumItem, String realDate, String content, boolean present) {
        return submitBody(curriculumItem, realDate, content, confirmedMember, present);
    }

    private String submitBody(
            CurriculumItemEntity curriculumItem,
            String realDate,
            String content,
            EventParticipantEntity participant,
            boolean present) {
        String attendances =
                participant == null
                        ? "[]"
                        : """
                          [{"eventPtcpId": %d, "presentYn": %b}]
                          """
                                .formatted(participant.getId(), present);
        return """
               {"curriculumItemId": %d, "realDt": "%s", "cn": "%s",
                "noticeCn": "다음 주 준비물", "attendances": %s}
               """
                .formatted(curriculumItem.getId(), realDate, content, attendances);
    }

    private MockHttpServletRequestBuilder sessions(AcademicProgramEntity program) {
        return post(sessionsPath(program));
    }

    private String sessionsPath(AcademicProgramEntity program) {
        return PROGRAMS + "/" + program.getId() + "/sessions";
    }

    private String sessionPath(AcademicProgramEntity program, Long sessionId) {
        return sessionsPath(program) + "/" + sessionId;
    }

    private List<CurriculumItemEntity> curriculumItems(AcademicProgramEntity program) {
        return curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(program.getId());
    }

    private EventParticipantEntity confirmParticipant(
            AcademicProgramEntity program, MemberEntity member) {
        return eventParticipantRepository.save(
                EventParticipantEntity.register(
                        program.getEvent(),
                        member,
                        EventParticipantStatus.CONFIRMED,
                        null,
                        leader));
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
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
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
