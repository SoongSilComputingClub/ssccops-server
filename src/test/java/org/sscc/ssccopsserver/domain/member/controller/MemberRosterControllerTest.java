package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * 회원명부 내려받기 (#674 · 상위 ssccops#598).
 *
 * 확인의 중심은 **누가 들어가고 직책이 무엇으로 적히는가**다 — 회장·부회장은 상태·등급과 무관하게
 * 항상 · 나머지는 고른 상태(기본 재학)이면서 임시회원이 아닌 회원 · 직책은 회장·부회장 외 전원
 * «정회원»(역할이 있어도 — #678). 그리고 거절은 파일이 아니라 상태 코드 + 봉투로 나가야 한다(회장 없음 409).
 *
 * 응답 파일을 POI로 다시 열어 행을 읽는다. 공용 testdb에는 다른 클래스가 커밋한 회원이 있을 수 있어
 * 행 수를 세지 않고 **이 클래스가 만든 회원끼리의 포함·순서**만 본다.
 *
 * 서비스가 예외를 던지면 참여 중인 테스트 트랜잭션이 rollback-only로 표시되므로, 400·403·409를 보는
 * 테스트는 실패하는 요청 하나로 끝낸다 (MemberImportControllerTest와 같은 이유).
 *
 * 미리보기(#676)도 같은 이유로 인원을 절대값으로 보지 않는다 — 같은 조건으로 내려받은 파일과
 * 맞는지, 그리고 회원을 더하거나 회장 배정을 끝낸 **전후의 차이**가 맞는 이유로 잡히는지를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class MemberRosterControllerTest {

    private static final String EXPORT = "/v1/members/roster-export";
    private static final String PREVIEW = EXPORT + "/preview";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;
    @Autowired private Clock clock;
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
    private MemberRoleAssignmentEntity presidentAssignment;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        MemberEntity manager =
                member("명부관리", "20259001", MemberGradeCode.FULL, MemberStatusCode.ENROLLED);
        manager.assignAuthUserId(managerToken);
        grant(manager, AuthorityCode.MEMBER_MANAGE);

        // MEMBER_MANAGE가 없는 회원 — '다른 권한만' 가져야 403이 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider =
                member("업무담당", "20259002", MemberGradeCode.FULL, MemberStatusCode.ENROLLED);
        outsider.assignAuthUserId(outsiderToken);
        grant(outsider, AuthorityCode.WORK_MANAGE);

        // 회장은 휴학 중인 임시회원이다 — 상태·등급 둘 다 걸러질 자리지만 회장이라 들어가야 한다
        presidentAssignment =
                assign(
                        member("김회장", "20259101", MemberGradeCode.TEMP, MemberStatusCode.LEAVE),
                        "회장",
                        true);
        // 부회장의 대표 역할은 국장이다 — 대표 역할이 아니라 부회장으로 적혀야 한다
        MemberEntity vice =
                member("이부회", "20259102", MemberGradeCode.ACTIVE, MemberStatusCode.ENROLLED);
        assign(vice, "부회장", false);
        assign(vice, "국장", true);

        // 학번 순서와 이름 순서를 엇갈려 둔다 — 정렬 기준이 학번임이 드러나게
        assign(
                member("가정회", "20259204", MemberGradeCode.FULL, MemberStatusCode.ENROLLED),
                "국장",
                true);
        assign(
                member("나준회", "20259203", MemberGradeCode.ASSOC, MemberStatusCode.ENROLLED),
                "국원",
                false);
        member("다활동", "20259202", MemberGradeCode.ACTIVE, MemberStatusCode.ENROLLED);

        member("라임시", "20259201", MemberGradeCode.TEMP, MemberStatusCode.ENROLLED);
        member("마휴학", "20259301", MemberGradeCode.ASSOC, MemberStatusCode.LEAVE);
        member("바임휴", "20259302", MemberGradeCode.TEMP, MemberStatusCode.LEAVE);
        member("사졸업", "20259303", MemberGradeCode.FULL, MemberStatusCode.GRADUATED);
        entityManager.flush();
    }

    // ------------------------------------------------------------------ 기본 = 연합회 제출용

    @Test
    void defaultExportIsTheFederationSubmission() throws Exception {
        byte[] file =
                mockMvc.perform(
                                authorized(
                                        get(EXPORT).param("year", "2026").param("semester", "2")))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(XLSX))
                        .andExpect(
                                header().string(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                containsString(
                                                        "filename*=UTF-8''"
                                                                + encoded(
                                                                        "2026년도_학술분과_SSCC_2학기_동아리회원명부.xlsx"))))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("2026년도 2학기 SSCC 회원명부(재학생)");

            Map<String, String> positions = positionsByName(sheet);
            // 회장 → 부회장이 맨 위다
            assertThat(new ArrayList<>(positions.keySet()).subList(0, 2))
                    .containsExactly("김회장", "이부회");
            assertThat(mine(positions))
                    .containsExactly(
                            Map.entry("김회장", "회장"),
                            Map.entry("이부회", "부회장"),
                            Map.entry("다활동", "정회원"),
                            Map.entry("나준회", "정회원"),
                            Map.entry("가정회", "정회원"));
        }
    }

    /*
     * 웹은 교차 출처라 CORS가 노출하지 않은 헤더를 읽지 못한다. Content-Disposition이 빠지면 파일
     * 이름을 화면이 따로 지어야 하고 그 순간 규칙이 두 벌이 된다 — 다른 테스트는 전부 통과한 채로
     * 웹에서만 깨지는 자리라 여기서 못 박는다 (SecurityConfig.corsConfigurationSource).
     */
    @Test
    void exposesContentDispositionToTheWebOrigin() throws Exception {
        mockMvc.perform(
                        authorized(
                                get(EXPORT)
                                        .param("year", "2026")
                                        .param("semester", "2")
                                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                                        containsString(HttpHeaders.CONTENT_DISPOSITION)));
    }

    /* 상태를 넓혀도 임시회원은 들어가지 않고, 회장은 휴학 상태를 골라도 한 번만 나온다 */
    @Test
    void includesChosenStatusesButNeverTemporaryMembers() throws Exception {
        Map<String, String> positions =
                export(
                        get(EXPORT)
                                .param("year", "2026")
                                .param("semester", "2")
                                .param("mbrSttsCd", "ENROLLED", "LEAVE"));

        assertThat(mine(positions))
                .containsExactly(
                        Map.entry("김회장", "회장"),
                        Map.entry("이부회", "부회장"),
                        Map.entry("다활동", "정회원"),
                        Map.entry("나준회", "정회원"),
                        Map.entry("가정회", "정회원"),
                        Map.entry("마휴학", "정회원"));
    }

    /*
     * 직책 표기법 옵션은 걷어냈다(#678). 웹이 따라오기 전의 옛 화면이 SSCC 표기법을 실어 보내도
     * 거절하지 않고 무시한다 — 대표 역할이 국장인 가정회도 «정회원»이다.
     */
    @Test
    void ignoresTheRemovedPositionNotation() throws Exception {
        Map<String, String> positions =
                export(
                        get(EXPORT)
                                .param("year", "2026")
                                .param("semester", "1")
                                .param("positionNotation", "SSCC"));

        assertThat(mine(positions))
                .containsExactly(
                        Map.entry("김회장", "회장"),
                        Map.entry("이부회", "부회장"),
                        Map.entry("다활동", "정회원"),
                        Map.entry("나준회", "정회원"),
                        Map.entry("가정회", "정회원"));
    }

    // ------------------------------------------------------------------ 제목의 괄호 = 고른 상태

    /*
     * 상태를 넓히면 괄호가 고른 상태 이름이 된다 — 휴학·졸업을 넣은 명부가 «(재학생)»으로 나가지 않게.
     * 요청 순서를 뒤집어 보내도 표시 순번(mbr_stts.indct_seqno)대로 적힌다.
     */
    @Test
    void titleNamesTheChosenStatuses() throws Exception {
        assertThat(
                        title(
                                get(EXPORT)
                                        .param("year", "2026")
                                        .param("semester", "2")
                                        .param("mbrSttsCd", "LEAVE", "ENROLLED")))
                .isEqualTo("2026년도 2학기 SSCC 회원명부(재학·일반휴학)");
    }

    /* 상태를 전부 고르면 괄호를 뺀다 — 여섯 이름을 늘어놓는 것보다 «거르지 않았다»가 정확하다 */
    @Test
    void titleDropsTheLabelWhenEveryStatusIsChosen() throws Exception {
        String[] every =
                memberStatusRepository.findAll().stream()
                        .map(MemberStatusEntity::getCode)
                        .toArray(String[]::new);

        assertThat(
                        title(
                                get(EXPORT)
                                        .param("year", "2026")
                                        .param("semester", "1")
                                        .param("mbrSttsCd", every)))
                .isEqualTo("2026년도 1학기 SSCC 회원명부");
    }

    // ------------------------------------------------------------------ 미리보기

    /* 미리보기의 제목·파일 이름·줄 수는 같은 조건으로 내려받은 파일 그대로다 */
    @Test
    void previewMatchesTheExportedFile() throws Exception {
        JsonNode preview =
                preview(
                        get(PREVIEW)
                                .param("year", "2026")
                                .param("semester", "2")
                                .param("mbrSttsCd", "ENROLLED", "LEAVE"));

        byte[] file =
                mockMvc.perform(
                                authorized(
                                        get(EXPORT)
                                                .param("year", "2026")
                                                .param("semester", "2")
                                                .param("mbrSttsCd", "ENROLLED", "LEAVE")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            List<String> positions = positionColumn(sheet);

            assertThat(preview.get("title").asText())
                    .isEqualTo(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("2026년도 2학기 SSCC 회원명부(재학·일반휴학)");
            // 명단은 연도·학기와 무관하게 오늘 기준이다 — 화면이 이 날짜를 그대로 보인다
            assertThat(preview.get("baseDate").asText()).isEqualTo(LocalDate.now(clock).toString());
            assertThat(preview.get("fileName").asText())
                    .isEqualTo("2026년도_학술분과_SSCC_2학기_동아리회원명부.xlsx");
            assertThat(preview.get("rowCount").asLong()).isEqualTo(positions.size());
            assertThat(preview.get("officerCount").asLong())
                    .isEqualTo(
                            positions.stream()
                                    .filter(position -> List.of("회장", "부회장").contains(position))
                                    .count());
        }
        assertThat(preview.get("totalMemberCount").asLong()).isEqualTo(memberRepository.count());
        assertThat(preview.get("excludedByStatusCount").asLong()).isNotNegative();
        assertThat(preview.get("presidentMissing").asBoolean()).isFalse();
    }

    /*
     * 빠진 회원은 이유별로 센다. 임시회원은 상태와 무관하게 «임시회원» 쪽이고(고르지 않은 상태여도),
     * 임시회원이 아닌데 고르지 않은 상태면 «상태» 쪽이다. 명부 줄 수는 그대로다.
     */
    @Test
    void previewCountsExcludedMembersByReason() throws Exception {
        JsonNode before = preview(get(PREVIEW).param("year", "2026").param("semester", "2"));

        member("아임시", "20259401", MemberGradeCode.TEMP, MemberStatusCode.ENROLLED);
        member("자임졸", "20259402", MemberGradeCode.TEMP, MemberStatusCode.GRADUATED);
        member("차군휴", "20259403", MemberGradeCode.FULL, MemberStatusCode.MIL_LEAVE);
        entityManager.flush();
        JsonNode after = preview(get(PREVIEW).param("year", "2026").param("semester", "2"));

        assertThat(delta(before, after, "excludedTemporaryCount")).isEqualTo(2);
        assertThat(delta(before, after, "excludedByStatusCount")).isEqualTo(1);
        assertThat(delta(before, after, "rowCount")).isZero();
        assertThat(delta(before, after, "totalMemberCount")).isEqualTo(3);
        assertThat(after.get("title").asText()).isEqualTo("2026년도 2학기 SSCC 회원명부(재학생)");
    }

    /*
     * 회장이 없으면 내려받기는 409지만 미리보기는 200으로 숫자를 내고 presidentMissing으로 알린다.
     * 회장이던 김회장은 휴학 중인 임시회원이라, 배정이 끝나면 명부에서 빠져 «임시회원» 쪽으로 옮겨 간다 —
     * 회장일 때는 임시회원이어도 빠진 사람으로 세지 않았다는 뜻이다.
     */
    @Test
    void previewReportsAMissingPresidentInsteadOfRejecting() throws Exception {
        JsonNode before = preview(get(PREVIEW).param("year", "2026").param("semester", "2"));

        presidentAssignment.end(LocalDate.now(clock).minusDays(1));
        entityManager.flush();
        JsonNode after = preview(get(PREVIEW).param("year", "2026").param("semester", "2"));

        assertThat(before.get("presidentMissing").asBoolean()).isFalse();
        assertThat(after.get("presidentMissing").asBoolean()).isTrue();
        assertThat(delta(before, after, "officerCount")).isEqualTo(-1);
        assertThat(delta(before, after, "rowCount")).isEqualTo(-1);
        assertThat(delta(before, after, "excludedTemporaryCount")).isEqualTo(1);
    }

    /* 조건 검증은 내려받기와 같다 — 응답은 봉투다 */
    @Test
    void previewRejectsUnknownStatusCode() throws Exception {
        mockMvc.perform(
                        authorized(
                                get(PREVIEW)
                                        .param("year", "2026")
                                        .param("semester", "2")
                                        .param("mbrSttsCd", "SABBATICAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void previewRequiresMemberManage() throws Exception {
        mockMvc.perform(
                        get(PREVIEW)
                                .param("year", "2026")
                                .param("semester", "2")
                                .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 거절 — 상태 코드 + 봉투

    /* 회장 배정이 끝났으면 옵션과 무관하게 409이고, 응답은 파일이 아니라 봉투다 */
    @Test
    void rejectsWhenNoPresidentIsAssigned() throws Exception {
        presidentAssignment.end(LocalDate.now(clock).minusDays(1));
        entityManager.flush();

        mockMvc.perform(
                        authorized(
                                get(EXPORT)
                                        .param("year", "2026")
                                        .param("semester", "2")
                                        .param("mbrSttsCd", "ENROLLED", "LEAVE")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ROSTER_PRESIDENT_MISSING"));
    }

    @Test
    void requiresMemberManage() throws Exception {
        mockMvc.perform(
                        get(EXPORT)
                                .param("year", "2026")
                                .param("semester", "2")
                                .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* 화면의 선택지는 mbr_stts에서 오므로 거기 없는 코드는 기준 코드 위반이다 */
    @Test
    void rejectsUnknownStatusCode() throws Exception {
        mockMvc.perform(
                        authorized(
                                get(EXPORT)
                                        .param("year", "2026")
                                        .param("semester", "2")
                                        .param("mbrSttsCd", "ENROLLED", "SABBATICAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"))
                .andExpect(jsonPath("$.message").value(containsString("SABBATICAL")));
    }

    @Test
    void requiresYear() throws Exception {
        mockMvc.perform(authorized(get(EXPORT).param("semester", "2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsSemesterOutOfRange() throws Exception {
        mockMvc.perform(authorized(get(EXPORT).param("year", "2026").param("semester", "3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ helpers

    private String title(MockHttpServletRequestBuilder request) throws Exception {
        byte[] file =
                mockMvc.perform(authorized(request))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            return workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue();
        }
    }

    /* 미리보기 응답의 data */
    private JsonNode preview(MockHttpServletRequestBuilder request) throws Exception {
        String body =
                mockMvc.perform(authorized(request))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    private static long delta(JsonNode before, JsonNode after, String field) {
        return after.get(field).asLong() - before.get(field).asLong();
    }

    /*
     * 3행부터 이름이 빌 때까지의 직책 열. positionsByName과 달리 이름으로 접지 않는다 — 줄 수를 세는
     * 자리라 공용 testdb에 같은 이름의 회원이 있어도 한 줄씩 센다.
     */
    private static List<String> positionColumn(XSSFSheet sheet) {
        List<String> positions = new ArrayList<>();
        for (int r = 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || text(row.getCell(1)).isEmpty()) {
                break;
            }
            positions.add(text(row.getCell(0)));
        }
        return positions;
    }

    private Map<String, String> export(MockHttpServletRequestBuilder request) throws Exception {
        byte[] file =
                mockMvc.perform(authorized(request))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            return positionsByName(workbook.getSheetAt(0));
        }
    }

    /* 3행부터 이름이 빌 때까지 이름 → 직책. 순서를 지킨다 */
    private static Map<String, String> positionsByName(XSSFSheet sheet) {
        Map<String, String> positions = new LinkedHashMap<>();
        for (int r = 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String name = row == null ? "" : text(row.getCell(1));
            if (name.isEmpty()) {
                break;
            }
            positions.put(name, text(row.getCell(0)));
        }
        return positions;
    }

    /*
     * 공용 testdb의 다른 회원은 빼고 이 클래스가 명부 판정용으로 만든 회원만, 명부 순서 그대로.
     * 요청자 둘(명부관리·업무담당)은 신원일 뿐이라 뺀다.
     */
    private static List<Map.Entry<String, String>> mine(Map<String, String> positions) {
        List<String> names = List.of("김회장", "이부회", "가정회", "나준회", "다활동", "라임시", "마휴학", "바임휴", "사졸업");
        return positions.entrySet().stream()
                .filter(entry -> names.contains(entry.getKey()))
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String text(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        return cell.getStringCellValue();
    }

    private static String encoded(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + managerToken);
    }

    private MemberEntity member(
            String name, String studentNumber, MemberGradeCode grade, MemberStatusCode status) {
        return memberRepository.save(
                MemberEntity.create(
                        studentNumber,
                        40,
                        name,
                        "컴퓨터학부",
                        2,
                        "01012345678",
                        null,
                        memberGradeRepository.findById(grade.code()).orElseThrow(),
                        memberStatusRepository.findById(status.code()).orElseThrow(),
                        LocalDate.now(clock),
                        null,
                        null));
    }

    /* 시드(V3)의 직책 역할을 이름으로 찾아 배정한다. 시작일은 넉넉히 과거다 */
    private MemberRoleAssignmentEntity assign(
            MemberEntity member, String roleName, boolean representative) {
        MemberRoleEntity role =
                memberRoleRepository.findAll().stream()
                        .filter(candidate -> roleName.equals(candidate.getName()))
                        .filter(
                                candidate ->
                                        "POSITION"
                                                .equals(
                                                        candidate
                                                                .getRoleClassification()
                                                                .getCode()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("시드에 없는 역할: " + roleName));
        return memberRoleAssignmentRepository.save(
                MemberRoleAssignmentEntity.create(
                        member, role, LocalDate.now(clock).minusYears(1), representative));
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
}
