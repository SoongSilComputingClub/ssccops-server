package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
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

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 출석 정정·인증사진 업로드 API (#137). 조회는 인증만, 정정·업로드는 스터디장/팀장 본인만이며
 * 승인 완료(APPROVED)된 회차는 둘 다 잠긴다.
 *
 * **S3Presigner를 목으로 갈아 끼운다** — 진짜 빈을 두면 R2 서명 키를 요구하고, 무엇보다 이
 * 테스트가 확인하려는 것은 서명 알고리즘이 아니라 서버가 무엇에 서명을 요청하는가(버킷·키·
 * contentType·유효기간)다(EventImageControllerTest와 같은 판단).
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다 — 서비스가 @Transactional이라
 * 예외가 테스트 트랜잭션을 rollback-only로 표시하고, 그 뒤 이어지는 요청은
 * UnexpectedRollbackException을 만난다(AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramAttendanceControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AcademicProgramAttendanceControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";

    /*
     * application-test.yaml의 r2.bucket-name과 같은 값이어야 한다.
     *
     * 공개 도메인 설정은 이 도메인이 쓰지 않았고 (#200 — 인증사진은 비공개 버킷에 두고 서명된
     * URL로만 오간다) 지금은 아예 없다 (#208 — 행사 이미지도 같은 방식으로 옮겨 갔다).
     */
    private static final String BUCKET = "test-bucket";

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
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @MockitoBean private S3Presigner r2Presigner;

    private UUID leaderToken;
    private MemberEntity leader;
    private UUID otherToken;

    /** 명단에 있는 팀원과 학술국장 — 사진을 볼 수 있는 나머지 두 자격이다 (#200) */
    private UUID participantToken;

    private UUID managerToken;

    private AcademicProgramEntity academicProgram;
    private CurriculumItemEntity firstItem;
    private EventParticipantEntity present;
    private EventParticipantEntity absent;

    @BeforeEach
    void setUp() {
        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20260401", "스터디장");
        otherToken = UUID.randomUUID();
        saveMember(otherToken, "20260402", "다른회원");

        academicProgram = createAcademicProgram("알고리즘 스터디", "OT", "1주차");
        firstItem = curriculumItems(academicProgram).get(0);

        participantToken = UUID.randomUUID();
        present =
                confirmParticipant(
                        academicProgram, saveMember(participantToken, "20260403", "참석자"));

        managerToken = UUID.randomUUID();
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                saveMember(managerToken, "20260410", "학술국장"),
                AuthorityCode.ACADEMIC_PROGRAM_MANAGE);
        absent =
                confirmParticipant(
                        academicProgram, saveMember(UUID.randomUUID(), "20260404", "결석자"));

        // 서명은 흉내만 낸다 — 요청받은 키를 URL에 실어 돌려주므로 '무엇에 서명했는가'가 드러난다
        when(r2Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            PutObjectPresignRequest presignRequest = invocation.getArgument(0);
                            return stubPresignedPutObject(presignRequest.putObjectRequest().key());
                        });
        when(r2Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenAnswer(
                        invocation -> {
                            GetObjectPresignRequest presignRequest = invocation.getArgument(0);
                            return stubPresignedGetObject(presignRequest.getObjectRequest().key());
                        });
    }

    // ------------------------------------------------------------------ 출석부 조회 (GET)

    /*
     * 줄마다 attendanceId를 함께 내린다 — 회차 상세(#135)의 출석 블록과 갈리는 유일한 값이며,
     * 출석 화면이 줄을 개별 지목하는 근거다.
     */
    @Test
    void getAttendancesReturnsRowsWithAttendanceId() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(authorized(get(attendancesPath(academicProgram, sessionId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].attendanceId").isNumber())
                .andExpect(jsonPath("$.data[0].eventPtcpId").value(present.getId()))
                .andExpect(jsonPath("$.data[0].mbrNm").value("참석자"))
                .andExpect(jsonPath("$.data[0].atndYn").value(true))
                .andExpect(jsonPath("$.data[1].eventPtcpId").value(absent.getId()))
                .andExpect(jsonPath("$.data[1].mbrNm").value("결석자"))
                .andExpect(jsonPath("$.data[1].atndYn").value(false));
    }

    // 조회는 인증만 요구한다 — 팀원도 일반 회원도 자기 활동의 출석부를 본다
    @Test
    void getAttendancesAsOtherMemberReturns200() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(authorized(get(attendancesPath(academicProgram, sessionId)), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)));
    }

    // 승인된 회차의 출석부는 고칠 수 없을 뿐 볼 수는 있다
    @Test
    void getAttendancesOfApprovedSessionReturns200() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        changeStatus(sessionId, SessionStatus.APPROVED);

        mockMvc.perform(authorized(get(attendancesPath(academicProgram, sessionId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)));
    }

    // 회차 식별자만 보면 남의 활동 출석부가 새어 나간다 — 질의가 활동으로 좁혀 찾는다
    @Test
    void getAttendancesThroughAnotherProgramReturns404() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        AcademicProgramEntity another = createAcademicProgram("남의 스터디", "남의 OT");

        mockMvc.perform(authorized(get(attendancesPath(another, sessionId)), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    // 활동 자체가 없으면 회차 404가 아니라 활동 404다 — 화면이 둘을 구별해야 한다
    @Test
    void getAttendancesOfUnknownProgramReturns404() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS + "/999999/sessions/1/attendances"), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    @Test
    void getAttendancesWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(PROGRAMS + "/1/sessions/1/attendances"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 출석 정정 (PATCH)

    /*
     * 부분 갱신이다 — 요청에 실린 줄만 값이 바뀌고 나머지는 그대로다. 응답은 갱신된 줄이 아니라
     * 출석부 전체와 집계이며(체크리스트 관례), 그래야 화면이 "N/M 참석"을 스스로 세지 않는다.
     */
    @Test
    void correctAttendancesUpdatesOnlyListedRowsAndReturnsSummary() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(patchBody(absent, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attendances", Matchers.hasSize(2)))
                // 요청에 없던 줄은 그대로다
                .andExpect(jsonPath("$.data.attendances[0].eventPtcpId").value(present.getId()))
                .andExpect(jsonPath("$.data.attendances[0].atndYn").value(true))
                .andExpect(jsonPath("$.data.attendances[1].eventPtcpId").value(absent.getId()))
                .andExpect(jsonPath("$.data.attendances[1].atndYn").value(true))
                .andExpect(jsonPath("$.data.presentCount").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    // 참석 → 결석도 같은 경로다. 체크박스가 같은 자리에서 켜지고 꺼진다
    @Test
    void correctAttendancesCanUncheckPresence() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(patchBody(present, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.presentCount").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    // 정정은 명단을 바꾸지 않는다 — 줄을 더하지도 빼지도 않으므로 행 수가 그대로다
    @Test
    void correctAttendancesDoesNotAddOrRemoveRows() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(patchBody(present, false)))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(attendanceRepository.count()).isEqualTo(2);
    }

    /*
     * 이 API가 존재하는 이유다 — 국장 검토 대기(SUBMITTED)라 기록 본문은 재제출할 수 없지만
     * (409 SESSION_NOT_EDITABLE) 출석은 바로잡을 수 있다. 두 판정이 갈리는 지점이다.
     */
    @Test
    void correctAttendancesOnSubmittedSessionReturns200() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sttsCd").value("SUBMITTED"));

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(patchBody(absent, true)))
                .andExpect(status().isOk());
    }

    // 수정요청 상태도 고칠 수 있다 — 잠기는 것은 APPROVED 하나뿐이다
    @Test
    void correctAttendancesOnRevisionRequestedSessionReturns200() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        changeStatus(sessionId, SessionStatus.REVISION_REQUESTED);

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(patchBody(absent, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.presentCount").value(2));
    }

    // 확정된 이력이 조회 시점마다 달라지면 안 된다 (설계 결정 #2)
    @Test
    void correctAttendancesOnApprovedSessionReturns409() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        changeStatus(sessionId, SessionStatus.APPROVED);

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(patchBody(absent, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_EDITABLE"));
    }

    /*
     * 출석부에 줄이 없는 대상은 확정 팀원이어도 400이다 — 정정은 체크 값만 바꾸는 일이고,
     * 대상을 더하는 것은 재제출(#135, 전체 교체)의 몫이다.
     */
    @Test
    void correctAttendancesWithParticipantNotOnTheSheetReturns400() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        EventParticipantEntity latecomer =
                confirmParticipant(
                        academicProgram, saveMember(UUID.randomUUID(), "20260405", "늦게 합류한 팀원"));

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(patchBody(latecomer, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTENDANCE_TARGET"));
    }

    // 같은 참가자가 서로 다른 답을 싣고 두 줄로 오면 무엇이 맞는지 정할 규칙이 없다
    @Test
    void correctAttendancesWithDuplicatedParticipantReturns400() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        """
                                        {"attendances": [{"eventPtcpId": %d, "atndYn": true},
                                                         {"eventPtcpId": %d, "atndYn": false}]}
                                        """
                                                .formatted(absent.getId(), absent.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTENDANCE_TARGET"));
    }

    // 아무것도 바꾸지 않는 정정은 요청이 잘못 조립됐다는 뜻이다
    @Test
    void correctAttendancesWithEmptyListReturns400() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        """
                                         {"attendances": []}
                                         """))
                .andExpect(status().isBadRequest());
    }

    // atndYn 누락은 조용한 결석이 아니라 400이다 (Boolean인 이유)
    @Test
    void correctAttendancesWithoutPresentYnReturns400() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        """
                                        {"attendances": [{"eventPtcpId": %d}]}
                                        """
                                                .formatted(absent.getId())))
                .andExpect(status().isBadRequest());
    }

    // 스터디장/팀장 본인이 아니면 403이다 — 조회는 되지만 정정은 안 된다
    @Test
    void correctAttendancesAsOtherMemberReturns403() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(patch(attendancesPath(academicProgram, sessionId)), otherToken)
                                .content(patchBody(absent, true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 남의 활동 경로로 부르면 404다. 소유권(403)을 회차 조회(404)보다 먼저 보므로, 회차 번호를
     * 바꿔 가며 부르는 것만으로 남의 활동에 몇 번 회차가 있는지 알아낼 수 없다.
     */
    @Test
    void correctAttendancesThroughAnotherProgramReturns404() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        AcademicProgramEntity another = createAcademicProgram("남의 스터디", "남의 OT");

        mockMvc.perform(
                        authorized(patch(attendancesPath(another, sessionId)), leaderToken)
                                .content(patchBody(absent, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 인증사진 (POST)

    /*
     * 발급의 기본형. 키 규칙과 응답 네 필드, 그리고 서명을 요청한 내용(버킷·키·contentType·
     * 유효기간)까지 한자리에서 못 박는다 — 하나만 어긋나도 브라우저의 PUT이 R2에서 거절된다.
     */
    @Test
    void issueFileReferenceUploadUrlReturns201WithKeyRule() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        String response =
                mockMvc.perform(
                                authorized(
                                                post(fileReferencePath(academicProgram, sessionId)),
                                                leaderToken)
                                        .content(uploadBody("jpg")))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.fileReferenceId").isNumber())
                        // 웹은 이 값을 그대로 PUT의 Content-Type 헤더에 실어야 한다
                        .andExpect(jsonPath("$.data.contentType").value("image/jpeg"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String viewUrl = JsonPath.parse(response).read("$.data.viewUrl", String.class);
        String uploadUrl = JsonPath.parse(response).read("$.data.uploadUrl", String.class);

        String keyPrefix =
                "academic-programs/" + academicProgram.getId() + "/sessions/" + sessionId + "/";
        String objectKey = storedObjectKey();
        assertThat(objectKey).startsWith(keyPrefix).endsWith(".jpg");
        assertThat(uploadUrl).contains(objectKey);
        /*
         * viewUrl은 공개 주소가 아니라 **서명된 읽기 주소**다 (#200) — 업로드 직후 미리보기에
         * 쓰라고 함께 내려주며, 회차 상세가 내려주는 것과 같은 방식으로 서명된다.
         */
        assertThat(viewUrl).isEqualTo(signedUrlOf(objectKey, "stub-get"));

        // 파일명이 아니라 UUID다 — 같은 이름을 두 번 올려도 앞의 것이 덮이지 않는다
        String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        assertThat(UUID.fromString(fileName.substring(0, fileName.length() - ".jpg".length())))
                .isNotNull();

        ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(r2Presigner).presignPutObject(captor.capture());
        PutObjectPresignRequest presignRequest = captor.getValue();
        assertThat(presignRequest.putObjectRequest().bucket()).isEqualTo(BUCKET);
        assertThat(presignRequest.putObjectRequest().key()).isEqualTo(objectKey);
        // contentType까지 서명에 넣지 않으면 허가받은 URL로 아무 형식이나 올릴 수 있다
        assertThat(presignRequest.putObjectRequest().contentType()).isEqualTo("image/jpeg");
        assertThat(presignRequest.signatureDuration().toMinutes()).isEqualTo(10);
    }

    /*
     * 재업로드는 UPSERT다(설계 결정 #1) — 거절하지 않고 참조를 갈아 끼운다. fileReferenceId가
     * 유지되고 행이 늘지 않는 것이 요점이며, 그래야 화면이 들고 있던 식별자가 무효가 되지 않는다.
     */
    @Test
    void reissueReplacesExistingFileReferenceWithoutAddingRow() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        String first = issueUpload(sessionId, "jpg");
        String second = issueUpload(sessionId, "png");

        Long firstId = JsonPath.parse(first).read("$.data.fileReferenceId", Long.class);
        Long secondId = JsonPath.parse(second).read("$.data.fileReferenceId", Long.class);
        String firstUrl = JsonPath.parse(first).read("$.data.viewUrl", String.class);
        String secondUrl = JsonPath.parse(second).read("$.data.viewUrl", String.class);

        assertThat(secondId).isEqualTo(firstId);
        assertThat(secondUrl).isNotEqualTo(firstUrl);

        entityManager.flush();
        assertThat(fileReferenceRepository.count()).isEqualTo(1);
        // 저장되는 값은 URL이 아니라 오브젝트 키다 (#200) — 읽기가 그 키로 서명한다
        assertThat(fileReferenceRepository.findAll())
                .singleElement()
                .satisfies(
                        reference -> {
                            assertThat(reference.getFileUrl())
                                    .startsWith("academic-programs/")
                                    .endsWith(".png");
                            assertThat(secondUrl).contains(reference.getFileUrl());
                            /*
                             * 소유자는 FK가 아니라 (대상_구분_코드, 대상_ID) 두 값이다 (#220).
                             * 회차 상세가 그 짝으로 되찾으므로, 한쪽만 어긋나면 방금 올린 사진이
                             * 상세에서 사라진다.
                             */
                            assertThat(reference.getTargetType()).isEqualTo(FileTargetType.SESSION);
                            assertThat(reference.getTargetId()).isEqualTo(sessionId);
                        });
    }

    /*
     * 회차 상세(#135)가 그 참조를 싣는다 — 화면은 이 블록의 유무로 사진 유무를 가른다.
     *
     * **fileUrlAddr은 서명된 읽기 URL이다** (#200). 버킷이 비공개라 저장된 키를 그대로 내리면
     * 열리지 않으므로 조회 시점에 서명해 싣고, 남은 시간을 함께 알려 준다.
     */
    @Test
    void sessionDetailCarriesSignedFileReferenceAfterUpload() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileReference").value(Matchers.nullValue()));

        String issued = issueUpload(sessionId, "png");
        Long fileReferenceId = JsonPath.parse(issued).read("$.data.fileReferenceId", Long.class);
        String objectKey = storedObjectKey();

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileReference.fileReferenceId").value(fileReferenceId))
                // 서명 스텁이 키를 URL에 실어 돌려주므로 '무엇에 서명했는가'가 드러난다
                .andExpect(
                        jsonPath("$.data.fileReference.fileUrlAddr")
                                .value(signedUrlOf(objectKey, "stub-get")))
                .andExpect(jsonPath("$.data.fileReference.expiresInSeconds").value(900));
    }

    // 팀원도 사진을 본다 — 명단에 있으면 그 활동의 관계자다
    @Test
    void sessionDetailCarriesFileReferenceForParticipant() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        issueUpload(sessionId, "png");

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), participantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileReference.fileUrlAddr").isNotEmpty());
    }

    // 학술국장은 명단에 없어도 본다 — 감독 자격이다
    @Test
    void sessionDetailCarriesFileReferenceForManager() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        issueUpload(sessionId, "png");

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileReference.fileUrlAddr").isNotEmpty());
    }

    /*
     * 관계자가 아니면 사진 블록을 아예 내리지 않는다 (#200) — 인증사진은 얼굴이 찍힌 사진이라
     * 발급이 곧 읽기 권한이다. 상세의 나머지 필드는 종전대로 내려간다(조회 자체는 인증만이다).
     *
     * 사진이 없는 회차와 **같은 응답**이다 — 사진 유무도 관계자가 아닌 사람에게 알릴 값이 아니다.
     */
    @Test
    void sessionDetailHidesFileReferenceFromOutsider() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        issueUpload(sessionId, "png");

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.fileReference").value(Matchers.nullValue()));
    }

    /*
     * #200 이전에 저장된 행은 값이 전체 URL이다. 마이그레이션 없이 동작해야 하므로, 키가 아니라
     * URL이 들어 있어도 같은 키로 서명된다(FileReferenceEntity.objectKey).
     */
    @Test
    void sessionDetailSignsLegacyRowsStoringFullUrl() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        issueUpload(sessionId, "png");

        String objectKey = storedObjectKey();
        fileReferenceRepository
                .findAll()
                .get(0)
                .changeFileUrl("https://legacy.example.com/" + objectKey);
        entityManager.flush();

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.fileReference.fileUrlAddr")
                                .value(signedUrlOf(objectKey, "stub-get")));
    }

    // jpg·jpeg는 둘 다 받되 키에 쓰는 확장자는 하나로 굳힌다. 앞의 점·대문자도 같은 형식이다
    @Test
    void fileExtensionIsNormalizedToCanonicalExtension() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        assertThat(issuedObjectKey(sessionId, "jpeg")).endsWith(".jpg");
        assertThat(issuedObjectKey(sessionId, ".JPG")).endsWith(".jpg");
        assertThat(issuedObjectKey(sessionId, " WEBP ")).endsWith(".webp");
    }

    /*
     * 허용 목록 밖은 400이다. SVG는 이미지이면서 스크립트를 담을 수 있는 문서라 공개 도메인에서
     * 그대로 열리는 순간 XSS 경로가 되므로 의도적으로 빠져 있다(ImageFileType).
     */
    @Test
    void unsupportedFileExtensionReturns400() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), leaderToken)
                                .content(uploadBody("svg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    /*
     * 상한을 넘긴 크기는 413이고 **서명 자체를 만들지 않는다** (ssccops#188). 그전까지 인증사진에는
     * 이 판정이 아예 없어 아무 크기나 올라갔다 — 행사 이미지는 처음부터 끊고 있었다.
     *
     * 서명을 만들지 않는 것까지 함께 보는 것은 이 레포가 자격·형식 검사에서 지켜 온 순서 그대로다:
     * 발급은 곧 버킷 쓰기 허가라, 만들어 두고 응답에서 빼는 구조는 한 줄만 어긋나도 새어 나간다.
     */
    @Test
    void oversizedFileReferenceUploadReturns413AndNeverSigns() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), leaderToken)
                                .content(uploadBody("jpg", 10L * 1024 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));

        verify(r2Presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));
    }

    /*
     * 신고한 크기가 **그대로 서명에 들어간다** (ssccops#188). 이것이 상한을 실제로 강제하는
     * 지점이다 — PUT은 서버를 거치지 않으므로 서명에 없는 조건은 누구도 검사하지 않는다.
     *
     * 상한값이 아니라 요청값을 확인하는 것이 요점이다. 상한을 서명하면 그보다 작은 파일이 전부
     * 거절되고, 그 실패는 서버 로그가 아니라 브라우저에서만 보인다.
     */
    @Test
    void requestedFileSizeIsSignedAsContentLength() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        long declaredSize = 4242L;

        mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), leaderToken)
                                .content(uploadBody("jpg", declaredSize)))
                .andExpect(status().isCreated());

        ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(r2Presigner).presignPutObject(captor.capture());
        assertThat(captor.getValue().putObjectRequest().contentLength()).isEqualTo(declaredSize);
    }

    @Test
    void issueFileReferenceUploadUrlWithoutFileExtReturns400() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        """
                                         {"fileExt": " ", "fileSize": 1024}
                                         """))
                .andExpect(status().isBadRequest());
    }

    // 확정된 회차에는 사진도 새로 붙이지 못한다 (설계 결정 #2)
    @Test
    void issueFileReferenceUploadUrlOnApprovedSessionReturns409() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");
        changeStatus(sessionId, SessionStatus.APPROVED);

        mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), leaderToken)
                                .content(uploadBody("jpg")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_EDITABLE"));
    }

    // 소유권이 없으면 서명 요청 자체가 나가지 않아야 한다 — 발급은 곧 버킷 쓰기 허가다
    @Test
    void issueFileReferenceUploadUrlAsOtherMemberReturns403() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), otherToken)
                                .content(uploadBody("jpg")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void issueFileReferenceUploadUrlWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        post(PROGRAMS + "/1/sessions/1/file-reference")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(uploadBody("jpg")))
                .andExpect(status().isUnauthorized());
    }

    /* ── 도우미 ───────────────────────────────────────────── */

    private String issueUpload(Long sessionId, String fileExt) throws Exception {
        return mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), leaderToken)
                                .content(uploadBody(fileExt)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** 발급 뒤 저장된 오브젝트 키. 키에 붙는 확장자가 하나로 굳는지를 이 값으로 본다 */
    private String issuedObjectKey(Long sessionId, String fileExt) throws Exception {
        issueUpload(sessionId, fileExt);
        return storedObjectKey();
    }

    /*
     * 제출 API를 그대로 태워 회차와 출석부를 만든다 — 리포지토리로 심으면 #135가 세운 저장
     * 규칙을 지나친 데이터로 이 이슈의 규칙을 검증하게 된다. 참석 1 · 결석 1로 시작한다.
     */
    private Long submitSession(CurriculumItemEntity curriculumItem, String realDate) {
        try {
            String response =
                    mockMvc.perform(
                                    authorized(post(sessionsPath(academicProgram)), leaderToken)
                                            .content(
                                                    """
                                                    {"curriculumItemId": %d, "actlYmd": "%s", "prgrsCn": "회차 내용",
                                                     "attendances": [{"eventPtcpId": %d, "atndYn": true},
                                                                     {"eventPtcpId": %d, "atndYn": false}]}
                                                    """
                                                            .formatted(
                                                                    curriculumItem.getId(),
                                                                    realDate,
                                                                    present.getId(),
                                                                    absent.getId())))
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
     * 승인 API(#136)를 태우면 국장 권한 부여까지 픽스처가 지고 가야 하고, 그 규칙과 무관한
     * 실패가 이 테스트로 흘러든다(AcademicProgramSessionControllerTest와 같은 판단).
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

    private static String patchBody(EventParticipantEntity participant, boolean present) {
        return """
               {"attendances": [{"eventPtcpId": %d, "atndYn": %b}]}
               """
                .formatted(participant.getId(), present);
    }

    private static String uploadBody(String fileExt) {
        return uploadBody(fileExt, 1024L);
    }

    private static String uploadBody(String fileExt, long fileSize) {
        return """
               {"fileExt": "%s", "fileSize": %d}
               """
                .formatted(fileExt, fileSize);
    }

    private String sessionsPath(AcademicProgramEntity program) {
        return PROGRAMS + "/" + program.getId() + "/sessions";
    }

    private String sessionPath(AcademicProgramEntity program, Long sessionId) {
        return sessionsPath(program) + "/" + sessionId;
    }

    private String attendancesPath(AcademicProgramEntity program, Long sessionId) {
        return sessionPath(program, sessionId) + "/attendances";
    }

    private String fileReferencePath(AcademicProgramEntity program, Long sessionId) {
        return sessionPath(program, sessionId) + "/file-reference";
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

    /* 진짜 서명 대신 키를 그대로 실은 URL을 돌려준다 (PresignedRequest.url()은 httpRequest에서 온다) */
    /** 저장된 참조의 오브젝트 키. 회차당 1건이라 고를 것이 없다 */
    private String storedObjectKey() {
        entityManager.flush();
        return fileReferenceRepository.findAll().get(0).getFileUrl();
    }

    private static PresignedGetObjectRequest stubPresignedGetObject(String objectKey) {
        URI uri = URI.create(signedUrlOf(objectKey, "stub-get"));
        return PresignedGetObjectRequest.builder()
                .expiration(Instant.now().plusSeconds(900))
                .isBrowserExecutable(true)
                .signedHeaders(Map.of("host", List.of("test-account.r2.cloudflarestorage.com")))
                .httpRequest(SdkHttpRequest.builder().method(SdkHttpMethod.GET).uri(uri).build())
                .build();
    }

    /** 서명은 흉내만 낸다 — 요청받은 키를 URL에 실어 '무엇에 서명했는가'를 드러낸다 */
    private static String signedUrlOf(String objectKey, String signature) {
        return "https://test-account.r2.cloudflarestorage.com/"
                + BUCKET
                + "/"
                + objectKey
                + "?X-Amz-Signature="
                + signature;
    }

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
