# AGENTS.md

SSCC(숭실컴퓨팅클럽) 지원서 관리 백엔드 — Spring Boot 3.5 / Java 17 / Gradle.

## 빌드 · 테스트 · 린트

- 전체 빌드: `./gradlew build` (compileJava → checkstyle → spotless → test → jacoco 순서로 전부 수행)
- 테스트만: `./gradlew test`
- 단일 테스트 클래스: `./gradlew test --tests "org.sscc.ssccopsserver.domain.member.service.MemberServiceImplTest"`
- 단일 테스트 메서드: `./gradlew test --tests "*.MemberServiceImplTest.메서드명"`
- 포맷 검사: `./gradlew spotlessCheck` / 자동 정렬·수정: `./gradlew spotlessApply`
- 스타일 검사: `./gradlew checkstyleMain checkstyleTest` (설정: `config/checkstyle/checkstyle.xml`, Naver 컨벤션 변형 — 상단 주석에 원본과의 차이가 정리되어 있음)
- **OpenAPI 하위 호환 게이트** (#412 · ssccops#342): PR마다 `integrate-dev.yml`의 `api-compat` job이 base·head에서 각각 `OpenApiSnapshotTest`(공용 컨텍스트, `GET /v3/api-docs` → `build/openapi.json`)를 돌려 `oasdiff breaking --fail-on ERR --severity-levels config/oasdiff/severity-levels.txt`로 비교한다. **막는 것**: 응답 필드 삭제·이름·타입 변경 · 요청 필드 필수화 · 엔드포인트·enum 값 삭제. **통과하는 것**: 필드·엔드포인트·선택 파라미터 추가(응답 enum 값 추가는 warn — 요약에만). springdoc 스펙에 `required`가 없어 oasdiff 기본값으로는 응답 필드 삭제가 info라 심각도를 덮어쓴다(`config/oasdiff/README.md`). 의도한 깨는 변경은 라벨 **`api-breaking-approved`**(마이너 버전업 + 릴리스 노트 마이그레이션 항목 필수)를 붙이고 `gh run rerun <id> --failed` — job이 라벨을 실행 시점에 읽는다. 릴리스 PR·dependabot 면제. 스펙은 커밋하지 않는다(생성이 정본). 한계: 런타임 생성이라 애노테이션 누락은 못 잡는다. 로컬에서 보려면 `./gradlew test --tests '*OpenApiSnapshotTest'` 두 번(base·head)과 oasdiff 바이너리.
- 로컬 실행: `local` 프로필(`-Dspring.profiles.active=local`)이 PostgreSQL 접속 정보(`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`)와 Supabase JWKS URI(`SUPABASE_URL`), CORS 허용 오리진(`FRONTEND_URL`), Cloudflare R2 접속 정보(`R2_ACCOUNT_ID`/`R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`/`R2_BUCKET_NAME`, ssccops#113), 이 API 자신의 공개 주소(`APP_PUBLIC_BASE_URL` — 행사 이미지의 영구 읽기 주소를 조립한다, #208)를 요구한다. **`R2_PUBLIC_BASE_URL`은 없어졌다**(#208) — 버킷을 공개하는 전제가 성립하지 않아 읽기가 전부 서명된 URL로 바뀌었다. R2 엔드포인트는 `R2_ACCOUNT_ID`로 서버가 직접 조립하므로 별도 env가 없다. **`.env` 한 벌을 `docker compose`와 `./gradlew bootRun`이 함께 읽는다**(`cp .env.example .env` 후 값 채우기 — compose는 이 디렉터리의 `.env`를 자동으로, `bootRun`은 `build.gradle`이 파싱해 JVM 환경변수로 주입). 두 가지 방식이 있다:
  - `docker compose up`으로 `backend`(local 프로필)와 `postgres`를 함께 띄운다. 컨테이너 안에서는 DB 주소가 달라야 하므로 `docker-compose.yml`이 `DB_HOST=postgres`·`DB_PORT=5432`로 덮어쓴다.
  - `docker compose up postgres`로 DB만 띄우고(호스트 포트 `DB_PORT`, 기본 `15432`) 앱은 `./gradlew bootRun`으로 실행한다. `.env`의 `DB_HOST=localhost`·`DB_PORT=15432`가 그대로 쓰인다. 로컬에 PostgreSQL을 직접 설치해 쓴다면 `createdb ssccops_server_db` 후 `DB_PORT=5432`로 바꾼다.

  **주의**: `.env`와 `docker-compose.yml`의 변수 이름에 하이픈(`-`)을 쓰지 않는다. Compose의 변수 치환은 셸 파라미터 확장 문법을 따르므로 `${db-username}`은 이름이 아니라 "`db`가 없으면 문자열 `username`"으로 읽힌다 — 실제로 이 때문에 `.env` 값이 통째로 무시된 채 `POSTGRES_USER=username`·`POSTGRES_DB=name:-ssccops_server_db`로 DB가 만들어지고 앱이 인증 실패로 죽는 일이 있었다(#59). Spring 프로퍼티 키(`db-username`)를 환경변수 이름으로 그대로 쓸 수 없다.

  **로컬은 `ddl-auto: update`이고 Flyway가 함께 돈다**(ssccops#213 — 이 줄은 `create-drop`이라고 적혀 있었으나 `application-local.yaml`은 오래전부터 `update`다). 그래서 DB를 새로 만들 때만 회원이 비고, 그때 첫 가입자가 최고관리자가 된다(#71). 기획안 시스템 폼(`PROPOSAL`)은 명의로 쓸 회원이 있어야 세워지므로 **가입하기 전에는 없는 것이 정상**이며, 가입하는 순간 세워진다(#184). 기동 로그에 `회원이 없어 기획안 시스템 폼(PROPOSAL) 시드를 건너뛴다`가 보이면 고장이 아니라 아직 아무도 가입하지 않은 상태다. 가입한 뒤에도 폼이 없다면 그때가 진짜 문제이니 `ProposalFormSeeder`의 `log.error`를 찾을 것.
- 프로필: `local`(PostgreSQL, OTel 비활성, **Flyway + ddl-auto update** — 이유는 아래) / `dev`(**Flyway + ddl-auto validate**) / `prod`(env 변수 주입, **Flyway + ddl-auto validate**, Swagger 비활성) / `test`(H2 인메모리, ddl-auto create, **Flyway 꺼짐** — 이유는 아래). 테스트 인증은 [ADR-0009](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0009-standardize-test-jwt-decoder-and-share-context.md)의 공용 JWT 규약을 쓴다. **`create-drop`이 아닌 이유는 `application-test.yaml`의 주석에 있다** — 고정 Clock·독립 DB·`@MockitoBean`으로 갈린 컨텍스트도 `testdb`를 공유하므로, 하나가 닫힐 때 스키마를 지우면 남은 테스트가 "Table MBR not found"로 떨어질 수 있다.

**주의**: JPA 프로필 설정에 `database-platform`(Hibernate `dialect`)을 명시하지 않는다. Hibernate가 커넥션에서 자동 감지하며, 명시하면 `HHH90000025` 경고가 뜨고 DB 엔진을 바꿀 때 드라이버와 방언이 어긋나 깨진다.

**주의**: checkstyle의 `ImportOrder`는 `java, javax, jakarta, org, net, com, *, lombok` 그룹 순서를 엄격히 검사한다. import를 추가/이동한 뒤 checkstyle이 실패하면 순서를 수동으로 고치지 말고 `./gradlew spotlessApply`로 자동 정렬할 것 (Spotless의 `importOrder` 설정이 checkstyle 규칙과 동일하게 맞춰져 있음).

**주의**: `dev`·`prod`는 이제 **`ddl-auto: validate`**이며 스키마는 Flyway가 만든다(ssccops#213 · 아래 절). `validate`인 것은 엔티티와 실제 스키마가 어긋나면 **부팅을 실패시키기 위해서**다 — ssccops#209는 값이 든 옛 컬럼 옆에서 빈 새 컬럼을 읽으며 정상 부팅했고 그래서 아무도 몰랐다. 그전의 `update`는 "추가만 자동, 삭제·리네임·타입 변경은 수동 `ALTER`"였는데 그 수동 단계를 아무도 강제하지 않아 세 번 터졌다(ssccops#209 승인 마비 · ssccops#212 공유 링크 · #224 회의 안건 — 셋 다 리네임이 '새 컬럼 추가'로 처리된 경우다).

**이제 이슈 본문에 `ALTER`를 적어 두는 관행은 없다.** 스키마를 바꾸면 마이그레이션 파일을 함께 쓴다 — 빠뜨리면 dev 배포가 `validate`에서 막히고, 그것이 의도한 동작이다. 예전에 대기 중이던 `ALTER`(#224의 `mtg_dtl`·`form_rspns_rvw_hstry` 리네임)는 2026-09-07에 dev·prod 양쪽에 적용했고, 그 정리 뒤에 뜬 덤프가 baseline이다.

**주의**: `dev`는 `ddl-auto: create-drop`이 **아니라 `update`다**(ssccops#83). Render 무료 티어(512MB, 공유 CPU)에서 재시작(배포·유휴 슬립 해제 포함)마다 스키마 전체를 지우고 다시 만드는 비용이, 회원·역할·CSV 이관·회의 등 테이블이 늘어나며 헬스체크 타임아웃을 넘길 만큼 무거워졌다 — 실제로 부팅 중 `HikariPool housekeeper Thread starvation`이 찍히고 배포가 `update_failed`로 반복 실패했으며, 한 번은 부팅이 "성공"했지만 `mbr_grd` 시드가 일부만 들어간 채로 떠 회원가입이 500을 냈다. `update`는 새 테이블·컬럼은 자동 반영하지만 **컬럼 삭제·이름 변경·타입 변경은 반영하지 않는다** — 지금은 `prod`도 `update`라 두 환경의 제약이 같다. **그 트레이드오프는 이제 없다** — Flyway가 들어와 `dev`·`prod` 모두 `validate`이며(ssccops#213) 리네임·삭제도 마이그레이션 파일로 나간다. 아래 문단은 `update`이던 시절의 기록이다. **`ddl-auto: update`로도 근본 원인은 해결되지 않았다** — 실제로는 부팅 중 Hibernate가 EntityManagerFactory(당시 엔티티 26종 메타모델)를 만드는 도중 컨테이너가 OOM으로 죽고 있었다(`exit 137`, ssccops-server#107). 그때는 `Dockerfile`의 `ENTRYPOINT`에 JVM 메모리 플래그를 명시해 막았지만, **배포가 Render 무료 티어(512MB)에서 Coolify(13.6GB)로 옮겨오며 그 제약이 사라져 플래그도 걷어냈다**(#202). 지금은 JVM 기본값에 맡긴다 — 엔티티는 39종으로 늘었지만 메모리 여유가 그보다 훨씬 크다. **나중에 컨테이너 메모리를 좁게 제한하게 되면 이 절을 다시 볼 것** (`exit 137`이 재발하면 힙 밖 메모리부터 의심한다).

## 스키마 변경 — Flyway가 한다 (ssccops#213)

**스키마를 바꾸면 마이그레이션 파일을 함께 쓴다.** `src/main/resources/db/migration/`에
`V{다음 번호}__{무엇을 하는지}.sql`로 더한다. 엔티티만 고치고 파일을 빠뜨리면 `dev` 배포가
`ddl-auto: validate`에서 막힌다 — **그것이 이 도구를 들인 이유다.**

그전에는 "추가는 `ddl-auto: update`가 자동, 삭제·리네임·타입 변경은 이슈 본문에 적어 둔 수동
`ALTER`"였고, 그 `ALTER`를 아무도 실행을 강제하지 않아 세 번 터졌다.

| | 무엇이 |
|---|---|
| ssccops#209 | prod에서 승인 필요 하위 업무를 **아무도** 승인·반려 못 함 (SUPER도) |
| ssccops#212 | 공유 링크 대상 구분 컬럼 |
| #224 | 회의 안건 처리 상태 — prod에서 **조용히 빈 값이었다** |

셋 다 같은 경로다. `update`는 리네임을 **새 컬럼 추가**로 처리해 값이 든 옛 컬럼 옆에 빈 새
컬럼을 남기고, 앱은 새 컬럼만 읽는다. 발현이 머지가 아니라 **배포**라 리뷰에서 잡히지 않았다.

### 프로필별로 다른 이유

| 프로필 | Flyway | `ddl-auto` | 왜 |
|---|---|---|---|
| `dev`·`prod` | 켜짐 | **`validate`** | 어긋나면 부팅을 실패시킨다. ssccops#209가 정상 부팅했던 것이 문제였다 |
| `local` | 켜짐 | `update` | 개발 편의. 대신 로컬과 배포본이 다르게 자랄 수 있어, 마이그레이션을 빠뜨리면 dev에서 걸린다 |
| `test` | **꺼짐** | `create` | 아래 |

**`test`가 예외인 이유**는 테스트가 H2 인메모리에서 돌고 baseline이 prod `pg_dump` 결과라
H2에서 아예 실행되지 않기 때문이다(IDENTITY 시퀀스 · `timestamp with time zone` · 따옴표 식별자).
전면 Testcontainers로 옮기는 안은 기각했다 — #103이 스프링 컨텍스트를 58개에서 25개로 줄여 놓은
이득을 반납하고, 무엇보다 이 테스트들이 공용 `testdb` 하나를 공유하며 '`mbr`이 비어 있다'
(최초 가입자 부트스트랩 #71) 같은 전제를 `ddl-auto: create`의 매 컨텍스트 스키마 재생성에
기대고 있어 공유 PostgreSQL로는 성립하지 않는다.

대신 **마이그레이션 자체는 `FlywayMigrationValidateTest`가 Testcontainers PostgreSQL에서
검증한다** — 빈 DB에 V1부터 전부 적용하고 엔티티 41종에 대해 `validate`가 통과하는지 본다.
이 이슈가 막으려는 것(마이그레이션이 dev 배포에서 처음 검증되는 것)은 그 테스트가 막는다.
**로컬에서 `./gradlew test`를 돌리려면 Docker가 필요하다** — 그 한 클래스 때문이다.

### 시드는 마이그레이션 파일 한 벌이다 — 늘면 `test`의 목록도 함께 는다

옛 `data.sql`은 **삭제됐고** `V3__seed_reference_data.sql`이 그것을 그대로 옮겨 담았다
(`spring.sql.init`도 함께 걷어냈다 — 두 벌이 동시에 도는 상태를 만들지 않는다).

- `dev`·`prod`·`local`: Flyway가 넣는다.
- `test`: Flyway가 꺼져 있으므로 `spring.sql.init.data-locations`가 **같은 파일을** 가리킨다.
  사본을 테스트 리소스에 두지 않은 것은 시드가 두 벌이 되어 갈리기 때문이다.

**시드 마이그레이션이 둘 이상이면 `data-locations`와 `SeedScript.LOCATIONS`에 같은 목록·순서로
적는다**(#402의 `V11`). 빠뜨리면 그 행이 `test` DB에만 없어, 새 권한을 요구하는 엔드포인트의
테스트가 «`data.sql`이 넣어야 할 권한이 없다»(`AuthorityFixture`)로 죽는다 — dev·prod는 Flyway가
넣으므로 멀쩡하다.

`WHERE NOT EXISTS` 멱등성은 그대로다. 버전 마이그레이션이라 한 번만 돌지만 **baseline이 이미
시드된 prod 덤프라 이 파일이 처음 도는 DB에도 행이 이미 있다** — 가드가 없으면 중복 키로 깨진다.
가드의 원래 뜻(운영진이 화면에서 고친 값을 배포가 되돌리지 않는다)도 함께 산다:
**값을 고칠 때 `UPDATE`로 덮어쓰지 말 것.** 기준 코드를 더할 때도 새 마이그레이션 파일이다 —
매 기동 반영되던 편의는 사라지지만 무엇이 언제 들어갔는지가 남고, #241이 터진 자리가 그 부재였다.

기획안 시스템 폼(`sys_form_cd = 'PROPOSAL'`, #173)은 여기 없다 — 자바(`ProposalFormSeeder`)가
세운다. 문항 구성이 JSONB라 한 벌의 SQL로 H2·PostgreSQL을 함께 만족시킬 수 없고, `qitemId`가
`SystemFormContract`·이관과 공유하는 계약이라 SQL 문자열로 두면 선언과 시드가 두 벌이 된다.

### 마이그레이션 파일

| | |
|---|---|
| `V1__baseline.sql` | prod 현재 스키마(2026-09-07). `pg_dump --schema-only`를 걸러 담았다 — 무엇을 왜 뺐는지는 파일 상단에 있다 |
| `V2__create_share_link.sql` | `shr_lnk`. prod에는 없고 dev에는 있어 **두 환경에서 결과가 달라야 하는 유일한 마이그레이션**이다 |
| `V3__seed_reference_data.sql` | 기준 코드·기준 데이터 |
| `V4__drop_orphan_columns.sql` | `update`가 남긴 고아 컬럼 넷. dev·prod에서는 이미 정리돼 아무 일도 안 하며, 몇 달 자란 **로컬 DB**를 위해 남는다 |
| `V5__create_sub_work_checklist_history.sql` | `sub_work_chck_list_hstry`. 체크리스트 편집을 여는 조건으로 만든 이력 테이블 |
| `V6__widen_share_target_check.sql` | `shr_lnk.shr_trgt_se_cd` CHECK 제약을 `ShareTargetType` 여섯 값으로 넓힌다 |
| `V7__add_form_del_dt.sql` | `form.del_dt`. 폼 소프트 삭제 표시 컬럼 (#329) |
| `V8__add_event_del_dt.sql` | `event.del_dt`. 행사 소프트 삭제 표시 컬럼이며, `uk_event_form`을 살아 있는 행사끼리만 거는 부분 유니크 인덱스로 바꾼다 (#347) |
| `V9__cascade_member_own_data.sql` | 회원 본인 데이터 FK 9개(+ 딸린 3개)에 `ON DELETE CASCADE`. 임시 회원 하드 삭제의 경계이며 행위자 참조 20개는 손대지 않는다 (#361 · ADR-0021) |
| `V10__create_assistant_tables.sql` | `vector` 확장 · `vector_store`(Spring AI가 이름을 정한다) · `rag_doc`. 규정 도우미(RAG)의 스키마이며 **`FlywayMigrationValidateTest`의 Testcontainers 이미지가 `pgvector/pgvector:pg17`로 바뀐 이유**다 (#396 · ADR-0028·0029) |
| `V11__seed_rag_document_manage_authority.sql` | `RAG_DOCUMENT_MANAGE` 권한 한 줄(`SUPER` 직속 · `sys_yn`)과 회장·부회장·총무 부여. **시드를 더할 때도 새 파일**이라는 규칙이 처음 쓰인 자리이며, `test`는 Flyway가 꺼져 있어 `data-locations`가 V3와 함께 이 파일도 가리킨다 (#402) |
| `V12__drop_rag_document_versioning.sql` | `rag_doc.doc_cd`·`doc_ver`와 제약·인덱스 둘(`uk_rag_doc_doc_cd_ver` · `uk_rag_doc_effective`). 규정 문서의 판본 관리를 걷어낸다 — 문서 한 건이 곧 그 규정이고 갱신은 «옛 것을 지우고 새로 올리기»다. **시행 중인 문서가 여러 건일 수 있게 된다** (#441 · ADR-0034) |
| `V13__widen_file_target_check.sql` | `file_rfrnc.trgt_se_cd` CHECK 제약을 `FileTargetType` 두 값으로 넓힌다. V6과 **같은 결함의 두 번째**라 — enum만 자라고 제약은 그대로여서 규정 문서 업로드가 언제나 500이었다 — 제약을 이름이 아니라 컬럼으로 찾아 지우고, 규칙을 문장이 아니라 테스트로 옮겼다(`FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums`가 `@Enumerated(STRING)` 29개를 전수 대조한다) (#443) |

**baseline을 엔티티에서 생성하지 않은 이유**는 prod가 `update`로 자라난 DB라 엔티티가 말하는
스키마와 실제가 갈려 있었기 때문이다. 대조용 DDL이 필요하면 아래로 뽑는다 — **baseline이 아니다.**

```bash
./gradlew bootRun --args='--spring.profiles.active=local   --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create   --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=build/entity-schema.sql   --spring.jpa.properties.jakarta.persistence.schema-generation.create-source=metadata'
```

### 규칙

- **이미 적용된 마이그레이션 파일을 고치지 않는다.** Flyway가 체크섬으로 검증해 부팅이 실패한다.
  잘못된 것은 새 마이그레이션으로 되돌린다.
- **`baseline-on-migrate: true`**는 이미 테이블이 있는 DB(dev·prod)에 Flyway를 처음 붙이기
  위한 것이다. `baseline-version: 1`이 V1을 '적용됨'으로 표시해 V2부터 돌게 한다 — V1의 내용이
  곧 그 DB의 현재 모습이므로 맞다. 빈 DB에서는 개입하지 않고 V1부터 전부 돈다.
- **머지는 곧 dev 배포다**(Coolify가 `develop` 푸시를 자동 배포한다, #202). 마이그레이션이
  깨지면 dev가 즉시 죽으므로 `FlywayMigrationValidateTest`를 통과시키고 머지한다.

## 결정 기록 (ADR)

되돌리기 어려운 결정 — 여러 컴포넌트에 걸치거나 운영·보안에 영향을 주는 것 — 은 기획 저장소의
**[`ssccops/docs/decisions/`](https://github.com/SoongSilComputingClub/ssccops/tree/main/docs/decisions)**
에 한 장씩 남긴다. 목차와 쓰는 법은 그 저장소의 `AGENTS.md`에 있다.

**논의는 `[DECISION]` 이슈에서, 확정은 ADR 파일로.** 이슈는 ADR 링크 없이 닫지 않는다.

이 저장소의 판단 중 ADR로 올라갈 것과 여기 남을 것을 가른다 — **ADR은 '왜', 코드 주석은
'어떻게'**다. 주석에서 ADR을 가리키면(`ADR-0003 참고`) 둘이 갈라지지 않는다. 아래 절들은
여전히 이 저장소의 규칙이며 ADR로 옮기지 않는다.

## 아키텍처

패키지 루트는 `org.sscc.ssccopsserver` (Gradle group도 `org.sscc`) — SSCC 동아리 프로젝트이므로 Spring Initializr 기본값(`com.example`)을 쓰지 않는다.

- `domain/{member,auth,operation,...}` — 도메인별로 `controller/service/repository/entity/dto/code` 하위 구조를 반복하는 계층형 패키지. 새 도메인을 추가할 때 이 구조를 그대로 따른다. (`admin`/`applyform`/`user`는 Supabase 인증 전환에 맞춰 재설계 예정이라 제거됨 — 잔재를 찾는 코드가 있다면 지우면 된다.)
- **이 구조는 도메인별 계층형이고 DDD 전술 패턴은 일부만 쓴다** (ssccops#244). 폴더 이름이 `domain/`이라 애그리게이트와 도메인 이벤트를 기대하기 쉬운데, 2026-09-08에 실제로 재 본 값은 이렇다.

  | 항목 | 값 |
  |---|---|
  | `@Entity` 클래스 | 41 |
  | 그중 인스턴스 도메인 메서드를 가진 것 | **29 (70%)** |
  | 정적 팩토리만 있고 인스턴스 행위가 없는 것 | 12 — **전부 이력·관계 테이블** |
  | 리포지토리 | 41 (엔티티 수와 같다) |
  | `@DomainEvents` | 2 |

  - **쓰는 것**: 엔티티가 행위를 갖는다 — 상태 전이와 그 검증은 서비스가 아니라 엔티티가 던진다(`FormEntity.changeStatus` · `FormResponseReviewHistoryEntity.record`). 생성은 정적 팩토리 + `protected` 기본 생성자(`MemberEntity`에서 채택한 컨벤션).
  - **안 쓰는 것**: **애그리게이트 경계가 없다** — 리포지토리가 엔티티마다 하나라 단위가 테이블이고, 루트를 거쳐야 저장된다는 규칙도 없다. **도메인 이벤트 중심 설계도 아니다** — `@DomainEvents`는 둘뿐이고 도메인 간 연동은 이벤트가 아니라 **포트 인터페이스**로 한다(아래 순환 항목 · `SystemFormApprovalHook`이 이벤트를 기각한 이유는 트랜잭션 원자성이다). 새 기능을 얹을 때 이 둘을 전제하지 말 것.
  - 행위 없는 12개는 모델이 빈약해서가 아니라 **불변 이력 행**이라 그렇다(`*HistoryEntity` · `*RelationEntity` · `SubWorkApprovalEntity` · `SubWorkRejectionEntity` · `CurriculumItemEntity` — 전 컬럼 `updatable = false`가 걸린 것들이다). 여기에 세터를 여는 것이 개선이 아니다.
  - **`modules/`·`packages/`로 개명하는 안은 기각했다**(2026-09-08) — 이름을 바꿔도 결합은 그대로인데 도메인 파일의 import 경로만 전부 바뀐다. 문제는 이름이 아니라 **문서가 이 표를 말하지 않은 것**이었다.
- `domain/example` — 위 6계층 구조를 보여주는 참고용 템플릿 도메인. 새 도메인을 추가할 때 이 구조를 복사해서 시작하면 된다. **읽으라고 있는 것이지 부르라고 있는 것이 아니다** — `ExampleController`는 `@Profile("local")`이라 dev·prod에는 라우트가 없다(ssccops#244). 그전까지 `/examples`는 `anyRequest().authenticated()`에만 걸리고 `@RequireAuthority`가 없어 **로그인만 하면 누구나 쓰고 지울 수 있었고**, `example_entity`는 prod에 실재하며 `V1__baseline.sql`에도 들어 있다 — "실제 기능 아님"이라는 설명과 달리 배포된 쓰기 경로였다. **엔티티와 테이블은 지우지 않는다**: 엔티티를 지우면서 테이블을 두면 서술이 또 갈리고, 테이블까지 지우면 되돌릴 수 없는 prod DDL을 정리 삼아 실행하는 것이 된다. **복사할 때 `@Profile` 한 줄은 지운다** — 그 줄은 템플릿을 배포에서 빼기 위한 것이지 새 도메인이 따를 규칙이 아니다.
- **도메인끼리 서로를 되부르지 않는다 — `DomainCycleTest`가 막는다** (ssccops#242). ArchUnit이 `domain.(*)` 슬라이스의 순환을 검사하는 **테스트**라 `./gradlew test`와 CI가 그대로 잡는다(별도 도구·별도 job이 아니다). 2026-09-08 측정에서 순환 5개(그중 둘은 전이 순환이라 파일 하나만 봐서는 보이지 않았다)가 나왔고 전부 *"다른 도메인에 읽기 전용 질의 하나를 묻는"* 자리였다. 끊는 방법은 새 공용 모듈이 아니라 **묻는 쪽이 포트 인터페이스를 선언하고 소유한 쪽이 구현하는 것**이며(`SystemFormApprovalHook`·`SharePreviewProvider`가 이미 쓰는 모양), 지금 그 포트는 셋이다 — `MemberSubWorkLoadProvider`(회원 선언 · 운영 구현) · `AcademicFormLinkProvider`(폼 선언 · 학술 구현) · `AcademicEventLinkProvider`(행사 선언 · 학술 구현). 공용 모듈로 빼는 안을 기각한 것은 그 모듈이 다음 순환의 자리가 되기 때문이다. **한 방향 결합은 손대지 않는다** — `member`로 들어오는 화살표가 143개인 것은 회원이 모든 도메인에 등장한다는 사실이지 결함이 아니다. **포트 구현은 전용 빈으로 둔다** — 기존 서비스 구현체에 얹으면 그 포트를 `@MockitoBean`으로 바꾸는 테스트가 빈을 통째로 갈아 끼워 같은 빈을 다른 타입으로 주입받던 곳이 함께 죽는다.

### 도메인

도메인 고유 규칙(상태 전이 · 인가 층 · 불변 이력 · 기각한 대안 · 테스트 함정)은 각 도메인 폴더의 `AGENTS.md`가 정본이다. 여기서는 가리키기만 하고 `@`로 끌어오지 않는다 (#407 · ssccops#330).

| 도메인 | 한 줄 | 정본 |
|---|---|---|
| `member` | 회원 · 등급/상태와 그 이력 · 역할 배정 · 권한 트리(`authrt`) · 가입/계정 연결/CSV 이관/하드 삭제 | [domain/member/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/member/AGENTS.md) |
| `operation` | 업무 · 하위 업무(전이·승인·투표·체크리스트) · 회의 · 승인함 · 대시보드 — `oper` 공통 테이블의 확장 | [domain/operation/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/operation/AGENTS.md) |
| `form` | 폼(JSONB 문항) · 응답과 검토 이력 · 시스템 폼(기획안) · 템플릿 · 소프트 삭제 | [domain/form/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/form/AGENTS.md) |
| `academicprogram` | 스터디·프로젝트 — 기획안 승인 이관으로만 생기는 행사의 1:1 확장 · 회차·출석·인증사진 · 모집 선발 | [domain/academicprogram/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/academicprogram/AGENTS.md) |
| `event` | 행사 게시물 · 참가자 · 본문 이미지(presigned PUT · 영구 리다이렉트) · 익명 공개 조회 · 소프트 삭제 | [domain/event/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/event/AGENTS.md) |
| `share` | 토큰 공유 링크 — 미리보기까지만, 대상이 무엇인지 모른다 | [domain/share/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/share/AGENTS.md) |
| `auth` | `GET /v1/auth/session` 하나 — 미가입도 200 | [domain/auth/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/auth/AGENTS.md) |
| `file` | 파일이 버킷의 어디에 있는가 — `file_rfrnc` · 서명 · 삭제 · 복사 | [domain/file/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/file/AGENTS.md) |
| `assistant` | 규정 도우미(RAG) — 문서(`rag_doc` · 판본 없음 ADR-0034) · 두 축 상태 · 청크 저장소 포트 · 기능 플래그 · 회칙 파서와 조 단위 청커 · 평문 추출과 고정 길이 청커(**확장자가 유형을 단정하지 않는다** — 회칙이 아닌 `.md`는 거절이 아니라 평문으로 떨어진다, #445) · 업로드(멀티파트 · 동기 파싱 · R2 원본) · 색인 워커(잠금 · 부팅 복구 · 재색인 · 청크 상한) · 목록(요약 3값 동봉 · 서버 `q`)·상세·적용 전환(시행본 1건)·하드 삭제 · **질의**(임계값 거절 · 검색 필터 둘 · **번호 참조 인용**과 그 해석 · 추천 질문과 **빈 상태 세 값**(`corpusState` — «문서가 없다»와 «시행 중인 것이 없다»를 가른다, #449)) · **SSE 스트리밍**(`POST /v1/assistant/queries/stream` · 조각 단위 인용 판정 · 첫 바이트 전에 끝나는 거절 계단 · **`ApiResponse` 봉투의 유일한 예외**, #447) · **질의 레이트 리밋**(회원 분·일 · 전역 분 · 인메모리) · **골든셋**(스텁 임베딩 · 지표 넷 · 회귀 표) · **대화**(힙 Caffeine · 대화 500개 · 24h 슬라이딩 · 서버가 발급하는 `{회원 식별자}:{탭 UUID}` · 질의 임베딩 캐시) | [domain/assistant/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/assistant/AGENTS.md) |
| `example` | 6계층 템플릿, `@Profile("local")` — 읽으라고 있는 것 | [domain/example/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/example/AGENTS.md) |

### 전역 — `global/`과 횡단 관심사

- `global/config/R2Config` — Cloudflare R2(S3 호환 오브젝트 스토리지, ssccops#113) 연결용 `S3Client` 빈. R2 전용 SDK가 없어 AWS SDK v2의 S3 모듈을 그대로 쓰되, 리전은 R2가 요구하는 고정값 `"auto"`이고 `forcePathStyle(true)`가 필요하다(꺼져 있으면 R2가 모르는 가상 호스트 이름으로 요청이 나가 연결 자체가 실패한다). 같은 설정으로 `S3Presigner` 빈도 함께 만든다(#161 — 프리사이너에는 `forcePathStyle` 단축 설정이 없어 `S3Configuration.pathStyleAccessEnabled(true)`로 켠다). 두 빈의 엔드포인트·리전이 갈리면 서명은 성공하는데 R2가 거절하는 URL이 나가고, 그 실패는 서버 로그가 아니라 브라우저에서만 보인다.
  - 첫 사용처인 행사 본문 이미지 업로드·읽기 리다이렉트의 규칙은 `domain/event/AGENTS.md`에 있다.
- `global/apipayload` — 모든 API 응답의 공통 껍데기. **예외가 하나 있다** — 규정 도우미의 SSE
  스트리밍(`POST /v1/assistant/queries/stream`, #447)은 이벤트에 봉투를 씌우지 않는다. 봉투가
  «요청 하나에 응답 하나»를 전제로 `success`·`code`·`message`를 매기는데 SSE 는 한 응답 안에서
  이벤트가 여러 번 나가 그 셋이 조각마다 되풀이될 뿐이기 때문이며, 대신 **오류 이벤트만은 봉투와
  같은 필드 이름**(`code`·`message`)을 써 화면의 오류 처리를 두 벌로 만들지 않는다. 그 경로에서도
  **첫 바이트 전의 거절은 종전대로 상태 코드 + 봉투**다 — 예외는 «열린 스트림 안»에만 있다.
  - `ApiResponse<T>` — 생성자는 private, `success`/`successWithNoData`/`created`/`fail` 정적 팩토리로만 생성.
  - `code/success/SuccessCode`, `code/error/ErrorCode` — 인터페이스이며 `CommonErrorCode`가 전역 에러를 구현하고, 도메인 전용 에러가 필요하면 같은 방식으로 도메인 패키지 아래 `code/error` enum을 구현한다. 새 에러를 추가할 때 전역(`CommonErrorCode`)에 넣을지 도메인 전용 enum에 넣을지 먼저 판단할 것.
  - `exception/GeneralException` — 서비스 레이어에서 던지는 표준 예외, `ErrorCode`를 감싼다.
  - `handler/GlobalExceptionHandler` — `@RestControllerAdvice`. `GeneralException`은 감싼 `ErrorCode` 그대로, `MethodArgumentNotValidException`/`HttpMessageNotReadableException` 등 스프링 기본 예외는 `CommonErrorCode`로 변환해 항상 `ApiResponse.fail(...)` 포맷으로 응답한다. 컨트롤러 레벨에서 별도 try/catch를 추가하지 않는다.
- `global/security` — 인증/인가. 구글 OAuth2 로그인과 자체 JWT 발급 스택은 제거됐고, 지금은 Supabase Auth가 발급한 JWT를 `spring-boot-starter-oauth2-resource-server`로 검증한다. JWKS URI(`{SUPABASE_URL}/auth/v1/.well-known/jwks.json`)는 프로필별로 다른 Supabase 프로젝트를 가리키도록 `application-{profile}.yaml`에 분리돼 있다.
  - **`iss`·`aud`도 본다** (#383 · ssccops#313 · [ADR-0026](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0026-mcp-auth-reuses-supabase-oauth-server.md)). `withJwkSetUri`의 기본 검증기는 `exp`·`nbf`뿐이었다 — 서명 키가 프로젝트 전용이라 위험은 낮았지만, MCP를 위해 Supabase OAuth 2.1 서버를 켜면 같은 키로 서명된 토큰이 `client_id`를 달고 여러 클라이언트에게 발급되므로 발급자와 대상을 서버가 직접 봐야 한다. 검증기는 `global/security/jwt/SupabaseJwtValidators`(`JwtValidators.createDefaultWithIssuer` + `aud`에 `authenticated` 포함)이고 issuer는 `spring.security.oauth2.resourceserver.jwt.issuer-uri`(= `{SUPABASE_URL}/auth/v1`, jwk-set-uri와 같은 env에서 파생 — 새 env 없음)다. jwk-set-uri 문자열을 잘라 파생하지 않은 것은 그 규칙이 깨졌을 때 «모든 토큰이 401»로만 드러나 설정 오류와 코드 오류가 가려지기 때문이다. **통합 테스트의 스텁 `jwtDecoder`(ADR-0009)는 이 검증기를 지나지 않는다**(토큰 문자열이 곧 subject인 계약이라 iss·aud가 없다) — 그래서 `SupabaseJwtValidatorsTest`가 로컬 EC 키로 서명한 토큰을 실제 `NimbusJwtDecoder`에 넣어 iss 다름·aud 없음 거부를 본다. `NimbusJwtDecoder.withPublicKey`는 RSA 전용이라 EC 키는 `DefaultJWTProcessor` + `ImmutableJWKSet`으로 조립한다.
  - `global/security/jwt/SupabaseJwtAuthenticationConverter` — JWT의 `sub`(UUID)로 `MemberService.findByAuthUserId(...)`를 호출해 회원을 **조회만** 한다. **인증 시점에 회원을 만들지 않는다** — 만들어 버리면 "로그인은 했지만 아직 가입하지 않은 사용자"라는 상태가 존재할 수 없고, 조회 요청 하나에도 쓰기 트랜잭션이 열린다. `mbr` 행이 생기는 유일한 경로는 회원가입 API다. `MemberEntity.authUserId`(컬럼 `auth_user_id`)는 특정 인증 벤더 이름을 쓰지 않는다 — 지금은 Supabase Auth의 사용자 ID를 담지만 인증 수단이 바뀌어도 이 컬럼명은 유지된다. JWT의 `role` 클레임은 RLS용 Postgres 역할이라 인가 판단에 쓰지 않는다.
  - principal은 `MemberEntity`가 아니라 `global/security/AuthenticatedUser`다 — `authUserId`·`email`·`name`(`user_metadata.full_name`/`name`)·`provider`(`app_metadata.provider`)와 **nullable한 `MemberEntity`**를 담는다. 가입 여부는 별도 등급 코드가 아니라 `member == null`로 표현된다.
  - 회원이 필요한 엔드포인트는 principal을 캐스팅하지 말고 **`@CurrentMember MemberEntity`**로 받는다(`global/security/resolver`). 미가입 주체는 이 리졸버 한 곳에서 **403 `SIGNUP_REQUIRED`**(`MemberErrorCode`)로 끊기므로 주입된 값은 항상 non-null이다. 401(토큰 없음·무효)과 구분되며, 프론트는 이 코드를 받으면 재로그인이 아니라 가입 화면으로 보낸다. 리졸버 등록은 `global/config/WebConfig`.
  - principal에 실린 `MemberEntity`는 **준영속**이다(인증 필터는 트랜잭션 밖). 등급·상태 같은 지연 로딩 필드를 꺼내려면 식별자만 쓰고 회원 도메인 Service로 다시 조회해야 한다 — `MemberService.getProfile(memberId)`가 조회 트랜잭션 안에서 DTO로 굳혀 돌려준다.
  - **인가는 `@RequireAuthority` + AOP다** (`global/security/authorization`, #9). `GrantedAuthority`는 여전히 비워 둔다 — 역할·권한은 화면에서 바뀌는 사용자 관리 데이터라 인증 시점에 굳히면 이미 발급된 토큰의 판정이 따라오지 않고, 권한이 필요 없는 요청에도 조회가 붙는다. 애노테이션이 붙은 핸들러에서만 DB를 보는 **지연 판정**이며, 붙지 않은 엔드포인트는 종전처럼 인증만 요구한다. 클래스에 붙이면 그 컨트롤러 전체에 걸리고 메서드 애노테이션이 이긴다.
    - 판정 규칙의 **유일한 구현은 `domain/member/service/AuthorityPolicy`**다: 회원 → 유효한 역할(`role_bgng_ymd <= 오늘 <= role_end_ymd`, 종료일 NULL이면 무기한, 오늘은 주입된 `Clock`) → 그 역할들에 부여된 권한(`role_authrt_rel`) → **자손까지 펼침**. 펼침은 위→아래 한 방향이라 하위를 가졌다고 상위가 생기지 않으며, `mbr_role_rel.rprs_role_yn`(대표 역할)은 표시용이라 보지 않는다. `role.indct_seqno`를 서열로 쓰는 판정은 **쓰지 않는다** — 분류별 표시 순번이라 '프로젝트장(PROJECT 1)'이 '국장(POSITION 4)'보다 높게 계산된다.
    - **`SUPER`는 트리의 최상위이고 그게 전부다** (#71). `EXECUTIVE`가 그 자식이라 기존 펼침만으로 모든 권한을 포함하며, `AuthorityPolicy`에 `SUPER`를 특별 취급하는 분기는 **없다** — 넣지 말 것. 웹이 저장 전 미리 보기를 위해 트리 간선으로 같은 펼침을 한 번 더 하므로(`entities/authority/model/tree.ts.previewGrants`), 서버만 특별 취급하면 체크박스에는 한 줄만 켜지는데 저장하면 전부 열리는 상태가 된다. 새 권한 코드를 추가할 때 트리에 매달지 않으면 `SUPER`가 그것을 포함하지 못한다.
    - 권한(`authrt`)은 **단일 부모 트리**이며 자식을 가진 노드가 곧 묶음 권한이다. 상위 지정 시 조상 검사로 순환을 막고(`AuthorityEntity.changeParent`), 그래도 고리가 남은 데이터를 만나면 펼침이 방문 집합으로 멈춘다. `sys_yn = true`는 코드(`AuthorityCode`)가 직접 가리키는 권한이라는 표시다 — 삭제·코드 변경을 막아야 화면 조작 한 번으로 인가가 무력화되지 않는다.
    - 거절 응답은 `@CurrentMember`와 같은 계단이다: 미인증 401 · 미가입 403 `SIGNUP_REQUIRED` · 권한 부족 403 `FORBIDDEN`(`MemberErrorCode.AUTHORITY_REQUIRED`). **404로 감추지 않는다.** 다만 애스펙트는 인자 해석 뒤에 돌므로 본문이 `@Valid`를 어기면 권한 없는 요청도 400을 먼저 받는다.
    - 화면에는 역할이나 트리가 아니라 **펼친 결과**를 내린다 — `MemberProfileResponse.capabilities`(`GET /v1/auth/session`, ssccops-web#29). 애스펙트와 같은 정책을 쓰므로 버튼과 실제 판정이 갈리지 않는다.
    - 승인자 판정(`ApprovalAuthorityPolicy`)은 이것과 층이 다르다 — 그쪽도 판정 재료는 권한(#123, 유형이 지정한 결재 권한·`APPROVAL_VOTE`)이지만 유형마다 요구 코드가 달라 `@RequireAuthority` 하나로 고정되지 않아, 서비스 레이어에서 `AuthorityPolicy`의 펼침으로 건별 판정한다.
  - 인증 실패(서명/만료 오류 등)는 `CustomAuthenticationEntryPoint`, 권한 부족은 `CustomAccessDeniedHandler`가 `ApiResponse` 포맷으로 응답한다.
  - `SecurityConfig`가 필터체인을 구성: CSRF/formLogin/httpBasic/logout 비활성화, `SessionCreationPolicy.STATELESS`, 비prod Swagger 경로와 `/actuator/health`·`/actuator/info`, 그리고 **`/public/v1/**`**만 permitAll(배포 헬스 프로브는 토큰을 붙일 수 없다 — `prometheus`·`metrics`·`loggers`는 계속 보호), 그리고 **`/.well-known/oauth-protected-resource`**(#384 · MCP 클라이언트가 401 뒤에 읽는 RFC 9728 메타데이터 — 서비스 데이터가 아니라 설정에서 온 상수라 헬스 프로브·Swagger 부류다, 아래 «MCP» 절), 나머지는 인증 필요. **`/public/v1/**`는 업무 API 중 익명 접근이 허용되는 유일한 층이다**(#156 · 공개 행사 조회, ssccops#143 D1 · 행사 이미지 리다이렉트 `PublicEventImageController`, #208 — 본문 이미지는 상세와 같은 화면에서 열리므로 그쪽만 토큰을 요구하면 화면이 성립하지 않고, 카카오톡·에브리타임 OG 크롤러도 이 주소를 열어 302를 따라간다) — 예외를 엔드포인트 목록이 아니라 경로 접두사로 표현한 것은, `/v1/**` 아래에 익명 경로를 섞으면 여기가 경로별 permitAll 표가 되고 한 줄이 빠지거나 패턴이 넓게 잡히는 것으로 인증이 필요한 자원이 열리기 때문이다. 그러므로 **이 접두사 아래에 핸들러를 더하는 것은 permitAll을 더하는 것과 같다**. 폼의 '공개' 경로(`/v1/forms/{id}/public`)와 갈리는 지점이며 그쪽은 여전히 인증이 필요하다(응답자는 전원 회원이다). **경로별 권한 규칙을 여기 두지 않는다** — `RoleHierarchy`(ADMIN ⊃ USER ⊃ PREUSER)와 `/admin/**`은 #9에서 제거했다(실제 어휘와 대응되지 않는 잔재였고, 남기면 권한 어휘가 두 벌이 된다).
  - CORS는 `SecurityConfig.corsConfigurationSource()` 한 곳에서만 정의한다. 허용 오리진은 `frontend.url`이며 **쉼표로 여러 개**를 넣을 수 있다(Cloudflare Workers 프리뷰 도메인 대응). 시큐리티 필터체인이 먼저 처리하므로 `WebMvcConfigurer.addCorsMappings()`로 중복 정의하지 말 것 — `WebConfig`는 인자 리졸버 등록에만 쓴다.
- 관측성: OpenTelemetry(OTLP) + Micrometer(Prometheus/OTLP)가 연결돼 있고 dev·prod 모두 export는 꺼져 있다. 로컬에 OTLP collector가 없으면 애플리케이션 종료 시 `Connection refused` 경고가 뜨는데 무해하다. **로그 필드의 정본은 [`deploy/kibana/log-schema.md`](deploy/kibana/log-schema.md)다** (#411 · ssccops#341) — 필드 · ECS 경로 · 타입 · 어느 줄에 · 값 규칙 · 예시 · 싣지 않는 것. 필드를 더하거나 이름을 바꾸면 **표부터 고치고** 코드와 테스트(`EcsJsonEncoderTest`·`AuditLogTest`·두 핸들러 테스트)를 맞춘다. 아래 bullet들은 «왜 그 모양인가»이고, «무엇이 나가는가»는 표가 말한다.
- **로그는 ECS 구조의 JSON이고 모양은 `global/logging/EcsJsonEncoder` 한 곳이 정한다** (#366 · ssccops#298 · [ADR-0024](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0024-log-contract-ecs-datasets-retention.md)). 필드는 **중첩**이다 — `{"log":{"level":…}}`이지 `{"log.level":…}`가 아니다. Elasticsearch는 둘을 같게 보지만 Logstash 파이프라인의 `[event][dataset]` 조건은 중첩 키만 찾는다. 내는 것은 `@timestamp` · `message` · `log.level` · `log.logger` · `process.thread.name` · `service.name` · `service.environment`(활성 프로파일) · **`service.version`**(#411 — `META-INF/build-info.properties`의 `build.version`을 인코더가 **직접** 읽는다. logback 컴포넌트라 스프링 빈을 못 받고, `<springProperty>`로 넘기면 출처가 설정이 되어 버전이 두 벌이 된다. `/actuator/info`·`BuildVersionLogger`·배포 이력과 같은 파일이 출처다. 파일이 없으면 키를 내지 않는다 — `unknown`을 지어내면 Kibana에서 버전처럼 세어진다) · MDC에 있을 때만 `trace.id`·`span.id` · 예외가 있을 때만 `error.type`·`error.message`·`error.stack_trace` · 그리고 `StructuredArguments`로 넘긴 객체 전부(감사 로그가 이 길로 `event`·`user`·`audit`을 싣는다). **`event.dataset`은 인코더가 박지 않는다** — 일반 로그는 Logstash가 `ssccops.application`을 기본값으로 채우고, 감사 로그는 `AuditLog`가 `event` 객체를 인자로 싣는다(인코더가 먼저 내면 같은 키가 두 번 나가 JSON이 깨진다). XML이 아니라 코드인 것은 stdout과 Logstash TCP 두 appender가 같은 모양을 내야 하는데 logback이 encoder 정의를 공유하지 못해서다 — `EcsJsonEncoderTest`가 필드 이름을 못 박는다.
  - **appender는 셋이다** (`logback-spring.xml`): `STDOUT_TEXT`(dev·local·test, 사람이 읽는 한 줄) · `STDOUT_JSON`(prod, Coolify Runtime Logs가 보는 것) · **`LOGSTASH`(`LOGSTASH_HOST`가 있을 때만, 프로파일과 무관)**. Logstash는 프로파일이 아니라 환경변수로 켠다 — 프로파일로 가르면 local에서 붙여 볼 수 없고, 무조건 등록하면 Logstash가 없는 환경에서 5초마다 재접속 경고가 쌓인다. 조건은 logback 1.5.22의 `IsPropertyDefinedCondition`이라 **Janino가 필요 없다** — `<condition>`이 `<if>`의 자식이 아니라 **바로 앞 형제**인 것이 그 문법이며(`*/condition` 규칙), 옛 `<if condition='…'>` 속성은 Janino를 요구하고 deprecated다. `LOGSTASH_PORT` 기본 5000 · 재접속 5초 · keepAlive 5분 · 링 버퍼 8192줄 · **종료 유예 5초**(기본 1분인데 Logstash가 없는 채로 내려가는 컨테이너가 1분을 붙들면 배포가 그만큼 늦는다). **appender가 비동기이고 링 버퍼가 넘치면 버리므로 «Logstash에 보냈으니 반드시 보존된다»가 아니다** — 감사 로그도 같다(ADR-0024가 감수한 대가). Coolify에서는 `LOGSTASH_HOST=logstash-<ES 스택 uuid>`(같은 coolify 네트워크의 컨테이너 이름)이다.
  - **감사 로그는 `global/audit/AuditLog` 한 곳이 남긴다** (#367 · ssccops#299 · ADR-0024). 서비스가 `auditLog.record(AuditEvent.success(AuditAction.MEMBER_GRADE_CHANGE).target(id).change(before, after).build())`를 부른다 — SLF4J를 직접 쓰면 필드 이름이 자리마다 갈린다. `AuditLog`가 `event.dataset=ssccops.audit`·`event.action`·`event.outcome`·`user.id`(SecurityContext의 회원 id, 미가입이면 auth user id + `signed_up=false`, 요청 밖이면 `system`)·`source.ip`(`X-Forwarded-For` 첫 값)를 채우고 나머지(`audit.target.*`·`audit.changed_fields`·`audit.change.before/after`·`audit.decision`·`error.code`)를 StructuredArguments로 싣는다. 로거 이름은 `AUDIT`.
    - **성공은 커밋 뒤, 실패는 즉시.** 트랜잭션 안에서 부른 성공 사건은 `afterCommit`에 쓰인다 — 롤백된 변경을 «성공»으로 남기지 않기 위해서다. 실패(409·403)는 예외로 나가는 길이라 롤백이 확정이고 커밋이 오지 않으므로 즉시 쓴다. **그래서 성공 감사를 검증하는 테스트에는 `@Transactional`을 걸 수 없다**(`AuditPointsTest`가 전용 H2에서 실제로 커밋한다 — `MemberChangeRollbackTest`와 같은 이유).
    - **개인정보가 실릴 자리가 없다.** `AuditEvent`는 대상 식별자·바뀐 필드 **이름**·코드값 전후·결정 분류만 받는다. 프로필 수정은 `MemberProfileSnapshot.changedFieldNames`가 이름만 넘기고, 사유 문장(`request.reason()`·`grdChgRsnCn`)은 사람이 쓴 글이라 싣지 않는다. `AuditLogTest`가 출력에 전화·이메일 모양이 없는지 본다. 새 감사 지점에서 **값을 실어 보내는 인자를 열지 말 것.**
    - **1차 대상은 `AuditAction`이 전부다** — 가입·연결·상세 조회(본인 제외, 컨트롤러에서)·프로필 수정·등급·상태·역할 부여/종료·역할별 권한 교체·하드 삭제(409도 failure)·명부 가져오기·하위 업무 전이·투표·응답 검토·학술 활동/회차 전이·행사/폼 상태 전이·`@RequireAuthority` 거절. 사건을 더하려면 enum에 한 줄 + 부르는 자리 하나. **목록 조회는 남기지 않는다** — 상세 1건이 개인정보가 드러나는 자리이고 목록까지 남기면 «누가 봤나»의 신호가 스크롤에 묻힌다. 로그인 자체는 Supabase에서 일어나 서버가 모른다.
    - **채널 — `client.id`** (#384 · ssccops#314 · ADR-0026). 토큰에 `client_id` 클레임이 있으면(Supabase OAuth 2.1 서버가 발급한 MCP 토큰) 그 값을 `client.id`로 싣고, 없으면(웹 로그인 토큰) `client` 객체 자체를 내지 않는다 — «웹»이라는 값을 지어내면 그것이 두 번째 사실이 된다. 같은 회원이 같은 일을 웹에서 했는지 Claude가 했는지를 이것으로 가른다. 값은 클라이언트 식별자뿐이며 토큰 원문은 없다(`AuditLogTest`가 본다). API Key가 생기면 같은 `client` 객체에 `api_key_id`가 들어갈 자리다.
    - 로깅이 업무를 막지 않는다 — `AuditLog` 안의 예외는 삼키고 WARN 한 줄이다.
  - **401·403 로그에는 `url.path`·`http.request.method`·`user_agent.original`이 실리고 `/mcp`·`/.well-known/**`의 401은 INFO다** (#389 · ssccops#319). `CustomAuthenticationEntryPoint`·`CustomAccessDeniedHandler`가 `global/security/handler/RequestLogFields`로 그 셋을 `StructuredArguments.kv`에 실어 인코더가 중첩 객체로 낸다(`AuditLog`가 `user`·`source`를 싣는 것과 같은 길). 그전까지 `Authentication failed: Full authentication is required…` 한 문장뿐이라 `/mcp` 발견 프로브·만료된 웹 토큰·스캐너를 가를 수 없었고, MCP를 켠 뒤로는 «토큰 없이 `/mcp`를 쳐서 401을 받는 것»이 정상 연결의 첫 단계라 매번 WARN이면 진짜 경고가 묻힌다 — 판정은 `McpProtectedResource.isMcpRequest`(문자열을 두 벌 두지 않는다)이고 403은 경로와 무관하게 WARN이다(세 층의 403이 `FORBIDDEN` 하나라 경로가 곧 단서). **싣지 않는 것**: 쿼리 문자열 · `Authorization` 값(로그 열람 권한이 인증 권한이 된다) · `source.ip`(감사 로그에만 — ADR-0024와 같은 축). 별도 EntryPoint·Rate limit은 기각. 두 핸들러 테스트가 `EcsJsonEncoder`로 굳혀 필드 존재·토큰 부재·레벨을 본다.
  - **Logstash 파이프라인·compose 조각의 정본은 `deploy/logstash/`다** (#368 · ssccops#297). Coolify의 `elasticsearch-with-kibana` 스택에 붙여 넣은 것은 사본이며 `Dockerfile`과 같은 관계다. 파이프라인은 파싱하지 않고 `event.dataset`으로 `logs-ssccops.{application|audit}-{service.environment}` data stream에 라우팅만 한다. ES 자격은 `elastic`이 아니라 generator가 만드는 `logstash_writer` 사용자(`logs-ssccops.*` 쓰기만)이고 — API key는 Logstash가 HTTP 위에서 거부한다 — 어디에 붙이고 비밀번호를 어떻게 발급·회전하는지, Coolify가 `content:`를 처음 한 번만 파일로 만든다는 함정까지 `deploy/logstash/README.md`에 있다.
  - **Kibana·ES 설정의 정본은 `deploy/kibana/`다** (#372 · ssccops#300). `setup.sh`가 ILM(일반 14일 · 감사 365일) · 인덱스 템플릿 · Data View · Kibana 역할(`ssccops-developer` dev 전부+prod 일반·편집 / `ssccops-administer` prod 전부 / `ssccops-operator` prod 일반 — dev/prod는 stream 이름으로 갈리고 Data View·대시보드는 하나다) · 대시보드 import를 idempotent하게 한다. 대시보드를 화면에서 고쳤으면 다시 export해 `saved-objects.ndjson`을 덮어쓴다 — 정본이 레포다.

## MCP — Claude가 붙는 두 번째 인터페이스 (#384 · ssccops#314)

`https://<host>/mcp`(Streamable HTTP · **stateless**)로 Claude(claude.ai · Desktop · Claude Code)가 붙는다. 결정은
[ADR-0026](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0026-mcp-auth-reuses-supabase-oauth-server.md)(인증)·[ADR-0027](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0027-mcp-server-in-process-tools-call-rest.md)(배치)에 있고, 코드는 전부 `global/mcp/`다.

- **Spring AI는 1.1.x(1.1.8)에 머문다.** 2.0.x는 Boot 4 · Framework 7 · Java 21이 필요해 이 서버(Boot 3.5 · Java 17)에서는 로드조차 되지 않는다. `build.gradle`의 `spring-ai-bom` 줄을 올리지 말 것 — Boot 4 이관은 별도 Epic이다. 스타터는 `spring-ai-starter-mcp-server-webmvc`(MCP Java SDK 0.18.x). `spring.ai.mcp.server.version`은 MCP 인터페이스의 버전이지 릴리스 버전이 아니다 — 도구 계약이 호환되지 않게 바뀔 때 올린다.
- **인증은 웹과 같은 Supabase JWT다.** 토큰은 Supabase OAuth 2.1 서버가 발급하고(`client_id` 클레임이 달린다) 필터체인·`SupabaseJwtAuthenticationConverter`·`@RequireAuthority`·`AuditLog`가 **수정 없이** 그대로 돈다(ADR-0026). API Key는 없다. 서버가 더한 것은 리소스 서버 메타데이터뿐이다 — `/mcp`의 미인증 401에 `WWW-Authenticate: Bearer resource_metadata="<base>/.well-known/oauth-protected-resource/mcp"`(`CustomAuthenticationEntryPoint` · **다른 경로의 401은 그대로다**)와 그 문서(`McpProtectedResourceMetadataController` · 루트 경로와 `/mcp` 붙은 경로 둘 다 · `ApiResponse` 봉투 없음). 값은 `McpProtectedResource` 한 곳 — `resource` = `app.public-base-url` + `/mcp`, `authorization_servers` = JWT 검증의 `issuer-uri`와 **같은 값**(갈리면 로그인은 되는데 401이 나는 상태가 된다), `scopes_supported` = openid·email·profile(Supabase가 지원하는 넷 중 phone은 뺐다 — 연락처는 도구 출력에서도 걷어내는 값이다).
  - **`org.springaicommunity:mcp-server-security` 0.0.6(1.1.x용)은 쓰지 않았다.** Boot 3.5.9와 의존성은 맞지만(Security 6.5), 그 configurer가 `withIssuerLocation`으로 `JwtDecoder`를 새로 만들어 우리 `supabaseJwtDecoder`(ES256 목록 · iss/aud 검증기 #383)를 밀어내고, EntryPoint 위임이 본문 없는 `BearerTokenAuthenticationEntryPoint`로 굳어 있어 ApiResponse 포맷과 갈리며, resource URL을 요청 Host에서 조립해 Coolify 프록시 뒤에서는 forwarded 헤더 설정이 또 필요하다. 손으로 쓴 것이 수십 줄이다.
  - **호스트는 새 프로퍼티가 아니라 `app.public-base-url`이다.** «이 API 자신의 공개 주소»가 이미 배포의 속성으로 있고(#216 · dev `DEV_APP_PUBLIC_BASE_URL` · prod `PROD_APP_PUBLIC_BASE_URL`), 같은 사실을 두 벌 두면 한쪽만 바뀐다. 새 env 없음.
- **도구는 서비스를 직접 부르지 않고 `RestClient`로 `http://localhost:${PORT}/v1/…`를 자기 호출한다**(ADR-0027 · #385). 인가(`@RequireAuthority`)·검증(`@Valid`)·미가입 차단(`@CurrentMember`)·감사가 전부 컨트롤러 계층에 있어서다 — 서비스를 직접 부르면 1층 인가가 빠지고, 빠뜨린 도구는 조용히 열린 채 배포된다. 포트는 `server.port`(= `PORT`)이며 **8080을 가정하지 않는다**(Coolify가 dev 80 · prod 3000을 넣는다). 입력·출력 타입은 기존 Request/Response record 그대로다 — 도구 스키마를 따로 쓰면 ssccops#305(서버·웹 계약 갈림)가 재현된다.
- **도구 실행 스레드에 SecurityContext가 있다 — 실제로 쟀다**(`McpToolExecutionContextTest` · 실제 포트 + MCP Java 클라이언트). Spring AI가 서블릿 환경의 SYNC 서버에 `immediateExecution(true)`를 걸어(stateless 자동 구성도 같다) 도구가 `POST /mcp` 요청 스레드(`http-nio-*`)에서 동기로 돌기 때문에 필터체인이 넣은 인증이 `SecurityContextHolder`에 그대로 보인다. 그 조건이 없으면 MCP SDK가 `Schedulers.boundedElastic()`으로 넘겨 비어 있다 — 라이브러리 내부 결정이라 버전이 오르며 바뀔 수 있다. **그래서 `McpServerConfig`가 전송 빈을 직접 만들어 `contextExtractor`로 Authorization 헤더를 `McpTransportContext`에도 담는다**(키 `authorization`, 도구 메서드가 `McpTransportContext`를 받아 `context.get(...)`). 도구 쪽 클라이언트는 SecurityContext의 `SupabaseAuthenticationToken.getCredentials()`(Jwt)를 먼저 보고 없으면 전송 컨텍스트를 본다. 전송 빈을 직접 만드는 것은 자동 구성이 `@ConditionalOnMissingBean`이라 허용된 길이며 엔드포인트는 같은 프로퍼티(`streamable-http.mcp-endpoint`)에서 읽는다.
- **전송은 stateless다 — 세션 없음, 재배포와 무관** (#393 · ssccops#314 · `spring.ai.mcp.server.protocol=STATELESS` · `WebMvcStatelessServerTransport`). 처음(#384)에는 세션 있는 `STREAMABLE`이었고 «세션은 메모리에 있어 재배포마다 끊기며 클라이언트가 재연결한다»를 ADR-0027이 감수한 대가로 적어 두었는데, **2026-09-13 dev 실측은 그 전제가 틀렸음을 보여 줬다** — Redeploy 뒤 Claude Code가 옛 `Mcp-Session-Id`로 POST → 404 → 클라이언트가 «failed to connect»로 굳고 자동 재초기화가 없었다(사용자가 손으로 다시 붙여야 했다). 우리 도구는 요청마다 Bearer로 신원을 받고 서버에 아무 상태도 두지 않으므로 세션이 있어야 할 이유가 처음부터 없었다. stateless는 `Mcp-Session-Id`를 내지도 요구하지도 않고, initialize 없이 온 `tools/call`도 그대로 처리한다(`McpToolExecutionContextTest` — 서버가 모르는 세션 id를 달고 와도 200 · `McpStatelessRedeployTest` — 서버를 내렸다 같은 포트에 다시 올린 뒤 **같은 클라이언트**가 재초기화 없이 도구를 부른다). 세션을 Redis 같은 외부 저장소에 두는 안은 기각 — 저장할 상태가 없다. SSE 전송은 쓰지 않는다(스펙 폐기 예정). 리소스·프롬프트·completion capability는 껐다 — 켜 두면 빈 목록을 광고한다. `spring.ai.mcp.server.streamable-http.mcp-endpoint`와 `McpProtectedResource.MCP_PATH`는 같은 값이어야 한다(프로퍼티 이름은 `streamable-http`지만 stateless 자동 구성도 같은 클래스를 읽는다). `GET /mcp`는 405다(stateless는 서버→클라이언트 스트림이 없다).
  - **오류 본문에서 예외 객체를 걷어낸다.** MCP Java SDK의 전송은 4xx·5xx 본문에 `McpError`(RuntimeException) **객체 자체**를 싣고(`ServerResponse.body(new McpError(...))`) Spring MVC가 그것을 Jackson으로 직렬화하면 `cause`·`stackTrace`·`suppressed`가 프레임 단위로 나간다 — 실측한 404 본문이 그것이었다(`WebMvcStreamableServerTransportProvider.handlePost` 프레임까지). stateless가 되며 404 경로는 사라졌지만 같은 패턴이 잘못된 JSON-RPC 본문의 400(`Invalid message format`)과 핸들러 실패의 500에 남아 있다. `@ControllerAdvice`는 이 응답을 잡지 못한다(예외가 아니라 정상 반환된 `ServerResponse`다) — 그래서 `McpServerConfig`가 라우터 함수 빈(`webMvcStatelessServerRouterFunction` — **이름이 자동 구성의 `@ConditionalOnMissingBean(name=…)`과 같아야** 라우터가 둘이 되지 않는다)도 직접 만들어 필터를 걸고, 본문이 `Throwable`인 응답을 상태 코드는 그대로 둔 채 `ApiResponse` 오류 봉투(4xx `INVALID_BODY` · 5xx `INTERNAL_SERVER_ERROR`, 메시지는 SDK의 문장 그대로)로 바꿔 낸다. 앱 ObjectMapper에 Throwable 믹스인을 거는 안은 기각 — 모든 응답에 영향이 가고 어느 경로가 예외를 내보내는지가 코드에 남지 않는다. `McpToolExecutionContextTest`가 400 본문에 `stackTrace`·`cause`가 없음을 본다.
- **1차 도구 9종은 `global/mcp/tool/OperationTools` 하나다** (#385). 읽기 7 + 하위 업무 전이·체크리스트. **update·create·삭제·회원·폼·역할 도구는 없다** — 운영 도메인 PATCH가 전체 교체라(분석 문서 F2) 모델의 부분 호출이 본문·완료 기준·외부 링크를 지운다. 도구 이름과 REST 짝:

  | 도구 | REST | 권한 | 출력 타입 |
  |---|---|---|---|
  | `list_operations` | `GET /v1/operations` | `WORK_MANAGE` | `OperationHubResponse` |
  | `get_work` | `GET /v1/works/{id}` | `WORK_READ` | `WorkDetailResponse` |
  | `list_sub_works` | `GET /v1/sub-works` (조건 = `SubWorkSearchCondition` 그대로) | `WORK_READ` | `McpListResult<SubWorkSummaryResponse>` |
  | `get_sub_work` | `GET /v1/sub-works/{id}` | `WORK_READ` | `SubWorkDetailResponse` |
  | `list_meetings` | `GET /v1/meetings` | `MEETING_READ` | `List<MeetingListItemResponse>` |
  | `get_meeting` | `GET /v1/meetings/{id}` | `MEETING_READ` | `MeetingDetailResponse` |
  | `get_me` | `GET /v1/auth/session` | 인증만 | `AuthSessionResponse` |
  | `transition_sub_work` | `POST /v1/sub-works/{id}/transitions` (`SubWorkTransitionRequest`) | 서버 3층 판정 | `SubWorkTransitionResponse` |
  | `check_sub_work_item` | `PATCH /v1/sub-works/{id}/checklist/{itemId}` (`SubWorkChecklistItemUpdateRequest`) | 담당자 | `SubWorkChecklistItemUpdateResponse` |

  - **공통 클라이언트는 `global/mcp/client/McpRestClient` 한 곳이다.** 주소 `http://localhost:{local.server.port ?: server.port}`(웹 서버가 뜬 뒤 스프링이 넣는 실제 포트 — RANDOM_PORT 테스트도 이것) · 연결 2s · 읽기 10s(Rate limit이 없는 서버라 도구 루프가 API를 붙드는 시간을 여기서 자른다) · Bearer는 `BearerTokenSource`(SecurityContext의 Jwt → 전송 컨텍스트 순) · `ApiResponse` 봉투는 여기서만 벗긴다 · `success:false`면 `McpToolException(code, message)`로 — mcp-annotations가 도구 예외를 `isError=true` + 근본 원인 메시지로 바꾸므로 **메시지가 곧 모델이 읽는 전부**다. `FORBIDDEN`·`SIGNUP_REQUIRED`는 «재시도해도 결과는 같습니다 — 사용자에게 알리고 멈추세요»를 문장에 박는다(세 층의 403이 전부 `FORBIDDEN` 하나라 모델에게 줄 수 있는 것이 그것뿐이고, 없으면 인자를 바꿔 가며 되풀이한다). 나머지는 `[code] message`. 상태 코드로 던지지 않고 `exchange`로 본문을 읽는 것은 4xx에도 ApiResponse가 실려 오기 때문이다.
  - **자기 호출에 원 요청의 `X-Forwarded-For`를 그대로 싣는다** (#390 · ssccops#314). localhost 호출은 안쪽 요청의 remoteAddr가 127.0.0.1이라 `AuditLog`의 `source.ip`가 언제나 루프백이었다(2026-09-13 dev `subwork.transition` 실측 — `client.id`는 찍히는데 IP가 없었다). `McpServerConfig.contextExtractor`가 Authorization을 담는 자리에 원 요청의 `X-Forwarded-For`(없으면 `getRemoteAddr()`)를 키 `x-forwarded-for`로 함께 담고 `McpRestClient`가 같은 이름의 헤더로 넘긴다 — **체인은 가공하지 않는다**(첫 값을 고르는 것은 `AuditLog` 한 곳). `RequestContextHolder`로 꺼내는 길은 두지 않았다 — 토큰과 달리 두 길의 값이 같아 우선순위를 둘 이유가 없고 그 보장도 같은 스레드 조건에 매여 있다. 프록시 신뢰 목록은 없다(Coolify Traefik 뒤라 이미 XFF가 신뢰된다). `McpRestClientTest`(체인 원문 전달·없으면 헤더 없음)와 `OperationToolsIntegrationTest`(전이 뒤 감사 줄의 `source.ip` = 보낸 XFF 첫 값)가 본다.
  - **목록은 커서를 최대 3페이지(`MAX_PAGES`)까지만 따라간다.** `McpListResult{items, pagesFetched, hasMore, nextCursor, totalCount}`로 돌려주고 항목 타입은 REST record 그대로다. 검색 조건 record는 Jackson으로 Map으로 바꿔 쿼리에 싣는다(필드 이름 = 파라미터 이름 · null 제외 · 컬렉션은 같은 이름 반복). **값은 URI 템플릿 변수로 넣는다** — 문자열로 이어 붙이면 `+09:00`의 `+`가 서버에서 공백이 된다.
  - **개인정보는 `ToolOutputRedactor`가 JSON 트리에서 이름으로 걷어낸다** — `phoneNumber` · `email` · `studentNumber`(깊이 무관, `MemberSummaryResponse`가 담당자·등록자·발표자 자리마다 중첩되어 있어서다). 이름(`name`)은 남긴다 — 담당자가 누구인지 없이는 운영 도구가 성립하지 않는다. 도구마다 출력용 뷰 record를 만들지 않은 것은 응답 record에 필드가 늘 때 한쪽만 늘어 갈리고 새 도구가 뷰를 빠뜨리면 조용히 새기 때문이다. 걷힌 필드는 출력에 `"phoneNumber":null`처럼 값 없는 키로 남는다(mcp-annotations 직렬화는 null을 빼지 않는다). 키를 더하면 이 표도 고친다.
  - **도구 호출 로그는 도구 이름·대상 id만** INFO로(`OperationTools`). 전이 사유 같은 인자 본문은 싣지 않는다 — ADR-0024의 값 미탑재 원칙이다. 감사 로그는 REST를 지나며 기존 지점이 그대로 찍히고 `client.id`로 웹과 갈린다.
  - **`McpTransportContext` 인자는 입력 스키마에 나타나지 않는다**(mcp-annotations가 `McpTransportContext`·`CallToolRequest`·`McpMeta`를 특수 인자로 뺀다). **stateless에서 도구가 받을 수 있는 컨텍스트 인자는 `McpTransportContext`뿐이다** — `McpSyncServerExchange`는 세션 있는 서버의 것이라 stateless 콜백(`SyncStatelessMcpToolMethodCallback`)이 특수 인자로 빼지 않고 일반 입력으로 취급하며, `McpSyncRequestContext`는 `UnsupportedOperationException`이다. 도구 메서드에 그 둘을 쓰지 말 것. `@McpToolParam(required=false)`가 선택 인자, 이름은 `-parameters` 컴파일 플래그(Boot 플러그인 기본)에서 온다. 기존 record 인자(`SubWorkSearchCondition` 등)는 victools가 스키마를 만든다 — `@Min/@Max`는 스키마에 반영되지 않으므로 서버의 400 메시지가 곧 안내다.
  - 테스트: `McpRestClientTest`(JDK HttpServer로 REST를 흉내 — 봉투·오류 변환·3페이지·타임아웃·Bearer 우선순위) · `ToolOutputRedactorTest` · `OperationToolsIntegrationTest`(실제 포트 + MCP 클라이언트 · 이 클래스만의 H2에 가입 API로 SUPER·역할 없음·미가입 세 신원을 만들어 `get_me`·`list_sub_works`·403·SIGNUP_REQUIRED·404를 본다) · `McpStatelessRedeployTest`(`@SpringBootTest` 없이 `SpringApplicationBuilder`로 같은 포트에 두 번 띄운다 — **포트·DB는 `run("--server.port=…")` 인자로 줘야 한다**, `builder.properties()`는 기본 프로퍼티라 `application.yaml`의 `${PORT:8080}`에 밀려 8080에 떴다 · 두 번째 부팅이 `ddl-auto: create`로 스키마를 다시 만들므로 공용 testdb가 아니라 전용 H2). **HttpServer 기본 executor는 단일 스레드**라 느린 핸들러 테스트가 다음 테스트를 물고 늘어진다 — `setExecutor` 필수.
- **로컬에서 붙여 보기**: `./gradlew bootRun` 뒤 Claude Code에서 `claude mcp add --transport http ssccops http://localhost:8080/mcp --header "Authorization: Bearer <Supabase 액세스 토큰>"`. dev·prod는 헤더 없이 URL만 넣으면 401 → 메타데이터 → Supabase 동의 화면(ssccops#315 · #316)으로 이어진다.
- 테스트 함정: `@McpTool` 빈은 어느 컨텍스트에서든 스캐너가 등록한다 — 테스트 전용 도구는 `@TestConfiguration` + `@Import`로 그 테스트에만 둔다. MCP 클라이언트의 초기화 실패는 예외를 두 겹으로 감싸므로 401 본문은 원인 사슬을 따라가야 보인다.

## 규정 도우미 (RAG) — Gemini 배선 (#395 · #396 · Epic ssccops#321)

**도메인 규칙의 정본은 [domain/assistant/AGENTS.md](src/main/java/org/sscc/ssccopsserver/domain/assistant/AGENTS.md)다** —
판본 테이블·두 축 상태·청크 저장소 포트·기능 플래그·`V10` 스키마·두 갈래 파싱이 거기 있다. 이 절은
**무거운 의존성을 어떻게 골랐고 무엇을 실측했는가**만 남긴다 — 모델 쪽 배선(Gemini)과 Tika다.

**스키마·배선(#396) · 구조화 파서(#397) · 평문 추출기와 고정 길이 청커(#398) ·
업로드(#399) · 색인 워커(#400) · 목록·상세·적용 전환·재색인·삭제(#401) · 질의(#403) ·
질의 레이트 리밋(#404) · 골든셋(#405) · 대화 메모리(#406 · Phase 2)까지 왔다 — 서버 몫은 끝났고
남은 것은 웹(ssccops-web#434)이다.** 기능 플래그
`ssccops.assistant.enabled`는 기본이 **꺼짐**이다 — 그 플래그가 **질의와 워커를 함께 닫는다**(질의만
닫으면 워커가 계속 임베딩을 부르는데, 끄는 이유가 대개 쿼터다). 워커의 자동 실행에는 스위치가
하나 더 있다: `ssccops.assistant.indexing.auto`(기본 켬 · `test` 프로필만 끈다).

**손잡이 18개의 선언은 `application.yaml`의 `ssccops.assistant` 블록 한 곳이다** (#439). 기본값과
«왜 그 값인가»가 거기 있고, 자바 쪽 `@Value`는 **기본값 없이 키만 참조한다** — 두 벌로 두면
한쪽만 바뀌기 때문이며, 그래서 yaml에서 줄을 지우면 부팅이 `Could not resolve placeholder`로
실패한다(`ddl-auto: validate`와 같은 판단이다). 예외는 유형별 임계값 둘
(`similarity-threshold-structured`·`-generic`)로, 그쪽은 **«값 없음»이 «공통값을 쓴다»는 뜻**이라
선언 자체가 의미를 바꾸므로 `@Value`가 `#{null}`을 그대로 들고 있다.

**값을 바꾸는 곳은 여전히 환경변수다** — dev·prod는 Coolify, 로컬은 `.env`(`.env.example`이
변수 이름 18개를 목록으로 들고 있고 설명은 갖지 않는다). 그리고 **프로필 파일
(`application-dev.yaml`·`-prod.yaml`)에는 이 키들을 적지 않는다**: 베이스에 `enabled: false`를
선언한 것은 자리를 만든 것이지 켠 것이 아니며, 막는 것은 «어느 프로필에 `true`를 굳히는 것»이다.

- **스타터 둘** — `spring-ai-starter-model-google-genai`(채팅) · `…-google-genai-embedding`.
  **BOM 줄은 늘지 않았다**(MCP가 이미 쓰는 `spring-ai-bom:1.1.8`). **2.0.x로 올리지 말 것** —
  Boot 4 · Framework 7 · Java 21이 필요하다(MCP 절에서 못 박은 자리와 같다).
  `spring-ai-starter-model-vertex-ai-gemini`가 **아닌** 이유는 그쪽이 GCP 프로젝트·결제 계정을
  요구하는데 이 기능의 전제가 「비용 0」이기 때문이다. 이 스타터는 같은 코드에서 두 백엔드를
  고르며 `…embedding.vertex-ai`의 기본값이 `false`라 **AI Studio 무료 키 하나로 선다** —
  **그 값을 켜지 말 것**(켜는 순간 project-id·location을 요구한다).
- **프로퍼티 이름의 정본은 jar의 `spring-configuration-metadata.json`이지 docs.spring.io가
  아니다.** 공개 문서는 `…embedding.text.dimensions`라고 적는데 1.1.8에 그런 키는 없다
  (`…embedding.text.options.dimensions`다). 값을 더할 때도 문서가 아니라 jar를 확인할 것.
- **키가 없으면 배선이 서지 않는다** — `global/config/GeminiWiringEnvironmentPostProcessor`
  (`META-INF/spring.factories`로 등록. EnvironmentPostProcessor는 Boot 3에서도 자동 구성
  imports가 아니라 이 파일이다). 프로퍼티로 끄지 않는 이유는 **`GoogleGenAiEmbeddingConnection
  AutoConfiguration`에 조건이 아예 없어서**다 — `spring.ai.model.embedding.text=none`으로도
  그 빈은 만들어지고, 키가 비면 Vertex AI 경로로 흘러 `project-id must be set!`에서 죽는다.
  실제로 그 상태에서는 **규정 도우미와 무관한 모든 테스트와 부팅이 함께 실패한다**(확인함).
  그래서 셋(`spring.ai.model.chat` · `spring.ai.model.embedding.text` · 제외 목록)을 **한 가지
  사실**에서 함께 끈다. **제외 목록에는 #396부터 `PgVectorStoreAutoConfiguration`도 들어간다** —
  그쪽은 `EmbeddingModel` 빈을 생성자로 요구하는데 조건은 `spring.ai.vectorstore.type` 하나뿐이라
  임베딩이 꺼진 것을 알지 못한다(덤으로 H2 테스트가 실제 PostgreSQL 없이 뜬다). `AppPublicBaseUrl`(#216)처럼 부팅을 세우지 않는 것은 이 값에는 「비어
  있을 정당한 이유가 없다」가 성립하지 않기 때문이다 — 키를 발급받지 않은 기여자 로컬·테스트·
  아직 키를 넣지 않은 배포가 전부 정당하다. 대신 **조용히 끄지 않는다**(부팅 로그 한 줄).
- **모델은 `gemini-embedding-2` · `gemini-3.6-flash`다** (2026-09-13 `./gradlew geminiCheck`
  실측). 임베딩 기본값이 `gemini-embedding-001`이 아닌 이유는 아래 표다.

  | | `gemini-embedding-001` | **`gemini-embedding-2`** |
  |---|---|---|
  | 입력 상한 | 2,048 토큰 | **8,192 토큰** |
  | `dimensions=768` 요청 | 768 | 768 |
  | 그 벡터의 노름 | **0.5819**(비정규화) | **1.0000**(정규화) |
  | `dimensions` 미지정 | 3072(노름 1.0) | 3072(노름 1.0) |
  | `task-type`이 결과를 바꾸나 | 예 | **아니오** |
  | 개정안 제7조(2,136자) 잘림 | 아니오(상한 코앞) | 아니오 |

  **768은 실제로 나온다** — ssccops#322가 고른 값이 전제가 아니라 사실이 됐고, #396의
  `vector(768)`은 둘 중 어느 모델을 골라도 같다(되돌리기는 환경변수 한 줄). 표의 다섯 줄은
  공식 문서와 그대로 일치한다(입력 상한 8,192 vs 2,048 · «gemini-embedding-2 introduces
  automatic renormalization for non-default dimensions» vs «you must manually normalize
  non-3072 dimensions» · «the `task_type` parameter is not supported»).

  **둘 다 무료 티어다** — pricing의 Gemini Embedding 2는 전 입력 유형이 «Free of charge»이고
  (유료는 텍스트 $0.20/1M) Flash 3.x는 전부 «Free of charge»다. 다만 **`gemini-embedding-001`은
  이제 pricing 페이지에 없다** — 값만 바꾸면 되는 선택지이긴 하지만 공식 문서가 가리키는 곳은 2다.
  모델 목록 문서는 Embedding 2의 ID를 `gemini-embedding-2-preview`로 적는데 API는
  `gemini-embedding-2`도 함께 내주며 여기서는 후자를 고정한다.
  ⚠️ **채팅 모델 ID는 늙는다** — 처음 기본값이던 `gemini-2.5-flash`는 모델 목록에 여전히
  보이는데도 새 키로 부르면 404 «no longer available to new users»다. 문서상 최신 stable은
  `gemini-3.8-flash`이고 3.6을 쓰는 것은 **실제로 답하는 것을 확인한 유일한 모델**이라서다 —
  옮기려면 `./gradlew geminiCheck -Pargs="--chat-model=…"`가 먼저다.
  출처: [pricing](https://ai.google.dev/gemini-api/docs/pricing) ·
  [models](https://ai.google.dev/gemini-api/docs/models) ·
  [embeddings](https://ai.google.dev/gemini-api/docs/embeddings)
- ⚠️ **`task-type`은 지금 아무 일도 하지 않는다 — 이유가 둘 겹친다.** ① Spring AI 1.1.8이
  싣지 않는다(`GoogleGenAiTextEmbeddingModel`이 `EmbedContentConfig`에 넣는 것은
  `outputDimensionality` 하나뿐이고 `taskType`·`title`·`autoTruncate`는 읽지도 않는다 —
  SDK와 Gemini API는 셋 다 지원한다). ② **`gemini-embedding-2`는 애초에 `task-type`으로
  **`task_type` 파라미터 자체를 지원하지 않는다**(SDK로 직접 걸어도 DOCUMENT와 QUERY가 같은
  벡터이고 공식 문서도 «the `task_type` parameter is not supported»라고 적는다. 001은 다르다).
  그래서 「적재 `RETRIEVAL_DOCUMENT` · 질의 `RETRIEVAL_QUERY`」를 실제로 걸려면
  `EmbeddingModel`을 감싸야 하는데, **그 일은 001로 되돌릴 때만 의미가 있다.** yaml의 그 줄을
  지우지 않는 것은 이 조건을 그 자리에 적어 두기 위해서다.
  **비대칭 자체를 포기한 것은 아니다** — 2에서 Google이 제시하는 대체 경로는 파라미터가 아니라
  **프롬프트에 과업 설명을 넣는 것**이다(«include task instructions directly in the prompt»).
  적재·질의 텍스트 앞에 서로 다른 한 줄을 붙이는 일이라 Spring AI가 무엇을 싣든 우리가 할 수
  있고, 골든셋(§14.2)이 필요하다고 말하면 그때 청커·질의 쪽에서 건다.
- ⚠️ **모델 클래스의 `dimensions()`를 믿지 말 것.** 모델 이름 상수표를 먼저 보고
  `gemini-embedding-001`에 **3072**을 돌려준다 — 실제 벡터가 768일 때도 그렇다. 이름이 표에
  없으면(`gemini-embedding-2`가 그렇다) 진짜 한 번 불러 그 길이를 캐시하므로 지금은 768이
  나오지만 **모델을 바꾸면 답이 달라지는 값**이다. 그래서 #396이
  **`spring.ai.vectorstore.pgvector.dimensions: 768`을 명시해 두었다** — 비워 두면 저장소가
  부팅 중에 그 값을 묻고, 3072이 나오면 `V10`의 `vector(768)`과 어긋나 적재가 통째로 실패한다.
- ⚠️ **정규화는 모델에 달려 있다.** `gemini-embedding-2`는 768로 잘라도 노름이 1.0이지만
  **001은 0.58이다**(Google 문서가 001을 3072 밖으로 자르면 수동 정규화가 필요하다고 한 그대로이고
  Spring AI는 하지 않는다). 001로 되돌리면 무해한 것은 오직
  `spring.ai.vectorstore.pgvector.distance-type`의 기본값이 `cosine-distance`이고 **코사인이
  스케일 불변**이기 때문이다 — **`euclidean`·`inner-product`로 바꾸는 순간 검색이 조용히
  망가진다.**
- ⚠️ **SDK의 기본 타임아웃은 「무한」이고 스타터에는 그것을 줄 프로퍼티가 없다** (#403 · #448).
  google-genai 1.37.0이 OkHttp에 `connectTimeout(0)`을 걸고 read·write도 같다 — 0은 「제한 없음」
  이라 질의 한 건이 **톰캣 요청 스레드를 영영 붙들 수 있다.** 그래서 `GeminiClientConfig`가
  `com.google.genai.Client` 빈을 직접 만들어 `HttpOptions.timeout`(→ OkHttp `callTimeout`)을
  건다(`ssccops.assistant.gemini.call-timeout` · **기본 40초**). 자동 구성의 그 빈이
  `@ConditionalOnMissingBean`이라 허용된 길이며, **connect와 read를 따로 줄 수 없어** 기획안의
  «connect 3s / read 20s»는 전체 왕복 하나로 합쳤다 — **본문을 다 읽는 시간까지 포함**하므로
  스트리밍(#447)도 같은 상한에 걸린다(실측: 상한 1,500ms에 조각 넷이 나간 뒤 1,863ms에 끊긴다).
  **임베딩은 이 빈을 쓰지 않는다**(`GoogleGenAiEmbeddingConnectionDetails`로 따로 연결한다) —
  상한이 질의 경로에만 걸린다.
- ⚠️ **`spring.ai.retry`는 그 자체로는 Gemini 경로에 닿지 않는다 — 재시도의 정본은 SDK다**
  (#448). 스타터의 `RetryTemplate`은 세 예외만 다시 부르는 **화이트리스트**이고
  (`TransientAiException`·`ResourceAccessException`·`WebClientRequestException`) google-genai가
  던지는 것(`GenAiIOException`·`ApiException`)은 그중 무엇도 아니다 — 재 봤다(그 템플릿에
  `GenAiIOException`을 던지면 콜백이 **한 번**, `TransientAiException`이면 두 번 불린다). 게다가
  스트리밍(`internalStream`)은 템플릿을 아예 지나지 않는다. **대신 SDK가 자기 재시도를 감춰
  두고 있었다** — `ApiClient`가 `retryOptions`가 비면 `RetryInterceptor`를 기본값(5회 · 1s에서
  2배씩 · 408·429·5xx·**모든 IOException**)으로 끼우는데, 그것이 OkHttp **애플리케이션
  인터셉터**라 `callTimeout`이 터진 뒤에도 잠든다(취소된 호출의 재요청은 서버에 닿지도 않는다).
  **그래서 상한 20초가 35~43초의 대기가 됐다**(#448의 증상 · 작게 재현하면 상한 1초 → 18.2초).
  지금은 `GeminiClientConfig`가 `spring.ai.retry`의 값을 그 인터셉터로 옮겨 담아 **2회 · 1s ·
  2배 · 최대 5s · 5xx만**이며, `on-client-errors`(기본 false)가 408·429를 가른다 — 쿼터로
  거절당한 요청을 다시 부르는 것은 쿼터 소진을 가속할 뿐이다(#400). `GeminiClientConfigTest`가
  가짜 서버를 물려 횟수와 대기 시간을 본다. **색인 임베딩에는 아직 닿지 않는다**(따로 연결한다
  — SDK 기본 재시도 그대로다).
- **모델 ID는 설정값 한 줄이다** — `GEMINI_CHAT_MODEL`(기본 `gemini-3.6-flash`) ·
  `GEMINI_EMBEDDING_MODEL`(기본 `gemini-embedding-2`). 교체가 환경변수 하나가 되게 한다.
- ⚠️ **사고 수준을 명시한다 — `GEMINI_CHAT_THINKING_LEVEL`(기본 `MINIMAL`)** (#453).
  «첫 글자까지 6초»의 **76%가 모델이 말하기 전 사고 시간**이었다(2026-09-16 로컬 ·
  `검색=904ms 첫수신=5272ms 첫송신=5276ms 소요=5732ms` — 검색 0.9초 · **생성 앞의 침묵
  4.4초** · 답 전체가 흘러나오는 데 0.46초). 느린 것은 전송도 생성도 아니었고 #447의 SSE는
  정상 동작 중이었다.

  **그전까지 이 블록에는 `model` 한 줄뿐이었고, `thinkingLevel`·`thinkingBudget`·
  `includeThoughts`가 전부 null 이면 Spring AI 가 `ThinkingConfig`를 요청에 아예 싣지
  않는다**(바이트코드 확인) — 모델 기본값을 그대로 받고 있었던 것이고, `includeThoughts`가
  꺼져 있어 그 시간이 **열린 스트림 안의 침묵**으로 나타났다.

  **건 뒤 (같은 질문 · 같은 조건 · 각 1건)**:

  | | 검색 | 첫수신 | 첫송신 | 소요 | 인용 |
  |---|---:|---:|---:|---:|---:|
  | 전 | 904ms | 5272ms | 5276ms | 5732ms | 1 (버린 0) |
  | **후** | 814ms | **2674ms** | **2676ms** | **3347ms** | 1 (버린 0) |

  **첫 글자까지 5.3초 → 2.7초**이고 줄어든 것은 전부 B다(4.37초 → 1.86초 · **-57%**).
  `인용`·`버린인용`이 그대로라 **이 한 건에서는 품질 회귀가 없다** — 표본이 하나씩이므로
  여러 질문으로 다시 볼 것. **남은 2.7초는 A 0.8초 + B 1.9초**이고, B 는 `MINIMAL`에서도
  남는 모델의 최소 왕복이라 **여기가 대체로 바닥이다.**

  ⚠️ **`thinking-budget`이 아니라 `thinking-level`이다**(둘 다 실재한다 — 이름의 정본은 jar
  의 `spring-configuration-metadata.json`이라는 위 규칙이 여기서도 걸린다). **Gemini 3 Pro
  로 옮기면 `MINIMAL`이 부팅을 깨뜨린다**(`validateThinkingLevelForModel`이 Pro 에서만
  LOW·HIGH 로 제한한다) — 그 짝을 `GeminiChatOptionsDefaultsTest`가 묶어 두었다. **키가 없는
  환경에서는 이 프로퍼티가 바인딩조차 되지 않아**(위 `GeminiWiringEnvironmentPostProcessor`)
  CI 가 오타를 잡지 못하므로, 그 테스트는 부팅이 아니라 **선언 자체**를 읽는다.
- **`./gradlew geminiCheck`** (`src/test/.../tools/GeminiCheck`, R2Check와 같은 자리) — 실제
  키로 차원·`task-type` 전달 여부·정규화·긴 조문 잘림을 재 본다. `./gradlew test`에 섞지 않은
  것은 키 없는 CI·기여자 로컬에서 언제나 건너뛰는 테스트가 되기 때문이다.
  **7단계가 검색 점수 분포다**(#405) — 골든셋 시험지(`AssistantGoldenSet`, CI의
  `RetrievalGoldenSetTest`가 스텁 임베딩으로 묻는 것과 **같은 질문·같은 청크**)를 실제 모델로 한
  번 돌려 유형별 분포와 권장 임계값을 찍는다. **운영 임계값이 정해지는 유일한 자리이며**, 지금
  `similarity-threshold-structured`·`-generic`이 같은 값인 것이 «아직 재지 않았다»는 표시다.
  임베딩을 약 70회 부르므로 쿼터가 아까우면 `-Pargs="--no-distribution"`.
  **`gemini-embedding-001`의 입력 상한은 2,048 토큰**이라 조 단위 청크가 긴 조에서 닿는다
  (개정안 제7조가 2,136자다) — 넘으면 오류가 아니라 조용히 잘려 조문 뒷부분이 검색되지 않는다.
### Tika — 표준 패키지가 아니라 모듈 셋 (#398)

`tika-core` + `tika-parser-pdf-module` + `tika-parser-microsoft-module` 셋뿐이다. **버전을 명시한다**
(3.3.1) — 어느 BOM도 Tika를 관리하지 않는다. PDFBox 3 · POI 5.5가 딸려 온다.

| 2026-09-14 실측 | jar | 용량 | `app.jar` |
|---|---|---|---|
| `tika-parsers-standard-package` | 74 | 50.06 MB | — |
| **모듈 셋 (택한 것)** | **40** | **46.47 MB** | 109.64 → **155.51 MB** |
| 모듈 셋 + `poi-ooxml-lite`·jackcess·libpst 제외 | 33 | 34.86 MB | — |

**용량이 크게 줄지 않는다** — 무게의 절반이 DOCX 지원 자체(`poi-ooxml-full` 13.6MB)와 암호화 PDF
(bouncycastle 9.6MB)라 모듈 선택으로 건드릴 수 없다. **그래도 고른 이유는 용량이 아니라 파서 수**다:
코퍼스에 올라오는 것은 외부에서 받아 온 파일이라 실행될 수 있는 파서가 적을수록 좋고, 표준 패키지는
이미지·폰트·CAD·메일 아카이브까지 30여 개를 함께 싣는다. 그 위에 **파서를 확장자로 고정**하므로
(`AutoDetectParser`가 아니다) 실제로 도는 것은 **셋**이다 — `PDFParser` · `OOXMLParser` ·
`OfficeParser`. #445에서 받는 확장자가 `.md`·`.pdf`·`.docx` 셋에서 아홉으로 늘었지만 **모듈도
파서도 거의 늘지 않았다**: `.pptx`·`.xlsx`는 `.docx`와 같은 파서이고 옛 `.doc`·`.ppt`·`.xls`만
같은 모듈 안의 `OfficeParser` 하나를 더한다. `.md`·`.txt`는 파서를 쓰지 않는다(바이트가 곧
본문이다) — 평문에 Tika를 태우면 파서 수를 줄인 이 판단과 거꾸로 간다.

**셋째 줄까지 가지 않은 것은 `poi-ooxml-lite`가 XmlBeans 스키마를 골라 담은 것이기 때문**이다 —
흔치 않은 모양의 `.docx` 하나에서 `NoClassDefFoundError`가 나는데 그 파일은 운영진이 올린 실제 규정
문서다. 11MB를 아끼자고 «어떤 파일은 파싱이 죽는다»를 들이지 않는다.

**부팅 시간은 그대로다**(같은 조건에서 세 번 띄워 7.1s/1.36s/1.21s — 측정 전과 같다). 스프링이 이
jar들을 스캔하지 않고 우리가 Tika의 `ServiceLoader`(= `AutoDetectParser`)를 부르지 않기 때문이며,
**자동 감지로 바꾸면 이 값이 달라진다.**

- **아직 실측하지 않은 것 하나** — 무료 티어의 RPM·TPM·RPD다. **공개 문서에 그 수치가 없다**:
  공식 rate-limits 문서는 «Rate limits depend on a variety of factors (such as your usage tier)
  and can be viewed in Google AI Studio»라고만 하고 모델별 Free Tier 표를 싣지 않는다(2026-09-13
  확인. 그 페이지의 표는 Batch API enqueued token 한도이며 우리가 쓰는 값이 아니다).
  전역 레이트 리밋(§11)의 `N`이 그 값이므로 콘솔에서 읽어 ssccops#324에 적는다.
  ⚠️ **그 자리는 이제 비어 있지 않고 잠정값이 들어가 있다**(#404) — `AssistantRateLimiter`의
  전역 분 한도 기본값 **7**은 «10 RPM 가정 × 70%»이며, 실측이 끝나면 고치는 것은 코드가 아니라
  배포 환경변수 한 줄(`SSCCOPS_ASSISTANT_RATE_LIMIT_GLOBAL_PER_MINUTE`)이다. 낮게 잡힌 채로
  두는 쪽이 안전한 실패라 그 값으로 배포해 둔다.

## 커밋 · 브랜치 · PR 컨벤션

`.github/workflows/`가 강제하는 것과 사람이 지켜야 하는 규칙이 나뉜다 (자세한 배경은 로컬 전용 `private-workspace/CONTRIBUTING.md` 참고 — git에는 포함되지 않음):

- 브랜치: 이슈 생성 시 `issue-branch-creator.yml`이 제목 앞 태그(`[FEAT]`/`[FIX]`/`[REFACTOR]`/`[CHORE]`)를 읽어 `{type}/#{이슈번호}-{슬러그}` 형식으로 자동 생성. 직접 만들어야 한다면 같은 형식을 따르며, **남의 작업 브랜치가 아니라 `develop`에서 딴다** — #235가 작업 중이던 다른 브랜치 위에서 갈라져 나오는 바람에 문서 한 줄짜리 PR이 남의 61개 파일을 함께 머지했다.
- 커밋 메시지: 이슈가 있으면 `#{이슈번호} {type}({scope}): 설명`, 없으면 `{type}({scope}): 설명`. 타입은 `feat`/`fix`/`refactor`/`design`/`style`/`docs`/`test`/`chore`/`init`/`rename`/`remove`/`cicd`/`hotfix`. **커밋 타입과 이슈 유형은 다른 어휘다**(#238) — 커밋 타입은 위 열셋 그대로이고, **이슈 유형은 `feat`·`fix`·`refactor`·`chore` 네 가지가 전부다**(아래). 커밋에는 `docs(agents):`라고 적으면서 그 작업의 이슈는 `[CHORE]`인 것이 정상이다. **PR의 타입 라벨은 연결된 이슈의 라벨에서만 온다**(`pr-labeler.yml`) — 커밋 표기는 라벨에 아무 영향을 주지 않으므로, 표기를 지키는 이유는 `git log`가 읽히기 때문이다. 이슈를 연결하지 않은 PR에는 타입 라벨이 붙지 않는다.
- **이슈 유형은 `feat`·`fix`·`refactor`·`chore` 네 가지뿐이다**(#238). 이슈 템플릿이 주는 것이 정본이며 라벨과 브랜치 접두어가 여기서 나온다. 문서·테스트·스타일·CI/CD 작업의 이슈는 전부 `[CHORE]`다 — `[CICD]`·`[DOCS]` 같은 옛 태그로 열어도 `issue-labeler`·`issue-branch-creator`가 `chore`로 받는다. 예전에 쓰던 `docs`·`test`·`style`·`cicd`·`rename`·`remove` 라벨은 **저장소에서 지웠다** — 남겨 두면 화면의 라벨 목록에서 고를 수 있어 다시 붙는다. 그 라벨이 붙어 있던 과거 PR에서도 함께 사라지지만, 그 작업의 유형은 커밋 메시지와 연결된 이슈에 그대로 남는다. 넷으로 못 박는 이유는 이 표가 `issue-labeler`·`issue-branch-creator`·`pr-labeler`·`pr-guard` 네 워크플로에 흩어져 있어 한 곳만 고치면 갈라지기 때문이다 — 갈라져 있던 동안 그 라벨들이 이슈에는 하나도 없고 PR에만 붙어 있었다(docs 33건·test 53건).
- PR 제목은 `[#이슈번호] 총 작업 내용` — Squash merge 시 그대로 커밋 제목이 되므로 형식을 반드시 지킨다. **저장소 설정이 `squash_merge_commit_title = PR_TITLE`이라 커밋이 하나뿐인 PR에서도 PR 제목이 이긴다**(#238) — 기본값(`COMMIT_OR_PR_TITLE`)이던 동안에는 단일 커밋 PR에서 커밋 메시지가 제목이 되어, PR 제목을 통제해도 `git log`에는 다른 것이 박혔다. **`pr-guard.yml`이 제목·브랜치명·이슈 실재 여부를 검사해 어기면 실패시킨다**(#238) — `develop → main` 릴리스 PR과 dependabot만 면제다. **본문의 «근거» 줄도 본다**(#410 · ssccops#340): 템플릿의 `📎 근거` 칸에 `ssccops#<메타 이슈>` 또는 `ADR-NNNN`이 있어야 한다 — 배포 이력이 PR → Sub-task → Parent → ADR로 취합되는데 그 사슬이 끊긴 PR도 «어느 결정에서 왔나»를 스스로 말하게 하기 위해서다.
- **머지 전략은 둘이다.** 기능·수정 PR은 **Squash and merge**로 develop에 한 커밋으로 들어가고, **`develop → main` 릴리스 PR은 일반 merge commit**이다 — 그쪽을 squash 하면 develop 전체가 main에서 커밋 하나로 뭉개져 릴리스에 무엇이 들어갔는지 사라진다. 그래서 `allow_merge_commit`은 켜 둔 것이며 끄지 말 것. 릴리스 PR에 `[#이슈번호]`가 없는 것도 정상이라 `pr-guard`가 면제한다(`head.ref != develop`). 이 가드가 생긴 이유는 어긴 제목이 문서상의 실수로 끝나지 않고 `git log`에 영구히 박히기 때문이다(#235를 되돌리는 데 배포 브랜치 강제 푸시가 필요했다).
- **CI는 두 워크플로다.** `develop`으로 향하는 PR·푸시는 `integrate-dev.yml`, `main` 쪽은 `integrate-prod.yml`이며 둘 다 Spotless → Checkstyle → Test/JaCoCo → `bootJar`를 같은 명령으로 돈다(다르게 두면 develop에서 통과한 코드가 main에서 떨어진다). `integrate-dev.yml`에는 PR 전용 `api-compat`(OpenAPI 하위 호환 게이트, #412 · 위 «빌드·테스트·린트» 절)이 하나 더 있다.
- **SonarQube 분석은 `develop` 푸시에서만 돌고 아무것도 막지 않는다**(ssccops#231 · #238). `SONAR_TOKEN`이 없으면 건너뛰고, Quality Gate가 실패해도 리포트만 남긴다 — 처음 켰을 때 기존 코드의 지적이 수백 건 나오는 상태에서 게이트를 잠그면 아무것도 머지할 수 없기 때문이다. **기준을 정한 뒤에 잠근다.** 리포트는 **job 요약과 job 로그(stdout) 양쪽에** 남고 스크립트는 `.github/scripts/sonar-report.sh`다 — job 요약은 UI 에서만 보이고 Actions API 로는 읽히지 않아, 로그에 없으면 기준선 숫자를 사람이 브라우저를 열어 옮겨 적기 전에는 아무도 볼 수 없다.
  - **`main`(`integrate-prod.yml`)에는 analyze job이 없다.** 이 서버는 SonarQube **Community Build**이고 브랜치 플러그인이 없어(ssccops#234) 스캐너가 `sonar.branch.name`을 선언하지 못한다 — 선언하면 업그레이드하라는 오류로 분석이 죽는다. 그래서 **모든 분석이 프로젝트 기본 브랜치 한 자리를 덮어쓴다.** develop과 main이 둘 다 돌면 그 자리가 두 브랜치 사이를 오가 어느 쪽 수치인지 알 수 없어지므로 하나만 남겼고, develop이 항상 앞서므로 그쪽을 택했다.
  - **PR에서도 돌지 않는다.** ssccops#231은 PR에서 돌렸는데("병합 전에 보는 편이 리뷰에 붙어 쓸모 있다") 같은 이유로 성립하지 않는다 — PR마다 그 자리가 PR 내용으로 바뀌어 "지금 develop이 어떤 상태인가"를 아무도 알 수 없다. 머지된 상태만 분석하면 수치가 언제 봐도 develop을 가리키고, 그것이 있어야 게이트를 잠글 기준이 생긴다. 잃는 것은 머지 전 피드백이며, PR에는 이미 Spotless·Checkstyle·테스트가 걸려 있다.
  - **같은 이유로 리포트 질의에 `branch` 파라미터를 넣지 않는다.** 제출할 때 브랜치를 밝히지 않았으므로 조회에서 무엇을 하든 같은 데이터를 되읽으며, `&branch=<브랜치명>`은 **없는 브랜치를 묻는 것**이라 빈 응답이 오고 `jq`의 `// "0"` 폴백이 그것을 커버리지 0%로 보고했다. 그 폴백이 진짜 오류를 두 번 가렸다(#284 브랜치명 인코딩 · #238 파라미터 자체) — **0%가 나오면 "커버리지가 없다"가 아니라 "질의가 빗나갔다"부터 의심할 것.**
  - 예전에는 `build`가 `analyze`에 걸려 있었고 analyze는 Quality Gate 실패에 `exit 1` 했다. 그런데 **다섯 번의 릴리스에서 그 job을 실패시킨 것은 품질이 아니라 빈 `SONAR_TOKEN`**이었고(시크릿이 v0.2.1 릴리스보다 2시간 뒤에 등록됐다), 그 동안 JAR 빌드 검증은 `Build: skipped`로 한 번도 돌지 않았다. 막으려던 것은 안 막고 엉뚱한 것을 막은 셈이라 의존을 끊었다.
- **배포는 저장소가 하지 않는다** (#202). Coolify가 GitHub App으로 이 저장소를 직접 보고 있어, `develop` 푸시는 dev로 `main` 푸시는 prod로 **자동 배포**된다. 이미지도 Coolify가 레포의 멀티스테이지 `Dockerfile`로 직접 빌드하므로 GHCR을 거치지 않는다.
  - 따라서 `.github/workflows/`에는 **CI만 있다** — 예전의 `deploy-dev.yml`·`deploy-prod.yml`과 배포 전용 `Dockerfile.deploy`는 걷어냈다.
  - **환경변수의 정본은 Coolify다.** 예전에는 배포마다 Actions가 Coolify API(`envs/bulk`)로 값을 덮어썼는데, 그 구조에서는 대시보드에서 직접 넣은 값(R2 설정 등)이 다음 배포에 날아갔다. 지금은 덮어쓰는 주체가 없으므로 Coolify 대시보드에서 관리한다.
  - **기능 플래그도 거기서 켠다.** `SSCCOPS_MEMBER_HARD_DELETE_ENABLED=true`가 회원 하드 삭제(#361 · [ADR-0021](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0021-temporary-member-hard-delete.md))를 연다 — 기본값은 `false`이고 `application-dev.yaml`·`application-prod.yaml`에는 그 키가 **없다**(임시 기능이라 프로필 설정 파일에 굳히지 않는다). 규정 도우미 손잡이가 #439에서 베이스 `application.yaml`로 옮겨 갈 때 **이 키는 따라가지 않았다** — 곧 지울 기능이라 지금 선언해 두면 그때 다시 지워야 한다. 중복 계정 정리가 끝나면 변수를 지우거나 `false`로 돌린다. 웹은 `NEXT_PUBLIC_MEMBER_HARD_DELETE=true`로 버튼을 그린다.

## 릴리스 — 버전은 태그와 코드 양쪽에 남긴다 (ssccops#229)

릴리스는 `develop → main` 일반 merge commit이고, 그 커밋에 붙는 git 태그(`vX.Y.Z`)와 GitHub
릴리스가 무엇이 나갔는지를 말한다. **그런데 태그만으로는 "지금 떠 있는 것"에 답하지 못한다** —
배포를 Coolify가 자동으로 하므로(#202) 사람이 누른 것과 실제로 뜬 것 사이에 확인할 자리가 필요하다.

### 올릴 때 고치는 곳

| | |
|---|---|
| `build.gradle`의 `version` | 태그와 **같은 값**. `-SNAPSHOT`을 붙이지 않는다 |
| `ssccops-web` | 루트와 `apps/*`의 `package.json` — **같은 릴리스에 함께 올린다** |

`v0.1.0`·`v0.1.1`·`v0.2.0` 세 번의 릴리스 동안 이 절이 없어 `version`이 초기값
(`0.0.1-SNAPSHOT`)으로 남아 있었다. 태그는 v0.2.0인데 산출물은 0.0.1-SNAPSHOT이었다.

### 확인하는 곳

```bash
curl -s https://<배포 주소>/actuator/info
# {"build":{"artifact":"ssccops-server","name":"ssccops-server","time":"...","version":"0.2.1","group":"org.sscc"}}
```

`springBoot { buildInfo() }`가 `META-INF/build-info.properties`를 산출물에 넣고, `/actuator/info`가
그것을 읽는다(이미 `permitAll`이고 노출 목록에도 있다). **부팅 로그에도 한 줄 찍힌다** —
`BuildVersionLogger`가 같은 `BuildProperties`를 쓰므로 두 값이 갈릴 수 없다. Coolify 배포 로그에서
바로 보이므로, 배포가 끝났는데 옛 버전이 찍히면 그 자리에서 드러난다.

**버전 문자열을 코드나 설정에 손으로 적지 않는다.** `management.info.env`로 따로 쓰는 방법도
있지만 같은 사실이 두 벌이 되어 다음 릴리스에 한쪽만 오른다 — 이 절이 생긴 이유가 그것이다.

### 배포 이력은 메타 레포 `deploy-history` 브랜치, 조회는 스크립트 (#410 · #420 · ssccops#340 · ssccops#344)

**«어느 환경에 어떤 커밋이 언제 올라갔고 무엇이 들었나»는 사람이 쓰지 않는다.** `.github/workflows/deploy-history.yml`이
릴리스 게시(prod)와 `develop` 푸시(dev)마다 **메타 레포(`ssccops`)의 orphan 브랜치 `deploy-history`**에 있는
`server-prod.jsonl`·`server-dev.jsonl`에 JSON 한 줄을 append 한다(웹 레포는 같은 브랜치의 `web-*.jsonl` — 이력은 한 곳이다,
[ADR-0033](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0033-deploy-history-in-meta-repo-via-app-token.md)) —
직전 배포 지점과 `compare`한 PR 목록(→ 제목의 `[#N]` Sub-task → cross-repo Parent → 본문의 `ADR-NNNN`), 그리고
**`/actuator/info`의 `git.commit.id`가 푸시된 sha와 같아진 시각**(`deployed_at`, 최대 10분 폴링). 같아지지 않으면
`status: unverified`로 남는다 — 태그는 사람이 올린 값이라 «떠 있다»의 증빙이 못 되고, 실제 응답만 증빙이다.

```bash
scripts/deploy-history.sh current server prod     # 지금 prod 에 무엇이·언제·어떤 PR 로
scripts/deploy-history.sh list web dev 20         # 웹 레코드도 같은 브랜치
```

- **쓰기 토큰은 조직 GitHub App `sscc-devops`다.** 워크플로가 `actions/create-github-app-token`으로 1시간짜리 설치 토큰을
  받되 `repositories: ssccops`로 좁힌다 — 그 토큰이 메타 브랜치 push와 cross-repo Parent 조회(메타 레포가 **private**이라
  `GITHUB_TOKEN`으로는 null이었다) 둘 다 한다. 이 레포에는 쓰지 않으므로 `permissions.contents`는 `read`다. 처음(#410)에는
  이 레포의 orphan 브랜치였다 — 교차 레포 PAT를 두 레포에 두기 싫어서였는데, 조직 앱 토큰은 사람에 안 묶이고 비밀이 조직
  시크릿 하나라 저울이 바뀌었다(감수하는 것: 앱 설치가 all repos라 contents:write 상향이 Coolify가 쓰는 같은 앱의 키에도 미친다).
- **호스트·앱 정보는 조직 변수·시크릿이고 레포에는 없다.** `vars.SSCCOPS_DEPLOY_HISTORY_APP_ID` · `secrets.SSCCOPS_DEPLOY_HISTORY_APP_KEY`
  (Actions 전용으로 하나 더 발급한 PEM) · `vars.SSCCOPS_DEPLOY_HISTORY_ENV`(**.env 모양 여러 줄** — 이 워크플로는 `SERVER_DEV_URL`·
  `SERVER_PROD_URL`만 읽고 웹은 `WEB_*`를 읽는다. 끝 슬래시 없이). 변수 8개를 두 레포에 넣던 것을 한 덩어리로 만든 것이며,
  파서는 CR·주석·빈 줄·따옴표를 무시한다. URL이 비면 폴링 없이 `unverified`, 앱 변수·시크릿이 비면 **워크플로가 실패한다** —
  조용히 자기 레포에 쓰는 길을 남기지 않는다.
- 그래서 `/actuator/info`에 **git 정보가 실린다** — `com.gorylenko.gradle-git-properties`가 `git.properties`를 넣고
  `management.info.git.mode: full`이라 `git.commit.id.{abbrev,full}`·`git.branch`·`git.commit.time`이 나온다(키는 그 넷뿐 —
  `user.email` 같은 값이 공개 엔드포인트로 나가지 않게 `gitProperties.keys`로 좁혔다). `ActuatorInfoTest`가 경로를 못 박는다.
  **`Dockerfile`은 `.git`을 복사하지 않는다**(#422) — Coolify 빌드 컨텍스트에 `.git`이 없어 `COPY .git .git`이 빌드를 죽였고(#413 뒤 dev
  배포 5건 연속 실패) COPY는 없는 경로를 건너뛰지 못한다. 배포 빌드에서는 Coolify가 빌드 인자로 주는 `SOURCE_COMMIT`(Actions면 `GITHUB_SHA`)로
  `generateGitProperties`가 `git.commit.id`만 쓴다(`git.branch`·`git.commit.time`은 없다). **그 인자는 Coolify 앱 설정 Advanced → Build →
  «Source commit availability»가 `Available during build`일 때만 들어간다**(#424 — 기본값 `Runtime only`는 커밋마다 값이 바뀌어 Docker 캐시를
  깨기 때문에 빌드에서 뺀 것. v4.3.16 `include_source_commit_in_build`). dev·prod api 둘 다 켜 두었고, **새 환경을 만들면 이 토글부터** —
  꺼져 있으면 빌드는 성공하는데 `/actuator/info`에 `git`이 없고 레코드가 전부 `unverified`다(2026-09-14 실측). 그것도 없으면(로컬 `docker compose`) git 없이 뜬다 —
  부팅은 막지 않고 레코드가 `unverified`가 될 뿐. 로컬 `bootRun`·테스트는 `.git`이 있으니 JGit이 넷을 다 쓴다.
- Parent를 그래도 못 읽으면 PR 본문 «근거» 줄(`ssccops#N`·`ADR-NNNN`, pr-guard 강제)로 채운다(#418). `image_digest`는 Coolify가
  밖으로 내지 않아 싣지 않는다. 실제 이벤트 전에 돌려 보려면 `workflow_dispatch`(환경·ref 입력)다. 두 레포가 같은 브랜치에 쓰므로
  push가 밀리면 다시 받아 다시 붙인다(파일이 달라 충돌은 없다).

### ⚠️ 산출물 이름을 바꾸지 말 것

`bootJar`의 `archiveFileName`이 `app.jar`로 고정돼 있고 `Dockerfile`이 그 이름을 집어 온다.
예전에는 `COPY .../*-SNAPSHOT.jar` 글롭이었는데, 그러면 **버전에서 `-SNAPSHOT`을 떼는 순간 맞는
파일이 없어 이미지 빌드가 그 줄에서 죽는다.** 이름을 고정한 덕에 다음 릴리스에는 `version` 한
줄만 고치면 되고 `Dockerfile`을 다시 볼 일이 없다 — 되돌리려면 두 파일을 함께 봐야 한다.
