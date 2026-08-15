package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberImportServiceImpl;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * CSV 회원 이관 사전 검증 API (#84 · 상위 ssccops#75).
 *
 * 확인의 중심은 **"이 API는 아무것도 넣지 않고, 넣기 전에 무엇이 걸리는지 알려 준다"**이다.
 * 그래서 검증 뒤 mbr 행 수가 그대로여야 하고, 등급·상태 명칭은 기준 코드 테이블을 따라야 하며
 * (자바 코드에 박혀 있으면 기준정보에서 이름을 바꾼 다음 날 명부가 통째로 거절된다), 연락처가
 * 빈 행은 오류가 아니라 경고로 잡혀 okCount에 그대로 남아야 한다.
 *
 * 서비스가 예외를 던지면 참여 중인 테스트 트랜잭션이 rollback-only로 표시되므로, 400·403을 보는
 * 테스트는 실패하는 요청 하나로 끝낸다 (RoleClassificationControllerTest와 같은 이유).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberImportControllerTest.StubJwtDecoderConfig.class)
@Transactional
class MemberImportControllerTest {

    private static final String PREVIEW = "/v1/members/imports/preview";
    private static final String VALIDATION = "/v1/members/imports/validation";

    private static final String HEADER_LINE = "이름,학번,기수,학과,학년,전화번호,이메일,가입일,등급,상태";

    private static final String FULL_MAPPING =
            """
            {"이름":"mbrNm","학번":"stdntNo","기수":"genNo","학과":"scsbjtNm","학년":"scyrNo",\
            "전화번호":"telno","이메일":"eml","가입일":"joinYmd","등급":"mbrGrdCd","상태":"mbrSttsCd"}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository roleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private UUID managerToken;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        grant(saveMember(managerToken, "20260301", "회원관리자"), AuthorityCode.MEMBER_MANAGE);

        // MEMBER_MANAGE가 없는 회원. 권한이 아예 없는 쪽이 아니라 '다른 권한만' 가진 쪽이어야
        // 403이 인증·가입이 아니라 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260302", "업무담당"), AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ 미리보기

    /*
     * 헤더는 파일이 정한 순서 그대로 내려오고, 추천 매핑이 붙고, 미리보기는 앞 5행이다.
     * 총 건수를 함께 내리는 것은 운영자가 "몇 건짜리 파일인가"를 먼저 보게 하기 위해서다.
     */
    @Test
    void previewReturnsHeadersRecommendedMappingAndFirstFiveRows() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER_LINE).append('\n');
        for (int i = 1; i <= 7; i++) {
            csv.append(enrolledRow("회원" + i, "2021000" + i)).append('\n');
        }

        mockMvc.perform(authorized(multipart(PREVIEW).file(csvFile(csv.toString())), managerToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.headers")
                                .value(
                                        contains(
                                                "이름", "학번", "기수", "학과", "학년", "전화번호", "이메일", "가입일",
                                                "등급", "상태")))
                .andExpect(jsonPath("$.data.recommendedMapping['이름']").value("mbrNm"))
                .andExpect(jsonPath("$.data.recommendedMapping['학번']").value("stdntNo"))
                .andExpect(jsonPath("$.data.recommendedMapping['학년']").value("scyrNo"))
                .andExpect(jsonPath("$.data.recommendedMapping['등급']").value("mbrGrdCd"))
                .andExpect(jsonPath("$.data.recommendedMapping['상태']").value("mbrSttsCd"))
                .andExpect(jsonPath("$.data.sampleRows.length()").value(5))
                .andExpect(jsonPath("$.data.totalRowCount").value(7));
    }

    /*
     * 따옴표로 감싼 헤더 안의 쉼표는 구분자가 아니다. 이것이 미리보기를 서버에 둔 이유다 —
     * 웹이 따로 파싱하면 여기서 해석이 갈려 화면의 컬럼과 서버의 컬럼이 어긋난다.
     *
     * 짐작하지 못한 헤더는 빈 문자열로 내려간다. 키 자체를 빼면 위저드가 그 컬럼의 선택 상자를
     * 그리지 못한다.
     */
    @Test
    void previewParsesQuotedHeaderContainingComma() throws Exception {
        String csv = "이름,\"회장,프로젝트장\"\n홍길동,회장\n";

        mockMvc.perform(authorized(multipart(PREVIEW).file(csvFile(csv)), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers").value(contains("이름", "회장,프로젝트장")))
                // 헤더 두 개 모두 추천 매핑에 자리가 있다(짐작하지 못한 쪽은 빈 문자열)
                .andExpect(jsonPath("$.data.recommendedMapping.length()").value(2))
                .andExpect(jsonPath("$.data.recommendedMapping['이름']").value("mbrNm"))
                .andExpect(jsonPath("$.data.sampleRows[0]").value(contains("홍길동", "회장")));
    }

    /** BOM이 붙은 UTF-8도 첫 헤더가 온전해야 한다 — 떼지 않으면 이름 컬럼만 통째로 매핑에서 빠진다 */
    @Test
    void previewStripsByteOrderMark() throws Exception {
        String csv = "﻿" + HEADER_LINE + "\n" + enrolledRow("홍길동", "20211234") + "\n";

        mockMvc.perform(authorized(multipart(PREVIEW).file(csvFile(csv)), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers[0]").value("이름"))
                .andExpect(jsonPath("$.data.recommendedMapping['이름']").value("mbrNm"));
    }

    // ------------------------------------------------------------------ 검증 — 정상

    @Test
    void validatesEveryRowAndSummarizesResult() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + enrolledRow("홍길동", "20211234")
                        + "\n"
                        + enrolledRow("김철수", "20211235")
                        + "\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileToken").value(startsWith("sha256:")))
                .andExpect(jsonPath("$.data.summary.totalCount").value(2))
                .andExpect(jsonPath("$.data.summary.okCount").value(2))
                .andExpect(jsonPath("$.data.summary.errorCount").value(0))
                .andExpect(jsonPath("$.data.summary.duplicateCount").value(0))
                .andExpect(jsonPath("$.data.summary.warningCount").value(0))
                // rowNo는 헤더를 1행으로 센 원본의 줄 번호다
                .andExpect(jsonPath("$.data.rows[0].rowNo").value(2))
                .andExpect(jsonPath("$.data.rows[0].status").value("OK"))
                .andExpect(jsonPath("$.data.rows[0].target").value("홍길동 20211234"))
                .andExpect(jsonPath("$.data.rows[1].rowNo").value(3));
    }

    /** 같은 파일을 두 번 올리면 fileToken이 같다 — 실행 API가 이 값으로 같은 파일임을 확인한다 */
    @Test
    void fileTokenIsStableForIdenticalContent() throws Exception {
        String csv = HEADER_LINE + "\n" + enrolledRow("홍길동", "20211234") + "\n";

        String first = tokenOf(csv);
        assertThat(tokenOf(csv)).isEqualTo(first);
        assertThat(tokenOf(csv.replace("홍길동", "김철수"))).isNotEqualTo(first);
    }

    // ------------------------------------------------------------------ 검증 — 행별 오류

    /*
     * 한 행에서 사유를 모아 돌려준다. 첫 오류에서 멈추면 명부를 고치는 사람이 올릴 때마다
     * 한 칸씩만 알게 된다. 이름이 없는 행의 target은 "(회원명 없음)"이다.
     */
    @Test
    void reportsMissingRequiredValuesWithReasons() throws Exception {
        String csv = HEADER_LINE + "\n" + ",,,,,,,,정회원,재학" + "\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.errorCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].status").value("ERROR"))
                .andExpect(jsonPath("$.data.rows[0].target").value("(회원명 없음)"))
                .andExpect(
                        jsonPath("$.data.rows[0].reasons[*].field")
                                .value(contains("mbrNm", "stdntNo", "scsbjtNm", "scyrNo")));
    }

    @Test
    void reportsAcademicYearOutOfRange() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + "홍길동,20211234,30,컴퓨터학부,5,010-1111-2222,hong@sscc.org,2021-03-02,정회원,재학\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("ERROR"))
                .andExpect(jsonPath("$.data.rows[0].reasons[0].field").value("scyrNo"));
    }

    @Test
    void reportsInvalidJoinDateFormat() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + "홍길동,20211234,30,컴퓨터학부,3,010-1111-2222,hong@sscc.org,2021/03/02,정회원,재학\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("ERROR"))
                .andExpect(jsonPath("$.data.rows[0].reasons[0].field").value("joinYmd"));
    }

    /** 가입일 미입력은 이관일이 되므로 오류가 아니다 */
    @Test
    void blankJoinDateIsNotAnError() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + "홍길동,20211234,30,컴퓨터학부,3,010-1111-2222,hong@sscc.org,,정회원,재학\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("OK"));
    }

    /*
     * 기수 미입력은 0(미배정)이라 오류가 아니며 **학번으로 추정하지 않는다** (BR-M43).
     * 추정한 값은 나중에 사실과 구별되지 않는다.
     */
    @Test
    void blankGenerationNumberIsNotAnError() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + "홍길동,20211234,,컴퓨터학부,3,010-1111-2222,hong@sscc.org,2021-03-02,정회원,재학\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("OK"));
    }

    // ------------------------------------------------------------------ 기준 코드 명칭

    /** 기준 코드 테이블에 없는 명칭은 오류다. 등급·상태 둘 다 같은 자리에서 걸린다 */
    @Test
    void unknownGradeAndStatusNamesAreErrors() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + "홍길동,20211234,30,컴퓨터학부,3,010-1111-2222,hong@sscc.org,2021-03-02,왕회원,재적\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("ERROR"))
                .andExpect(
                        jsonPath("$.data.rows[0].reasons[*].field")
                                .value(contains("mbrSttsCd", "mbrGrdCd")));
    }

    /*
     * **명칭 하드코딩 금지를 못 박는 테스트다.**
     *
     * 기준정보에서 '재학'을 '재적'으로 바꾸면 이관 검증도 따라와야 한다 — 바뀐 이름이 통과하고,
     * 옛 이름이 거절되고, 되돌린 코드(ENROLLED)에 걸린 학적 조건부 필수 규칙까지 그대로
     * 살아 있어야 한다. 명칭이 자바 코드에 박혀 있으면 이 셋 중 어느 하나는 반드시 어긋난다.
     */
    @Test
    void referenceCodeRenameIsFollowedByValidation() throws Exception {
        MemberStatusEntity enrolled =
                memberStatusRepository.findById(MemberStatusCode.ENROLLED.code()).orElseThrow();
        enrolled.update("재적", enrolled.getDisplayOrder());
        entityManager.flush();
        entityManager.clear();

        String csv =
                HEADER_LINE
                        + "\n"
                        // 바뀐 이름 + 학적 필수 충족 → 통과
                        + "홍길동,20211234,30,컴퓨터학부,3,010-1111-2222,a@sscc.org,2021-03-02,정회원,재적\n"
                        // 바뀐 이름 + 학과·학년 누락 → ENROLLED의 학적 규칙이 그대로 걸린다
                        + "김철수,20211235,30,,,010-1111-3333,b@sscc.org,2021-03-02,정회원,재적\n"
                        // 옛 이름은 이제 기준 코드에 없다
                        + "이영희,20211236,30,컴퓨터학부,3,010-1111-4444,c@sscc.org,2021-03-02,정회원,재학\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("OK"))
                .andExpect(jsonPath("$.data.rows[1].status").value("ERROR"))
                .andExpect(
                        jsonPath("$.data.rows[1].reasons[*].field")
                                .value(contains("scsbjtNm", "scyrNo")))
                .andExpect(jsonPath("$.data.rows[2].status").value("ERROR"))
                .andExpect(jsonPath("$.data.rows[2].reasons[0].field").value("mbrSttsCd"));
    }

    // ------------------------------------------------------------------ 학적 조건부 필수

    /** 재학 회원은 학번·학과·학년이 모두 필수다 (가입 규칙과 같은 AcademicProfilePolicy) */
    @Test
    void enrolledMemberMissingAcademicFieldsIsError() throws Exception {
        String csv = HEADER_LINE + "\n" + "홍길동,,30,,,010-1111-2222,a@sscc.org,2021-03-02,정회원,재학\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("ERROR"))
                .andExpect(
                        jsonPath("$.data.rows[0].reasons[*].field")
                                .value(contains("stdntNo", "scsbjtNm", "scyrNo")));
    }

    /*
     * 졸업 회원은 학번이 없어도 통과한다 — 학번이 기억나지 않는 졸업생이 가입 화면으로도 들어올
     * 수 있으므로(#21), 이관만 더 엄격하면 안 된다. mbr.stdnt_no가 nullable인 근거다.
     */
    @Test
    void graduatedMemberWithoutStudentNumberPasses() throws Exception {
        String csv = HEADER_LINE + "\n" + "홍길동,,30,,,010-1111-2222,a@sscc.org,2010-03-02,정회원,졸업\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("OK"))
                .andExpect(jsonPath("$.data.summary.okCount").value(1));
    }

    // ------------------------------------------------------------------ 중복

    /** mbr에 이미 있는 학번. setUp의 회원관리자가 쓰고 있는 20260301이다 */
    @Test
    void studentNumberAlreadyInDatabaseIsDuplicate() throws Exception {
        String csv = HEADER_LINE + "\n" + enrolledRow("홍길동", "20260301") + "\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.duplicateCount").value(1))
                .andExpect(jsonPath("$.data.summary.okCount").value(0))
                .andExpect(jsonPath("$.data.rows[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.rows[0].reasons[0].field").value("stdntNo"))
                .andExpect(jsonPath("$.data.rows[0].reasons[0].message").value("이미 등록된 학번"));
    }

    /*
     * 같은 파일 안에서 겹치는 학번은 **두 행 모두** 중복이다. 앞의 것을 통과시키면 어느 쪽이 맞는
     * 행인지를 서버가 고른 셈이 된다 — 자동 병합은 없고 판단은 운영자가 한다(BR-M40).
     */
    @Test
    void studentNumberRepeatedWithinFileMarksBothRows() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + enrolledRow("홍길동", "20211234")
                        + "\n"
                        + enrolledRow("김철수", "20211235")
                        + "\n"
                        + enrolledRow("홍길동", "20211234")
                        + "\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.duplicateCount").value(2))
                .andExpect(jsonPath("$.data.summary.okCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.rows[1].status").value("OK"))
                .andExpect(jsonPath("$.data.rows[2].status").value("DUPLICATE"));
    }

    /** 학번이 빈 행끼리는 겹치지 않는다 — 미입력은 빈 문자열이 아니라 NULL이기 때문이다 */
    @Test
    void blankStudentNumbersDoNotCollide() throws Exception {
        String csv =
                HEADER_LINE
                        + "\n"
                        + "홍길동,,30,,,010-1111-2222,a@sscc.org,2010-03-02,정회원,졸업\n"
                        + "김철수,,30,,,010-1111-3333,b@sscc.org,2011-03-02,정회원,졸업\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.duplicateCount").value(0))
                .andExpect(jsonPath("$.data.summary.okCount").value(2));
    }

    // ------------------------------------------------------------------ 경고

    /*
     * 연락처가 빈 행은 **오류가 아니라 경고**이고 okCount에 그대로 들어간다. 데이터사전이 telno에
     * NULL을 허용하므로 이관을 막을 근거가 없다 — 다만 계정 연결이 A안(ssccops#78)이라 그 회원은
     * 나중에 스스로 연결하지 못하므로, 운영자가 이관 전에 채울 기회를 준다.
     */
    @Test
    void missingPhoneNumberIsWarningAndStillCountedAsOk() throws Exception {
        String csv =
                HEADER_LINE + "\n" + "홍길동,20211234,30,컴퓨터학부,3,,hong@sscc.org,2021-03-02,정회원,재학\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.okCount").value(1))
                .andExpect(jsonPath("$.data.summary.errorCount").value(0))
                .andExpect(jsonPath("$.data.summary.warningCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].status").value("OK"))
                .andExpect(jsonPath("$.data.rows[0].reasons").isEmpty())
                .andExpect(jsonPath("$.data.rows[0].warnings[0].field").value("telno"));
    }

    // ------------------------------------------------------------------ 아무것도 쓰지 않는다

    /*
     * 검증 뒤 mbr 행 수가 그대로다. 이 API가 "확인만 한다"는 계약 자체이며, 실제 등록은 별도
     * 엔드포인트(#85)의 몫이다.
     */
    @Test
    void validationDoesNotInsertAnyMember() throws Exception {
        long before = memberRepository.count();
        String csv =
                HEADER_LINE
                        + "\n"
                        + enrolledRow("홍길동", "20211234")
                        + "\n"
                        + enrolledRow("김철수", "20211235")
                        + "\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.okCount").value(2));

        entityManager.flush();
        entityManager.clear();
        assertThat(memberRepository.count()).isEqualTo(before);
    }

    /** 읽기 전용임을 애노테이션으로도 못 박는다 — 나중에 저장이 끼어들면 여기서 먼저 걸린다 */
    @Test
    void validationRunsInReadOnlyTransaction() throws Exception {
        Transactional annotation =
                MemberImportServiceImpl.class
                        .getMethod("validate", MultipartFile.class, String.class)
                        .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isTrue();
    }

    // ------------------------------------------------------------------ 파일·매핑 거절

    /** 5MB를 넘는 파일은 파싱 이전에 끊는다 */
    @Test
    void rejectsFileLargerThanLimit() throws Exception {
        MockMultipartFile tooLarge =
                new MockMultipartFile(
                        "file", "members.csv", "text/csv", new byte[5 * 1024 * 1024 + 1]);

        mockMvc.perform(authorized(multipart(PREVIEW).file(tooLarge), managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CSV_FILE"));
    }

    /** CSV가 아닌 파일. 확장자와 콘텐츠 타입 어느 쪽으로도 CSV라고 볼 수 없을 때만 걸린다 */
    @Test
    void rejectsNonCsvFile() throws Exception {
        MockMultipartFile notCsv =
                new MockMultipartFile(
                        "file",
                        "members.txt",
                        "text/plain",
                        "이름\n홍길동\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(authorized(multipart(PREVIEW).file(notCsv), managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CSV_FILE"));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(authorized(multipart(PREVIEW).file(csvFile("")), managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_CSV_FILE"));
    }

    /** 헤더만 있고 데이터 행이 없는 파일도 빈 파일이다 */
    @Test
    void rejectsHeaderOnlyFile() throws Exception {
        mockMvc.perform(
                        authorized(
                                multipart(PREVIEW).file(csvFile(HEADER_LINE + "\n")), managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_CSV_FILE"));
    }

    /*
     * 필수 필드가 매핑되지 않으면 행별 오류가 아니라 요청 전체를 거절한다 — 매핑이 어긋나면 모든
     * 행이 같은 이유로 틀려, 128건짜리 오류 목록만 남고 무엇을 고쳐야 하는지는 어디에도 없다.
     */
    @Test
    void rejectsMappingWithoutRequiredField() throws Exception {
        String csv = HEADER_LINE + "\n" + enrolledRow("홍길동", "20211234") + "\n";
        String mappingWithoutName = "{\"등급\":\"mbrGrdCd\",\"상태\":\"mbrSttsCd\"}";

        mockMvc.perform(validationRequest(csv, mappingWithoutName, managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_MAPPING_INVALID"));
    }

    /** 파일에 없는 헤더를 가리키는 매핑. 다른 파일로 만든 매핑을 그대로 보낸 경우다 */
    @Test
    void rejectsMappingPointingAtUnknownHeader() throws Exception {
        String csv = HEADER_LINE + "\n" + enrolledRow("홍길동", "20211234") + "\n";
        String mapping = "{\"성명\":\"mbrNm\",\"등급\":\"mbrGrdCd\",\"상태\":\"mbrSttsCd\"}";

        mockMvc.perform(validationRequest(csv, mapping, managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_MAPPING_INVALID"));
    }

    /** 한 필드에 두 컬럼을 매핑하면 어느 쪽을 쓸지 서버가 고를 근거가 없다 */
    @Test
    void rejectsMappingWithTwoColumnsForOneField() throws Exception {
        String csv = "이름,회원명,등급,상태\n홍길동,홍길동,정회원,졸업\n";
        String mapping =
                "{\"이름\":\"mbrNm\",\"회원명\":\"mbrNm\",\"등급\":\"mbrGrdCd\",\"상태\":\"mbrSttsCd\"}";

        mockMvc.perform(validationRequest(csv, mapping, managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_MAPPING_INVALID"));
    }

    // ------------------------------------------------------------------ 인가

    @Test
    void previewWithoutMemberManageIsForbidden() throws Exception {
        String csv = HEADER_LINE + "\n" + enrolledRow("홍길동", "20211234") + "\n";

        mockMvc.perform(authorized(multipart(PREVIEW).file(csvFile(csv)), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void validationWithoutMemberManageIsForbidden() throws Exception {
        String csv = HEADER_LINE + "\n" + enrolledRow("홍길동", "20211234") + "\n";

        mockMvc.perform(validationRequest(csv, FULL_MAPPING, outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private String tokenOf(String csv) throws Exception {
        String body =
                mockMvc.perform(validationRequest(csv, FULL_MAPPING, managerToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        int start = body.indexOf("sha256:");
        return body.substring(start, body.indexOf('"', start));
    }

    private MockMultipartHttpServletRequestBuilder validationRequest(
            String csv, String mapping, UUID token) {

        MockMultipartHttpServletRequestBuilder builder = multipart(VALIDATION).file(csvFile(csv));
        builder.param("mapping", mapping);
        return authorized(builder, token);
    }

    private static MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "members.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartHttpServletRequestBuilder authorized(
            MockMultipartHttpServletRequestBuilder builder, UUID authUserId) {
        builder.header("Authorization", "Bearer " + authUserId);
        return builder;
    }

    private static String enrolledRow(String name, String studentNumber) {
        return "%s,%s,30,컴퓨터학부,3,010-1111-2222,%s@sscc.org,2021-03-02,정회원,재학"
                .formatted(name, studentNumber, studentNumber);
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
