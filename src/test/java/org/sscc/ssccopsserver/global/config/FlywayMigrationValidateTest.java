package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/*
 * 마이그레이션 전체를 **실제 PostgreSQL 빈 DB**에 적용하고, 그 결과 스키마를 엔티티가
 * `ddl-auto: validate`로 받아들이는지 본다 (ssccops#213).
 *
 * **이 테스트가 이 이슈의 목적 그 자체다.** 그전까지 스키마 변경은 `ddl-auto: update` +
 * 이슈 본문에 적어 둔 수동 ALTER였고, 그 ALTER를 아무도 실행을 강제하지 않아 세 번 터졌다 —
 * `update`는 리네임을 **새 컬럼 추가**로 처리해 값이 든 옛 컬럼 옆에 빈 새 컬럼을 남긴다
 * (ssccops#209 승인 마비 · ssccops#212 공유 링크 · #224 회의 안건). 셋 다 발현이 머지가 아니라
 * **배포**라 리뷰에서 잡히지 않았고, 일반 테스트는 H2 + `ddl-auto: create`라 드리프트 상태가
 * 존재하지조차 않아 재현이 불가능했다.
 *
 * 여기서 잡는 것은 그 사각지대다. 마이그레이션 파일을 빠뜨린 채 엔티티만 고치면 이 테스트가
 * 실패한다 — Coolify가 develop 푸시를 dev로 자동 배포하므로(#202), 이것이 없으면 그 실패를
 * **dev 부팅**에서 처음 본다.
 *
 * H2가 아니라 Testcontainers인 이유는 baseline(V1)이 prod `pg_dump` 결과라 H2에서 아예
 * 실행되지 않기 때문이다(IDENTITY 시퀀스·`timestamp with time zone`·따옴표 식별자).
 * **일반 테스트까지 옮기지는 않았다** — #103이 스프링 컨텍스트를 58개에서 25개로 줄여 놓은
 * 이득을 반납하고, 그 테스트들이 공용 testdb 하나를 공유하며 'mbr이 비어 있다'(최초 가입자
 * 부트스트랩 #71) 같은 전제를 매 컨텍스트 스키마 재생성에 기대고 있어 공유 PostgreSQL로는
 * 성립하지 않는다. 실제 PostgreSQL이 필요한 것은 마이그레이션 검증 하나뿐이다.
 *
 * 컨테이너를 `static`으로 두어 이 클래스의 모든 테스트가 한 벌을 나눠 쓴다. `@ServiceConnection`
 * 대신 `@DynamicPropertySource`인 것은 여기서 URL만이 아니라 **Flyway와 ddl-auto를 함께
 * 덮어써야** 하기 때문이다 — `test` 프로필은 Flyway가 꺼져 있고 `ddl-auto: create`다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FlywayMigrationValidateTest {

    /*
     * 이미지 태그를 고정한다. `postgres:latest`로 두면 어제 통과한 것이 오늘 깨질 수 있고,
     * 그 실패는 우리 변경과 무관하다 — 브랜치 자동 생성이 `libretranslate:latest`로 겪은 일과
     * 같은 종류다(ssccops#208). 운영은 Supabase(PostgreSQL 15 계열)이며 이 스키마는
     * 두 메이저에서 같은 뜻이라 16으로 고정한다.
     */
    @SuppressWarnings("resource") // 컨테이너 수명은 Testcontainers의 ryuk이 관리한다
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ssccops_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add(
                "spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 이 테스트의 전부다 — Flyway가 스키마를 만들고 Hibernate가 그것을 검증한다.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // 시드는 Flyway(V3)가 넣는다. test 프로필의 sql.init이 같은 파일을 한 번 더 돌리면
        // 멱등하므로 깨지지는 않지만, 무엇이 넣었는지가 흐려지므로 끈다.
        registry.add("spring.sql.init.mode", () -> "never");

        // defer-datasource-initialization과 Flyway를 함께 켜면 부팅이 아예 안 된다 —
        // "Circular depends-on relationship between 'flyway' and 'entityManagerFactory'".
        // 그 설정은 test 프로필이 `ddl-auto: create` + sql.init 순서를 맞추려고 켠 것인데,
        // 여기서는 스키마도 시드도 Flyway가 만드므로 맞출 순서 자체가 없다.
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    }

    @Autowired private DataSource dataSource;

    /*
     * 컨텍스트가 뜬 것 자체가 검증이다 — `validate`는 엔티티와 실제 스키마가 어긋나면
     * 부팅을 실패시킨다. 그것이 dev·prod에서 우리가 원하는 동작이며, 여기서 미리 겪는다.
     */
    @Test
    void migrationsProduceASchemaTheEntitiesAccept() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success = true",
                        Integer.class);
        assertThat(applied)
                .as("V1부터 전부 적용됐어야 한다 — 빈 DB라 baseline-on-migrate는 개입하지 않는다")
                .isNotNull()
                .isGreaterThanOrEqualTo(4);

        Integer failed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                        Integer.class);
        assertThat(failed).isZero();
    }

    /*
     * baseline이 prod 덤프라 `shr_lnk`가 빠져 있고 V2가 그것을 채운다. 그 한 테이블이
     * 두 환경(prod 40 · dev 41)이 갈리는 유일한 자리이므로 결과에 실제로 있는지 못 박는다.
     */
    @Test
    void shareLinkTableComesFromV2() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema"
                                        + " = 'public' AND table_name = 'shr_lnk'",
                                Integer.class))
                .as("V2가 shr_lnk를 만들었어야 한다")
                .isEqualTo(8);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema"
                                        + " = 'public' AND table_name = 'shr_lnk' AND column_name ="
                                        + " 'shr_trgt_se_cd'",
                                Integer.class))
                .as("개명 후 이름이어야 한다 (ssccops#212)")
                .isEqualTo(1);
    }

    /*
     * V4가 지우는 고아 컬럼들이 결과에 남아 있지 않은지 본다. 새 DB에서는 애초에 만들어지지
     * 않으므로 이 테스트가 실제로 지키는 것은 **V1이 정리된 덤프인가**다 — 정리 전 덤프를
     * 실수로 되돌려 놓으면 여기서 잡힌다.
     */
    @Test
    void noOrphanColumnsSurvive() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer orphans =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND (table_name, column_name) IN (
                         ('mtg_dtl',       'prcs_se_cd'),
                         ('sub_work_type', 'autzr_role_cd'),
                         ('role',          'role_pstn_cd'),
                         ('shr_lnk',       'trgt_se_cd'))
                """,
                        Integer.class);

        assertThat(orphans)
                .as("ddl-auto: update가 남긴 고아 컬럼 — document/cleanup-2026-09-07.sql이 정리했다")
                .isZero();
    }

    /*
     * V8이 폼 전속 UNIQUE를 **살아 있는 행사끼리만** 걸도록 바꿨는지 본다 (#347 · ADR-0020).
     *
     * 이것을 여기서 보는 이유는 H2가 부분 인덱스를 지원하지 않아 일반 테스트가 이 규칙을 DB로는
     * 확인할 수 없기 때문이다 — 엔티티의 @UniqueConstraint를 걷었으므로, 이 인덱스가 실제로 이
     * 모양이 아니면 PostgreSQL에서 동시 연결을 막는 최종 방어선이 조용히 사라진다. 옛 제약이
     * 남아 있어도 안 된다: 그러면 지운 행사의 폼을 다른 행사가 못 쓴다.
     */
    @Test
    void formExclusivityIsAPartialUniqueIndexOnLiveEvents() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.table_constraints"
                                        + " WHERE table_schema = 'public' AND table_name = 'event'"
                                        + " AND constraint_name = 'uk_event_form'",
                                Integer.class))
                .as("조건 없는 UNIQUE 제약은 V8이 지웠어야 한다")
                .isZero();

        String indexDef =
                jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                                + " AND tablename = 'event' AND indexname = 'uk_event_form'",
                        String.class);
        assertThat(indexDef)
                .as("살아 있는 행사끼리만 거는 부분 유니크 인덱스여야 한다")
                .contains("UNIQUE INDEX")
                .contains("(form_id)")
                .contains("WHERE (del_dt IS NULL)");
    }

    /*
     * 시드가 마이그레이션으로 들어왔는지 본다. 옛 `data.sql`은 매 기동 돌았지만 V3는 한 번만
     * 도므로, 빠지면 기준 코드가 통째로 없는 DB가 만들어진다 — 회원가입부터 500이 난다.
     */
    @Test
    void referenceDataIsSeededByMigration() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mbr_grd", Integer.class))
                .as("회원 등급 4종 (임시·준·활동·정)")
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM authrt", Integer.class))
                .as("권한 트리")
                .isGreaterThan(0);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM sub_work_type WHERE aprv_need_yn = true"
                                        + " AND autzr_authrt_cd IS NULL",
                                Integer.class))
                .as("승인 필요 유형에 결재 권한이 비어 있으면 아무도 승인할 수 없다 (ssccops#209)")
                .isZero();
    }
}
