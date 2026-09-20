package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * V20 — 라이브 기획안 폼의 유형 선택지에 «트랙»을 더하는 마이그레이션 (#512).
 *
 * **이 레포에서 qitem_cpst_cn(JSONB)을 SQL로 고치는 첫 자리라 테스트가 있다.** 대상이 운영의
 * 접수중인 폼 하나뿐이고, SQL이 틀리면 고쳐지지 않는 것이 아니라 **문항 구성이 깨진다** —
 * jsonb_agg가 순서를 뒤집거나 programType 아닌 문항까지 건드리면 폼이 통째로 다르게 그려진다.
 * 그래서 «선택지가 늘었다»만이 아니라 «나머지가 그대로다»를 함께 본다.
 *
 * FlywayMigrationValidateTest와 달리 스프링 컨텍스트를 쓰지 않는다 — 여기서 필요한 것은
 * «V19까지 적용된 DB에 폼을 하나 심고 V20만 돌린다»는 통제된 순서이고, 그것은 Flyway를 직접
 * 부르는 쪽이 정확하다. 컨텍스트를 띄우면 시더가 폼을 만들어 버려 전제가 흐려진다.
 *
 * 스키마는 테스트마다 통째로 다시 만든다. Flyway의 schemas 설정으로 가르는 길은 V1 baseline이
 * 테이블 이름을 "public"."..."로 못 박고 있어 성립하지 않는다.
 */
@Testcontainers
class ProposalFormTrackOptionMigrationTest {

    private static final String PROGRAM_TYPE_QITEM_ID = "programType";

    /* V19까지 = «트랙 유형은 있는데 폼 선택지에는 없는» 상태 (#510 배포 직후의 dev·prod) */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("19");

    private static final MigrationVersion AFTER = MigrationVersion.fromVersion("20");

    /* pgvector 이미지인 이유는 FlywayMigrationValidateTest의 주석에 있다 (V10의 vector 확장) */
    @SuppressWarnings("resource") // 컨테이너 수명은 Testcontainers의 ryuk이 관리한다
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg17")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ssccops_v20_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetSchema() {
        execute("DROP SCHEMA IF EXISTS public CASCADE", "CREATE SCHEMA public");
    }

    /* 본 경로 — 선택지 둘짜리 폼에 트랙이 붙고, 버전이 오르고, 이력이 남는다 */
    @Test
    void addsTrackToTheLiveProposalFormAndRecordsAVersion() {
        migrateTo(BEFORE);
        long formId = insertProposalForm("[\"스터디\", \"프로젝트\"]");

        migrateTo(AFTER);

        assertThat(optionsOf(formId))
                .as("기준정보(acdm_actv_type)의 이름을 그대로 붙인다")
                .containsExactly("스터디", "프로젝트", "트랙");
        assertThat(questionVersionOf(formId)).as("문항 버전이 오른다").isEqualTo(2);

        // 이력 한 줄 — 변경자는 NULL이다(사람이 하지 않은 변경이다)
        assertThat(query("SELECT qitem_ver FROM form_qitem_hstry WHERE form_id = " + formId))
                .containsExactly("2");
        assertThat(
                        query(
                                "SELECT count(*) FROM form_qitem_hstry"
                                        + " WHERE chnrg_mbr_id IS NULL AND form_id = "
                                        + formId))
                .as("변경자는 NULL이다 — 아무 회원의 이름으로도 적지 않는다")
                .containsExactly("1");
    }

    /*
     * **나머지 문항이 그대로다.** 배열을 통째로 다시 쌓는 UPDATE라, programType 아닌 문항이
     * 바뀌거나 순서가 뒤집히는 것이 이 SQL의 현실적인 실패 모양이다.
     *
     * ⚠️ 이 테스트가 **순서 보장을 증명하지는 못한다** — V20의 `ORDER BY e.ord`를 지우고 돌려
     * 봤는데 그대로 통과했다(PostgreSQL이 이 크기에서는 입력 순서를 지킨다). 그 줄은 실행
     * 계획이 바뀌어도 성립해야 해서 두는 방어이고, 근거는 테스트가 아니라 그 자리의 주석이다.
     */
    @Test
    void leavesEveryOtherQuestionUntouched() {
        migrateTo(BEFORE);
        long formId = insertProposalForm("[\"스터디\", \"프로젝트\"]");
        String before = compositionOf(formId);

        migrateTo(AFTER);

        JsonNode beforeItems = readTree(before).get("qitems");
        JsonNode afterItems = readTree(compositionOf(formId)).get("qitems");

        assertThat(afterItems.size()).isEqualTo(beforeItems.size());
        for (int i = 0; i < beforeItems.size(); i++) {
            JsonNode was = beforeItems.get(i);
            JsonNode now = afterItems.get(i);
            assertThat(now.get("qitemId").asText())
                    .as("문항 순서가 그대로여야 한다 (%d번째)", i)
                    .isEqualTo(was.get("qitemId").asText());
            if (!PROGRAM_TYPE_QITEM_ID.equals(was.get("qitemId").asText())) {
                assertThat(now).as("%s 문항은 손대지 않는다", was.get("qitemId").asText()).isEqualTo(was);
            }
        }
        // 유형 문항도 선택지 말고는 그대로다
        JsonNode programType = afterItems.get(0);
        assertThat(programType.get("qitemLblNm").asText()).isEqualTo("유형");
        assertThat(programType.get("reqYn").asBoolean()).isTrue();
    }

    /*
     * **이미 트랙이 들어 있는 환경은 건드리지 않는다.** 운영진이 잠금 이전에 먼저 더해 둔 환경이
     * 있을 수 있고, 그쪽에서 버전만 올리면 «아무것도 바뀌지 않은 개정»이 이력에 쌓인다.
     */
    @Test
    void doesNothingWhenTheOptionIsAlreadyThere() {
        migrateTo(BEFORE);
        long formId = insertProposalForm("[\"스터디\", \"프로젝트\", \"트랙\"]");

        migrateTo(AFTER);

        assertThat(optionsOf(formId)).containsExactly("스터디", "프로젝트", "트랙");
        assertThat(questionVersionOf(formId)).as("버전은 그대로다").isEqualTo(1);
        assertThat(query("SELECT count(*) FROM form_qitem_hstry WHERE form_id = " + formId))
                .as("이력도 남기지 않는다")
                .containsExactly("0");
    }

    /* 폼이 아직 없는 환경(새 배포·CI)에서 조용히 지나간다 — 시더가 셋으로 세운다 */
    @Test
    void passesThroughWhenTheFormDoesNotExistYet() {
        migrateTo(BEFORE);

        migrateTo(AFTER);

        assertThat(query("SELECT count(*) FROM \"form\"")).containsExactly("0");
    }

    // ------------------------------------------------------------------ 헬퍼

    private static void migrateTo(MigrationVersion version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    /*
     * 기획안 폼 한 장. 문항 구성은 실제 시드(ProposalFormSeed)의 모양을 줄인 것이며 유형 문항이
     * 맨 앞이고 뒤에 다른 문항이 따라온다 — 그 뒤엣것들이 그대로인지가 이 테스트의 절반이다.
     */
    private static long insertProposalForm(String optionListJson) {
        String composition =
                """
                {"pages": [{"pageTtlNm": "기획안", "pageDescCn": "설명"}],
                 "qitems": [
                   {"qitemId": "programType", "qitemLblNm": "유형", "qitemTypeCd": "SINGLE_CHOICE",
                    "reqYn": true, "pageSeq": 0, "optionList": %s},
                   {"qitemId": "programTitle", "qitemLblNm": "활동명", "qitemTypeCd": "SHORT_TEXT",
                    "reqYn": true, "pageSeq": 0, "optionList": []},
                   {"qitemId": "curriculum", "qitemLblNm": "커리큘럼", "qitemTypeCd": "LONG_TEXT",
                    "reqYn": true, "pageSeq": 0, "optionList": []}
                 ]}
                """
                        .formatted(optionListJson);

        execute(
                """
                INSERT INTO mbr (gen_no, sys_join_ymd, mbr_nm, mbr_grd_cd, mbr_stts_cd)
                VALUES (1, CURRENT_DATE, '테스트', 'TEMP', 'ENROLLED')
                """);
        execute(
                """
                INSERT INTO "form" (crt_dt, mdfcn_dt, qitem_cpst_cn, form_stts_cd, form_ttl_nm,
                                    creatr_mbr_id, sys_yn, sys_form_cd)
                VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '%s'::jsonb, 'OPEN',
                        '스터디·프로젝트 기획안', (SELECT min(mbr_id) FROM mbr), TRUE, 'PROPOSAL')
                """
                        .formatted(composition.replace("'", "''")));

        return Long.parseLong(query("SELECT min(form_id) FROM \"form\"").get(0));
    }

    private static List<String> optionsOf(long formId) {
        JsonNode qitems = readTree(compositionOf(formId)).get("qitems");
        List<String> options = new ArrayList<>();
        for (JsonNode qitem : qitems) {
            if (PROGRAM_TYPE_QITEM_ID.equals(qitem.get("qitemId").asText())) {
                qitem.get("optionList").forEach(option -> options.add(option.asText()));
            }
        }
        return options;
    }

    private static String compositionOf(long formId) {
        return query("SELECT qitem_cpst_cn FROM \"form\" WHERE form_id = " + formId).get(0);
    }

    private static int questionVersionOf(long formId) {
        return Integer.parseInt(
                query("SELECT qitem_ver FROM \"form\" WHERE form_id = " + formId).get(0));
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("문항 구성을 읽지 못했습니다: " + json, ex);
        }
    }

    private static void execute(String... statements) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("SQL 실행 실패", ex);
        }
    }

    private static List<String> query(String sql) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        } catch (Exception ex) {
            throw new IllegalStateException("질의 실패: " + sql, ex);
        }
    }

    private static Connection connection() throws Exception {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
