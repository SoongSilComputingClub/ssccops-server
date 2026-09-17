package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.sscc.ssccopsserver.domain.member.repository.MemberReferenceConstraints;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
     * 같은 종류다(ssccops#208).
     *
     * **`postgres:16-alpine`이 아니라 `pgvector/pgvector:pg17`이다** (#396). V10이
     * `CREATE EXTENSION IF NOT EXISTS vector`를 하는데 기본 이미지에는 그 확장이 없어 거기서
     * 멈춘다. pg17로 오른 것은 이 이미지가 내는 태그를 따른 것이며, 운영은 Supabase이고 이
     * 스키마는 그 사이 메이저에서 같은 뜻이다.
     *
     * `asCompatibleSubstituteFor`가 필요한 것은 Testcontainers가 PostgreSQLContainer에 대해
     * 이미지 이름이 `postgres`인지를 확인하기 때문이다 — 이 이미지는 그 위에 확장만 얹은 것이다.
     */
    @SuppressWarnings("resource") // 컨테이너 수명은 Testcontainers의 ryuk이 관리한다
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg17")
                                    .asCompatibleSubstituteFor("postgres"))
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
     * 회원 본인 데이터 FK에만 ON DELETE CASCADE가 붙었는지 본다 (#361 · V9 · ADR-0021).
     *
     * 하드 삭제의 경계는 코드가 아니라 이 제약들이다 — cascade가 빠진 곳이 있으면 응답 있는
     * 회원이 409로 막히고, 행위자 참조에 cascade가 붙으면 회원을 지울 때 **남의** 폼·행사가
     * 함께 사라진다. 후자가 훨씬 나쁘므로 20개가 그대로 NO ACTION인지를 함께 못 박는다.
     * H2(테스트)는 엔티티의 @OnDelete로 같은 제약을 만들지만 V9와 어노테이션이 갈리면
     * 테스트는 초록인데 dev는 다르게 동작하므로 PostgreSQL에서 다시 본다.
     */
    @Test
    void memberOwnDataCascadesAndActorReferencesDoNot() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Map<String, String> rules = new HashMap<>();
        jdbc.query(
                "SELECT rc.constraint_name, rc.delete_rule"
                        + " FROM information_schema.referential_constraints rc"
                        + " WHERE rc.constraint_schema = 'public'",
                rs -> {
                    rules.put(rs.getString(1), rs.getString(2));
                });

        List<String> cascading =
                List.of(
                        "fkldq8y4cffwc5bkk42lq0fmhvd", // mbr_grd_hstry.mbr_id
                        "fka1aso9jn3i6nhqoh3mg1hiyip", // mbr_stts_hstry.mbr_id
                        "fkssqlpq3chlo7d1rciu5wguvt1", // mbr_chg_hstry.mbr_id
                        "fka229oo73t8twd2by22omue4jt", // mbr_role_rel.mbr_id
                        "fkbtp6dhj8bntedf10yc81a0620", // form_rspns_hstry.mbr_id
                        "fktd4dqicsmwfngwhocoak92b6n", // event_ptcp.mbr_id
                        "fk27ofrsdwuss5ecdkl8uwwgvdb", // sub_work_aprv.mbr_id
                        "fkpejqbv1u3b1utku2f1k7xvche", // sub_work_aprv_vote.mbr_id
                        "fkfo0xp7208swp62tbiw1pl4nmy", // sub_work_rjct.mbr_id
                        "fk51eb6sl115o38xmcux4l66ne9", // form_rspns_rvw_hstry.form_rspns_id
                        "fk2yfmj6dd4h4l2phh0smwi0kb9", // event_ptcp.form_rspns_id
                        "fkqka61prj9r2o70ii0o0u0xgbv"); // atndc.event_ptcp_id
        for (String name : cascading) {
            assertThat(rules.get(name)).as("본인 데이터 FK %s는 cascade여야 한다", name).isEqualTo("CASCADE");
        }

        // 행위자 참조 — 서비스가 409로 번역하는 표와 같은 목록이며 하나라도 cascade면 남의 기록이 지워진다
        for (MemberReferenceConstraints.Reference reference : MemberReferenceConstraints.BLOCKING) {
            assertThat(rules.get(reference.constraintName()))
                    .as(
                            "행위자 참조 FK %s(%s)는 NO ACTION이어야 한다",
                            reference.constraintName(), reference.label())
                    .isEqualTo("NO ACTION");
        }

        // mbr을 가리키는 FK는 29개이고 그중 cascade는 본인 데이터 9개뿐이다
        Integer cascadingToMember =
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.referential_constraints rc"
                                + " JOIN information_schema.table_constraints tc"
                                + " ON tc.constraint_name = rc.unique_constraint_name"
                                + " WHERE rc.constraint_schema = 'public' AND tc.table_name = 'mbr'"
                                + " AND rc.delete_rule = 'CASCADE'",
                        Integer.class);
        assertThat(cascadingToMember).as("mbr을 가리키는 cascade FK").isEqualTo(9);
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

    /*
     * 시드 마이그레이션이 V3 하나가 아니다 (#402의 V11). 새 파일이 실제로 도는지는 여기서만
     * 확인된다 — test 프로필은 Flyway가 꺼져 있어 spring.sql.init이 대신 읽기 때문이다.
     *
     * SUPER 직속이라야 최고관리자가 이 권한을 포함한다(AuthorityPolicy에 SUPER 특별 취급 분기가
     * 없다, #71). 부여는 EXECUTIVE를 가진 세 역할까지이며, 여기가 늘어나면 국장·국원이 회칙
     * 코퍼스를 갈아치울 수 있게 된다 — 넓히는 것은 배포가 아니라 역할별 권한 화면이다(#65).
     */
    @Test
    void ragDocumentManageIsSeededUnderSuperAndGrantedToExecutiveRoles() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT up_authrt_cd FROM authrt"
                                        + " WHERE authrt_cd = 'RAG_DOCUMENT_MANAGE'",
                                String.class))
                .as("규정 문서 관리 권한의 상위 (#402)")
                .isEqualTo("SUPER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sys_yn FROM authrt WHERE authrt_cd = 'RAG_DOCUMENT_MANAGE'",
                                Boolean.class))
                .as("코드가 가리키는 권한이라 삭제·코드 변경이 막혀야 한다")
                .isTrue();

        assertThat(
                        jdbc.queryForList(
                                "SELECT r.role_nm FROM role_authrt_rel x"
                                        + " JOIN role r ON r.role_id = x.role_id"
                                        + " WHERE x.authrt_cd = 'RAG_DOCUMENT_MANAGE'",
                                String.class))
                .as("부여는 EXECUTIVE를 가진 세 역할까지 — 최고관리자는 트리 펼침으로 갖는다")
                .containsExactlyInAnyOrder("회장", "부회장", "총무");
    }

    /*
     * V10의 벡터 저장소가 Spring AI가 기대하는 모양인지 본다 (#396 · ADR-0028).
     *
     * **스키마를 Flyway가 만들기로 한 대가가 이 테스트다.** 스타터의 자동 생성을 껐으므로
     * (`initialize-schema: false`) 컬럼 이름이 하나만 어긋나도 프레임워크가 찾지 못하는데, 그
     * 실패는 부팅이 아니라 첫 적재에서 나온다. 차원(768)은 임베딩 모델이 내는 길이와 같아야 하며
     * 3072이면 pgvector 인덱스 상한 2,000을 넘어 **재적재 없이는 인덱스를 못 건다.**
     *
     * H2에는 `vector` 타입이 없어 일반 테스트가 이 테이블을 아예 만들지 못한다 — 여기서만 본다.
     */
    @Test
    void vectorStoreHasTheColumnsSpringAiExpects() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Map<String, String> columns = new HashMap<>();
        jdbc.query(
                "SELECT column_name, data_type FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'vector_store'",
                rs -> {
                    columns.put(rs.getString(1), rs.getString(2));
                });

        assertThat(columns)
                .as("Spring AI가 이름으로 찾는 넷이다 — 하나라도 다르면 적재가 깨진다")
                .containsOnlyKeys("id", "content", "metadata", "embedding");
        assertThat(columns.get("id")).isEqualTo("uuid");
        assertThat(columns.get("metadata"))
                .as("필터가 ::jsonb로 훑으므로 json이면 행마다 캐스팅이 붙는다")
                .isEqualTo("jsonb");

        String embeddingType =
                jdbc.queryForObject(
                        "SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                                + " WHERE attrelid = 'public.vector_store'::regclass"
                                + " AND attname = 'embedding'",
                        String.class);
        assertThat(embeddingType)
                .as("ssccops#322가 정한 768 — 바꾸면 되돌리기가 아니라 전량 재적재다")
                .isEqualTo("vector(768)");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"
                                        + " AND tablename = 'vector_store'"
                                        + " AND indexdef ILIKE '%USING hnsw%'",
                                Integer.class))
                .as("인덱스를 만들지 않는 것이 결정이다 (ADR-0028) — 청크 3,000에 닿으면 다시 본다")
                .isZero();
    }

    /*
     * **판본 관리의 자취가 남지 않았는지 본다** (#441 · ADR-0034 · V12).
     *
     * 이 자리에는 «시행 중인 판본은 문서당 하나»를 지키던 부분 유니크 인덱스(`uk_rag_doc_effective`)와
     * 판본 겹침을 막던 `uk_rag_doc_doc_cd_ver`를 확인하는 테스트가 있었다. 둘 다 V12가 지웠고,
     * 남아 있으면 **엔티티가 모르는 제약이 배포 DB에만 살아 있는 상태**가 된다 — 시행 중인 문서를
     * 둘째로 올리는 순간 아무도 예상하지 못한 위반으로 터진다.
     *
     * 컬럼까지 함께 보는 것은 «컬럼은 남았는데 제약만 사라진» 어중간한 상태를 잡기 위해서다.
     */
    @Test
    void versioningLeavesNoTraceInTheSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"
                                        + " AND tablename = 'rag_doc' AND indexname IN"
                                        + " ('uk_rag_doc_effective', 'uk_rag_doc_doc_cd_ver')",
                                Integer.class))
                .as("판본 관리가 걸던 인덱스·제약은 V12가 지웠다")
                .isZero();

        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE"
                                        + " table_schema = 'public' AND table_name = 'rag_doc' AND"
                                        + " column_name IN ('doc_cd', 'doc_ver')",
                                String.class))
                .as("컬럼 둘도 함께 사라졌다 — 제약만 지우면 엔티티와 스키마가 갈린다")
                .isEmpty();
    }

    /*
     * ══ @Enumerated(STRING) 필드 전부를 CHECK 제약과 대조한다 (#443) ══════════════
     *
     * **이 자리가 두 번 샜다.** `shr_lnk.shr_trgt_se_cd`가 `ShareTargetType`보다 좁아 공유 링크
     * 발급이 터졌고(V6), 같은 일이 `file_rfrnc.trgt_se_cd`에서 되풀이돼 규정 문서 업로드가
     * 언제나 500이었다(V13). V6의 주석이 «값을 더할 때 두 자리를 함께 고친다»를 적어 두었는데
     * 그 문장이 다른 enum까지 닿지 않았다 — 규칙을 사람이 읽는 자리에만 두면 새는 것이 확인됐고,
     * 그래서 기계가 본다.
     *
     * **어느 테스트도 이것을 잡을 수 없었다.** `test`는 Flyway가 꺼져 있고 H2 스키마를 Hibernate가
     * **현재 enum으로** 만들어 주므로 어긋남이 존재하지조차 않는다. 위 `validate` 테스트도
     * 못 잡는다 — Hibernate의 `validate`는 테이블·컬럼·타입만 보고 CHECK 제약은 보지 않는다.
     * 마이그레이션이 실제로 적용된 PostgreSQL을 들고 있는 이 클래스가 유일한 자리다.
     *
     * **같은 집합인지를 본다 — 부분집합이 아니다.** enum이 넓으면 배포에서 INSERT가 터지고
     * (이 이슈), 제약이 넓으면 코드가 모르는 값이 조용히 들어올 수 있다. 값을 **뺄** 때도
     * 마이그레이션을 쓰게 되며, 그때 «옛 행은 어떻게 하나»를 여기서 한 번 묻게 되는 것이 맞다.
     *
     * 물리 이름은 `@Table`·`@Column`을 읽고, 없으면 Spring Boot 기본 전략과 같은 규칙
     * (카멜 → 스네이크)으로 되돌린다 — `ExampleEntity.status`가 그 경우다.
     */
    @Test
    void checkConstraintsMatchTheirEnums() throws ClassNotFoundException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // (테이블, 컬럼) → CHECK 정의. 컬럼 하나에만 걸린 것이 코드값 제약이다
        Map<String, List<String>> definitions = new HashMap<>();
        jdbc.query(
                """
                SELECT t.relname, a.attname, pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                  JOIN pg_attribute a
                    ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                 WHERE n.nspname = 'public'
                   AND c.contype = 'c'
                   AND cardinality(c.conkey) = 1
                """,
                rs -> {
                    definitions
                            .computeIfAbsent(
                                    rs.getString(1) + "." + rs.getString(2),
                                    key -> new ArrayList<>())
                            .add(rs.getString(3));
                });

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<String> mismatches = new ArrayList<>();
        int compared = 0;

        for (BeanDefinition candidate : scanner.findCandidateComponents(ENTITY_BASE_PACKAGE)) {
            Class<?> entity = Class.forName(candidate.getBeanClassName());
            String table = tableName(entity);

            for (Field field : entity.getDeclaredFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated == null || enumerated.value() != EnumType.STRING) {
                    continue;
                }
                compared++;

                String qualified = table + "." + columnName(field);
                List<String> constraints = definitions.getOrDefault(qualified, List.of());

                if (constraints.isEmpty()) {
                    mismatches.add(
                            "%s — CHECK 제약이 없다 (%s)"
                                    .formatted(qualified, field.getType().getSimpleName()));
                    continue;
                }
                if (constraints.size() > 1) {
                    mismatches.add(
                            "%s — CHECK 제약이 %d개다: %s"
                                    .formatted(qualified, constraints.size(), constraints));
                    continue;
                }

                Set<String> allowed = allowedValues(constraints.get(0));
                Set<String> constants =
                        Arrays.stream(field.getType().getEnumConstants())
                                .map(constant -> ((Enum<?>) constant).name())
                                .collect(Collectors.toCollection(TreeSet::new));

                if (!allowed.equals(constants)) {
                    mismatches.add(
                            "%s — 제약 %s ≠ %s %s"
                                    .formatted(
                                            qualified,
                                            allowed,
                                            field.getType().getSimpleName(),
                                            constants));
                }
            }
        }

        assertThat(compared)
                .as("스캐너가 엔티티를 못 찾으면 이 테스트는 아무것도 보지 않고 통과한다")
                .isGreaterThanOrEqualTo(29);
        assertThat(mismatches)
                .as("enum이 정본이고 CHECK 제약은 그 사본이다 — 값을 더하거나 뺄 때 마이그레이션을 함께 쓴다")
                .isEmpty();
    }

    /** 엔티티를 찾을 뿌리. 루트 패키지라 도메인이 늘어도 따라온다 */
    private static final String ENTITY_BASE_PACKAGE = "org.sscc.ssccopsserver";

    /*
     * `pg_get_constraintdef`가 내는 정의에서 허용 값을 뽑는다. 모양이 두 가지인데
     * (`= 'SESSION'::text` · `= ANY ((ARRAY['A'::character varying, …])::text[])`)
     * 둘 다 값만 작은따옴표에 싸이고 컬럼 이름은 싸이지 않으므로 같은 규칙으로 걷힌다.
     */
    private static final Pattern QUOTED_VALUE = Pattern.compile("'([^']*)'::");

    private static Set<String> allowedValues(String constraintDefinition) {
        Set<String> values = new TreeSet<>();
        Matcher matcher = QUOTED_VALUE.matcher(constraintDefinition);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String tableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return table != null && !table.name().isEmpty()
                ? table.name()
                : toSnakeCase(entity.getSimpleName());
    }

    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        return column != null && !column.name().isEmpty()
                ? column.name()
                : toSnakeCase(field.getName());
    }

    /** Spring Boot 기본 물리 명명 전략(CamelCaseToUnderscoresNamingStrategy)과 같은 규칙 */
    private static String toSnakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
