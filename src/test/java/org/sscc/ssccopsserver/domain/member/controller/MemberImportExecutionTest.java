package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsvRow;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportMapping;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberImportReferenceData;
import org.sscc.ssccopsserver.domain.member.service.MemberImportRowExecutor;
import org.sscc.ssccopsserver.domain.member.service.MemberImportServiceImpl;
import org.sscc.ssccopsserver.domain.member.service.MemberInitialHistoryRecorder;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * CSV 회원 이관 실행 API (#85 · 상위 ssccops#76).
 *
 * ── 왜 이 클래스만 @Transactional이 없는가 ─────────────────────────
 * 확인의 중심이 **"한 행의 실패가 다른 행을 되돌리지 않는다"**이고, 그것은 실제 커밋·롤백이
 * 일어나야만 확인할 수 있다. 테스트 트랜잭션을 걸면 서비스의 REQUIRES_NEW가 그것을 중단시키고
 * 새 트랜잭션을 여는데, 그 안에서는 아직 커밋되지 않은 테스트 데이터(운영자 회원)가 보이지 않아
 * 이력의 chnrg_mbr_id FK부터 어긋난다 — 규칙을 검증하기는커녕 성립하지도 않는다
 * (MemberSignupRollbackTest·RoleAuthoritySelfLockTest와 같은 이유).
 *
 * 대신 뒷정리를 손으로 한다. 시드(data.sql)가 넣은 역할·권한은 건드리지 않고 이 클래스가 만든
 * 것만 지운다 — 지우면 같은 컨텍스트를 쓰는 다른 테스트가 시드 없이 돌게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberImportExecutionTest.StubJwtDecoderConfig.class)
class MemberImportExecutionTest {

    private static final String IMPORTS = "/v1/members/imports";
    private static final String VALIDATION = "/v1/members/imports/validation";

    private static final String HEADER_LINE = "이름,학번,기수,학과,학년,전화번호,이메일,가입일,등급,상태";

    private static final String FULL_MAPPING =
            """
            {"이름":"mbrNm","학번":"stdntNo","기수":"genNo","학과":"scsbjtNm","학년":"scyrNo",\
            "전화번호":"telno","이메일":"eml","가입일":"joinYmd","등급":"mbrGrdCd","상태":"mbrSttsCd"}""";

    // 이름 없는 행. 회원명·학번·학과·학년이 한꺼번에 걸려 FAILED가 된다
    private static final String INVALID_ROW = ",,,,,,,,정회원,재학";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository roleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    /*
     * 행 하나만 저장 도중에 실패시키기 위한 스파이. 값 검증으로 걸리는 실패(FAILED)는 DB에 닿지도
     * 않으므로 "다른 행이 롤백되지 않는다"를 증명하지 못한다 — 회원 INSERT가 이미 flush된 뒤에
     * 터지는 실패가 필요하다.
     */
    @MockitoSpyBean private MemberInitialHistoryRecorder initialHistoryRecorder;

    private UUID managerToken;
    private UUID outsiderToken;
    private Long managerId;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        MemberEntity manager = saveMember(managerToken, "20260301", "회원관리자");
        managerId = manager.getId();
        grant(manager, AuthorityCode.MEMBER_MANAGE);

        // MEMBER_MANAGE가 없는 회원. 권한이 아예 없는 쪽이 아니라 '다른 권한만' 가진 쪽이어야
        // 403이 인증·가입이 아니라 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260302", "업무담당"), AuthorityCode.WORK_MANAGE);
    }

    /*
     * 커밋된 데이터를 손으로 지운다. 시드가 넣은 역할·권한 매핑은 남기고 이 클래스가 만든 역할
     * (AuthorityFixture가 '역할:' 접두사로 만든다)만 지운다.
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM mbr_grd_hstry");
        jdbcTemplate.update("DELETE FROM mbr_stts_hstry");
        jdbcTemplate.update("DELETE FROM mbr_role_rel");
        jdbcTemplate.update(
                "DELETE FROM role_authrt_rel WHERE role_id IN"
                        + " (SELECT role_id FROM role WHERE role_nm LIKE '역할:%')");
        jdbcTemplate.update("DELETE FROM role WHERE role_nm LIKE '역할:%'");
        jdbcTemplate.update("DELETE FROM mbr");
    }

    // ------------------------------------------------------------------ 한 호출에 세 결과

    /*
     * **정상·오류·중복이 한 번의 호출에서 함께 처리된다** (BR-M45). 128건 중 6건이 잘못됐다고
     * 전체가 막히면 운영이 시작조차 못 한다.
     *
     * 세 버킷은 서로 겹치지 않으며 합이 totalCount다.
     */
    @Test
    void createsSkipsAndFailsInOneCall() throws Exception {
        String csv =
                csv(
                        enrolled("홍길동", "20211234"),
                        INVALID_ROW,
                        // setUp의 회원관리자가 이미 쓰고 있는 학번 → 중복
                        enrolled("오세현", "20260301"),
                        enrolled("김철수", "20211235"));

        perform(csv, managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalCount").value(4))
                .andExpect(jsonPath("$.data.summary.createdCount").value(2))
                .andExpect(jsonPath("$.data.summary.skippedCount").value(1))
                .andExpect(jsonPath("$.data.summary.failedCount").value(1))
                // rowNo는 검증(#84)과 같은 값 — 헤더를 1행으로 센 원본의 줄 번호다
                .andExpect(jsonPath("$.data.rows[0].rowNo").value(2))
                .andExpect(jsonPath("$.data.rows[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.rows[0].target").value("홍길동 20211234"))
                .andExpect(jsonPath("$.data.rows[0].mbrId").isNumber())
                .andExpect(jsonPath("$.data.rows[0].reason").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.rows[1].target").value("(회원명 없음)"))
                // 사유는 field key가 아니라 사람이 읽는 이름으로 적힌다
                .andExpect(jsonPath("$.data.rows[1].reason").value(containsString("회원명")))
                .andExpect(jsonPath("$.data.rows[1].mbrId").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[2].status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.rows[2].reason").value(containsString("이미 등록된 학번")))
                .andExpect(jsonPath("$.data.rows[3].status").value("CREATED"));

        assertThat(nameOf("20211234")).isEqualTo("홍길동");
        assertThat(nameOf("20211235")).isEqualTo("김철수");
        // 중복 행은 **덮어쓰지 않는다** (BR-M40) — 기존 회원의 이름이 그대로다
        assertThat(nameOf("20260301")).isEqualTo("회원관리자");
    }

    /*
     * **한 행의 실패가 다른 행을 되돌리지 않는다** — 이 클래스의 핵심이다.
     *
     * 가운데 행의 이력 기록만 터뜨린다. 회원 INSERT가 이미 flush된 뒤라 그 행의 트랜잭션은 통째로
     * 되돌아가야 하고(회원만 남는 반쪽 이관은 없다), 앞뒤 행은 각자의 트랜잭션에서 이미 커밋됐으므로
     * 그대로 남아야 한다. 하나의 트랜잭션으로 묶여 있다면 세 건 모두 사라진다.
     */
    @Test
    void oneRowFailureDoesNotRollBackOtherRows() throws Exception {
        willAnswer(
                        invocation -> {
                            MemberEntity member = invocation.getArgument(0);
                            if ("터지는회원".equals(member.getName())) {
                                throw new IllegalStateException("이력 기록 실패");
                            }
                            return invocation.callRealMethod();
                        })
                .given(initialHistoryRecorder)
                .record(any(), any(), any(), any(), any(), any());

        String csv =
                csv(
                        enrolled("앞회원", "20211234"),
                        enrolled("터지는회원", "20211235"),
                        enrolled("뒷회원", "20211236"));

        perform(csv, managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdCount").value(2))
                .andExpect(jsonPath("$.data.summary.failedCount").value(1))
                .andExpect(jsonPath("$.data.rows[1].status").value("FAILED"));

        assertThat(nameOf("20211234")).isEqualTo("앞회원");
        assertThat(nameOf("20211236")).isEqualTo("뒷회원");
        // 실패한 행은 회원도 이력도 남기지 않는다 — 자기 트랜잭션만 되돌아갔다
        assertThat(nameOf("20211235")).isNull();
        assertThat(historyCountOfName("터지는회원")).isZero();
    }

    /*
     * 행 처리기가 **별도 빈이고 REQUIRES_NEW**임을 애노테이션으로도 못 박는다.
     *
     * 값이 바뀌면 위 테스트도 함께 깨지지만, 그때 무엇이 잘못됐는지를 바로 말해 주는 것은 이쪽이다.
     * 특히 이 메서드를 서비스 클래스 안으로 옮기면(자기 호출) 애노테이션은 그대로 남고 경계만
     * 조용히 사라지므로, '별도 빈'이라는 사실 자체가 규칙이다.
     */
    @Test
    void rowExecutorOpensItsOwnTransactionPerRow() throws Exception {
        Transactional annotation =
                MemberImportRowExecutor.class
                        .getMethod(
                                "create",
                                MemberImportCsvRow.class,
                                MemberImportMapping.class,
                                MemberImportReferenceData.class,
                                Long.class,
                                LocalDate.class)
                        .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

        // 이관 실행 자체는 한 트랜잭션으로 묶이지 않는다
        assertThat(
                        MemberImportServiceImpl.class
                                .getMethod(
                                        "importMembers",
                                        MultipartFile.class,
                                        String.class,
                                        String.class,
                                        Long.class)
                                .getAnnotation(Transactional.class))
                .isNull();
    }

    // ------------------------------------------------------------------ 이관은 가입이 아니다

    /*
     * **'정회원'으로 적힌 행은 정회원으로 들어간다** — 가입처럼 TEMP로 덮이지 않는다.
     * 명부의 정회원을 임시회원으로 만들면 그 사람의 이력이 거짓이 된다.
     */
    @Test
    void gradeFromCsvIsNotOverwrittenWithTemp() throws Exception {
        String csv =
                csv(
                        row("정회원씨", "20211234", "정회원", "재학"),
                        row("준회원씨", "20211235", "준회원", "재학"),
                        row("활동회원씨", "20211236", "활동회원", "재학"));

        perform(csv, managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdCount").value(3));

        assertThat(gradeCodeOf("20211234")).isEqualTo("FULL");
        assertThat(gradeCodeOf("20211235")).isEqualTo("ASSOC");
        assertThat(gradeCodeOf("20211236")).isEqualTo("ACTIVE");
    }

    /*
     * **탈퇴·제명 상태가 그대로 들어간다.** 가입 경로의 isSignupSelectable()을 이쪽에 적용하지
     * 않는다 — 과거 명부에는 이미 떠난 사람이 들어 있고, 그들을 재학으로 바꿔 넣을 수는 없다.
     */
    @Test
    void withdrawnAndExpelledStatusesAreImportedAsIs() throws Exception {
        String csv =
                csv(
                        row("탈퇴자", "20211234", "정회원", "탈퇴"),
                        row("제명자", "20211235", "정회원", "제명"),
                        row("휴학생", "20211236", "정회원", "일반휴학"));

        perform(csv, managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdCount").value(3))
                .andExpect(jsonPath("$.data.summary.failedCount").value(0));

        assertThat(statusCodeOf("20211234")).isEqualTo("WITHDRAWN");
        assertThat(statusCodeOf("20211235")).isEqualTo("EXPELLED");
        assertThat(statusCodeOf("20211236")).isEqualTo("LEAVE");
    }

    /*
     * **auth_user_id는 NULL이다.** 아직 로그인한 적 없는 회원이며, 계정 연결은 별도 경로(#86)의
     * 몫이다. 목록의 linkedAccount가 false로 그려지는 근거이기도 하다(VR-M22).
     */
    @Test
    void importedMemberHasNoAuthUserId() throws Exception {
        perform(csv(enrolled("홍길동", "20211234")), managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdCount").value(1));

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM mbr WHERE stdnt_no = ? AND auth_user_id IS"
                                        + " NULL",
                                Integer.class,
                                "20211234"))
                .isEqualTo(1);
    }

    /*
     * **등급·상태의 최초 이력이 남고 chnrg_mbr_id가 요청한 운영자다** (BR-M47).
     *
     * 가입은 본인이 신청한 값이라 변경자가 본인이지만, 이관은 운영자가 한 조작이다 — 그래야
     * "이 사람 등급은 누가 정했나"에 답할 수 있다. bfr_*는 NULL이다(이관 전에는 등급도 상태도
     * 없었다는 사실 그대로).
     */
    @Test
    void initialHistoriesRecordTheRequestingOperator() throws Exception {
        perform(csv(enrolled("홍길동", "20211234")), managerToken).andExpect(status().isOk());

        Long memberId = memberIdOf("20211234");

        assertThat(gradeHistory("bfr_mbr_grd_cd", memberId, String.class)).isNull();
        assertThat(gradeHistory("aftr_mbr_grd_cd", memberId, String.class)).isEqualTo("FULL");
        assertThat(gradeHistory("grd_chg_rsn_cn", memberId, String.class)).isEqualTo("CSV 이관");
        assertThat(gradeHistory("chnrg_mbr_id", memberId, Long.class)).isEqualTo(managerId);
        // 적용일은 이력을 남긴 시각이 아니라 그 회원의 가입일이다
        assertThat(gradeHistory("grd_aplcn_ymd", memberId, LocalDate.class))
                .isEqualTo(LocalDate.of(2021, 3, 2));

        assertThat(statusHistory("bfr_mbr_stts_cd", memberId, String.class)).isNull();
        assertThat(statusHistory("aftr_mbr_stts_cd", memberId, String.class)).isEqualTo("ENROLLED");
        assertThat(statusHistory("stts_chg_rsn_cn", memberId, String.class)).isEqualTo("CSV 이관");
        assertThat(statusHistory("chnrg_mbr_id", memberId, Long.class)).isEqualTo(managerId);
    }

    /*
     * 가입일 미입력은 **이관일**(주입된 Clock)이고, 기수 미입력은 0(미배정)이며 학번으로 추정하지
     * 않는다 (BR-M43). 학번 미입력은 빈 문자열이 아니라 **NULL**이다 —
     * uk_mbr_student_number가 살아 있어 빈 문자열이면 두 번째 졸업 회원부터 UNIQUE 충돌이 난다.
     */
    @Test
    void blankOptionalValuesFallBackToTheirDefaults() throws Exception {
        String csv =
                csv(
                        "미기재씨,20211234,,컴퓨터학부,3,010-1111-2222,a@sscc.org,,정회원,재학",
                        "졸업생1,,,,,010-1111-3333,b@sscc.org,2010-03-02,정회원,졸업",
                        "졸업생2,,,,,010-1111-4444,c@sscc.org,2011-03-02,정회원,졸업");

        perform(csv, managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdCount").value(3))
                // 학번 없이 들어간 두 건은 재실행하면 또 들어간다 — 그 사실을 요약이 드러낸다
                .andExpect(jsonPath("$.data.summary.reimportDuplicatesCount").value(2));

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT gen_no FROM mbr WHERE stdnt_no = ?",
                                Integer.class,
                                "20211234"))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT join_ymd FROM mbr WHERE stdnt_no = ?",
                                LocalDate.class,
                                "20211234"))
                .isEqualTo(LocalDate.now());

        // 학번 없는 두 행이 서로 충돌하지 않는다(NULL은 UNIQUE에 걸리지 않는다)
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM mbr WHERE stdnt_no IS NULL", Integer.class))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ 재실행 · fileToken

    /*
     * **멱등하지 않다.** 같은 파일을 두 번 실행하면 학번이 있는 행은 전부 SKIPPED지만, 학번이 없는
     * 졸업 회원 행은 중복이라고 판정할 근거가 아예 없어 **두 번 들어간다** — 이름이 같다고 같은
     * 사람이라 볼 수 없고, 이관 배치를 기록할 테이블은 데이터사전에 없다.
     *
     * 이 한계를 감추지 않고 reimportDuplicatesCount로 드러내는 것이 지금의 답이다.
     */
    @Test
    void rerunningTheSameFileSkipsRowsThatHaveAStudentNumber() throws Exception {
        String csv =
                csv(
                        enrolled("홍길동", "20211234"),
                        enrolled("김철수", "20211235"),
                        "졸업생,,,,,010-1111-3333,b@sscc.org,2010-03-02,정회원,졸업");

        perform(csv, managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdCount").value(3));

        perform(csv, managerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.skippedCount").value(2))
                .andExpect(jsonPath("$.data.rows[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.rows[1].status").value("SKIPPED"))
                // 학번 없는 행은 막을 근거가 없어 다시 들어간다. 감추지 않고 세어서 알린다
                .andExpect(jsonPath("$.data.rows[2].status").value("CREATED"))
                .andExpect(jsonPath("$.data.summary.reimportDuplicatesCount").value(1));

        assertThat(memberCountOfName("홍길동")).isEqualTo(1);
        assertThat(memberCountOfName("졸업생")).isEqualTo(2);
    }

    /*
     * **fileToken이 다르면 409이고 mbr 행 수가 변하지 않는다** (BR-M48). 대조를 파싱보다 먼저 하므로
     * 파일은 한 줄도 읽히지 않는다 — 확인한 내용과 들어가는 내용이 달라지면 사전 검증이 의미를 잃는다.
     */
    @Test
    void fileTokenMismatchIsRejectedWithoutInsertingAnything() throws Exception {
        long before = memberRepository.count();
        String csv = csv(enrolled("홍길동", "20211234"), enrolled("김철수", "20211235"));

        mockMvc.perform(request(csv, tokenOf(csv.replace("홍길동", "다른사람")), managerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_MISMATCH"));

        assertThat(memberRepository.count()).isEqualTo(before);
    }

    /** 토큰을 빼면 검사를 건너뛸 수 있다면 검사가 아니다 */
    @Test
    void missingFileTokenIsAlsoRejected() throws Exception {
        long before = memberRepository.count();
        String csv = csv(enrolled("홍길동", "20211234"));

        MockMultipartHttpServletRequestBuilder builder = multipart(IMPORTS).file(csvFile(csv));
        builder.param("mapping", FULL_MAPPING);
        builder.header("Authorization", "Bearer " + managerToken);

        mockMvc.perform(builder)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_MISMATCH"));

        assertThat(memberRepository.count()).isEqualTo(before);
    }

    /*
     * 검증 응답이 준 토큰이 그대로 실행에 쓰인다. 두 엔드포인트가 같은 방식으로 해시를 만든다는
     * 사실 자체가 계약이라, 토큰을 테스트가 직접 계산하지 않고 검증 응답에서 꺼내 확인한다.
     */
    @Test
    void tokenFromValidationEndpointIsAcceptedByExecution() throws Exception {
        String csv = csv(enrolled("홍길동", "20211234"));

        MockMultipartHttpServletRequestBuilder validation =
                multipart(VALIDATION).file(csvFile(csv));
        validation.param("mapping", FULL_MAPPING);
        validation.header("Authorization", "Bearer " + managerToken);

        String body =
                mockMvc.perform(validation)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        int start = body.indexOf("sha256:");
        String issuedToken = body.substring(start, body.indexOf('"', start));

        mockMvc.perform(request(csv, issuedToken, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.createdCount").value(1));
    }

    // ------------------------------------------------------------------ 인가

    @Test
    void importWithoutMemberManageIsForbidden() throws Exception {
        long before = memberRepository.count();

        perform(csv(enrolled("홍길동", "20211234")), outsiderToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(memberRepository.count()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 헬퍼

    private ResultActions perform(String csv, UUID token) throws Exception {
        return mockMvc.perform(request(csv, tokenOf(csv), token));
    }

    private MockMultipartHttpServletRequestBuilder request(
            String csv, String fileToken, UUID authToken) {

        MockMultipartHttpServletRequestBuilder builder = multipart(IMPORTS).file(csvFile(csv));
        builder.param("mapping", FULL_MAPPING);
        builder.param("fileToken", fileToken);
        builder.header("Authorization", "Bearer " + authToken);
        return builder;
    }

    private static String csv(String... rows) {
        return HEADER_LINE + "\n" + String.join("\n", List.of(rows)) + "\n";
    }

    private static String enrolled(String name, String studentNumber) {
        return row(name, studentNumber, "정회원", "재학");
    }

    private static String row(String name, String studentNumber, String grade, String status) {
        return "%s,%s,30,컴퓨터학부,3,010-1111-2222,%s@sscc.org,2021-03-02,%s,%s"
                .formatted(name, studentNumber, studentNumber, grade, status);
    }

    private static MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "members.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    /** 서비스와 같은 방식으로 계산한 파일 내용의 SHA-256 */
    private static String tokenOf(String csv) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(csv.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String nameOf(String studentNumber) {
        return jdbcTemplate
                .query(
                        "SELECT mbr_nm FROM mbr WHERE stdnt_no = ?",
                        (rs, rowNum) -> rs.getString(1),
                        studentNumber)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Long memberIdOf(String studentNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT mbr_id FROM mbr WHERE stdnt_no = ?", Long.class, studentNumber);
    }

    private String gradeCodeOf(String studentNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT mbr_grd_cd FROM mbr WHERE stdnt_no = ?", String.class, studentNumber);
    }

    private String statusCodeOf(String studentNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT mbr_stts_cd FROM mbr WHERE stdnt_no = ?", String.class, studentNumber);
    }

    private int memberCountOfName(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mbr WHERE mbr_nm = ?", Integer.class, name);
    }

    private <T> T gradeHistory(String column, Long memberId, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT %s FROM mbr_grd_hstry WHERE mbr_id = ?".formatted(column), type, memberId);
    }

    private <T> T statusHistory(String column, Long memberId, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT %s FROM mbr_stts_hstry WHERE mbr_id = ?".formatted(column), type, memberId);
    }

    private int historyCountOfName(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mbr_grd_hstry h JOIN mbr m ON m.mbr_id = h.mbr_id"
                        + " WHERE m.mbr_nm = ?",
                Integer.class,
                name);
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
                roleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
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
