package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.sscc.ssccopsserver.domain.academicprogram.repository.FileReferenceRepository;
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

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
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

    /** application-test.yaml의 r2.public-base-url·r2.bucket-name과 같은 값이어야 한다 */
    private static final String PUBLIC_BASE_URL = "https://images.test.local";

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

    @MockitoBean private S3Presigner r2Presigner;

    private UUID leaderToken;
    private MemberEntity leader;
    private UUID otherToken;

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

        present =
                confirmParticipant(
                        academicProgram, saveMember(UUID.randomUUID(), "20260403", "참석자"));
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

        String publicUrl = JsonPath.parse(response).read("$.data.publicUrl", String.class);
        String uploadUrl = JsonPath.parse(response).read("$.data.uploadUrl", String.class);

        String keyPrefix =
                "academic-programs/" + academicProgram.getId() + "/sessions/" + sessionId + "/";
        assertThat(publicUrl).startsWith(PUBLIC_BASE_URL + "/" + keyPrefix).endsWith(".jpg");
        String objectKey = publicUrl.substring((PUBLIC_BASE_URL + "/").length());
        assertThat(uploadUrl).contains(objectKey);

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
        String firstUrl = JsonPath.parse(first).read("$.data.publicUrl", String.class);
        String secondUrl = JsonPath.parse(second).read("$.data.publicUrl", String.class);

        assertThat(secondId).isEqualTo(firstId);
        assertThat(secondUrl).isNotEqualTo(firstUrl).endsWith(".png");

        entityManager.flush();
        assertThat(fileReferenceRepository.count()).isEqualTo(1);
        assertThat(fileReferenceRepository.findAll())
                .singleElement()
                .satisfies(reference -> assertThat(reference.getFileUrl()).isEqualTo(secondUrl));
    }

    // 회차 상세(#135)가 그 참조를 싣는다 — 화면은 이 블록의 유무로 사진 유무를 가른다
    @Test
    void sessionDetailCarriesFileReferenceAfterUpload() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileReference").value(Matchers.nullValue()));

        String issued = issueUpload(sessionId, "png");
        Long fileReferenceId = JsonPath.parse(issued).read("$.data.fileReferenceId", Long.class);
        String publicUrl = JsonPath.parse(issued).read("$.data.publicUrl", String.class);

        mockMvc.perform(authorized(get(sessionPath(academicProgram, sessionId)), otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileReference.fileReferenceId").value(fileReferenceId))
                .andExpect(jsonPath("$.data.fileReference.fileUrlAddr").value(publicUrl));
    }

    // jpg·jpeg는 둘 다 받되 키에 쓰는 확장자는 하나로 굳힌다. 앞의 점·대문자도 같은 형식이다
    @Test
    void fileExtensionIsNormalizedToCanonicalExtension() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        assertThat(issuedPublicUrl(sessionId, "jpeg")).endsWith(".jpg");
        assertThat(issuedPublicUrl(sessionId, ".JPG")).endsWith(".jpg");
        assertThat(issuedPublicUrl(sessionId, " WEBP ")).endsWith(".webp");
    }

    /*
     * 허용 목록 밖은 400이다. SVG는 이미지이면서 스크립트를 담을 수 있는 문서라 공개 도메인에서
     * 그대로 열리는 순간 XSS 경로가 되므로 의도적으로 빠져 있다(EventImageType).
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

    @Test
    void issueFileReferenceUploadUrlWithoutFileExtReturns400() throws Exception {
        Long sessionId = submitSession(firstItem, "2026-09-05");

        mockMvc.perform(
                        authorized(post(fileReferencePath(academicProgram, sessionId)), leaderToken)
                                .content(
                                        """
                                         {"fileExt": " "}
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

    private String issuedPublicUrl(Long sessionId, String fileExt) throws Exception {
        return JsonPath.parse(issueUpload(sessionId, fileExt))
                .read("$.data.publicUrl", String.class);
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
        return """
               {"fileExt": "%s"}
               """.formatted(fileExt);
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
