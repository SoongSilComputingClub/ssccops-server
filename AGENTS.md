# AGENTS.md

SSCC(숭실컴퓨팅클럽) 지원서 관리 백엔드 — Spring Boot 3.5 / Java 17 / Gradle.

## 빌드 · 테스트 · 린트

- 전체 빌드: `./gradlew build` (compileJava → checkstyle → spotless → test → jacoco 순서로 전부 수행)
- 테스트만: `./gradlew test`
- 단일 테스트 클래스: `./gradlew test --tests "org.sscc.ssccopsserver.domain.member.service.MemberServiceImplTest"`
- 단일 테스트 메서드: `./gradlew test --tests "*.MemberServiceImplTest.메서드명"`
- 포맷 검사: `./gradlew spotlessCheck` / 자동 정렬·수정: `./gradlew spotlessApply`
- 스타일 검사: `./gradlew checkstyleMain checkstyleTest` (설정: `config/checkstyle/checkstyle.xml`, Naver 컨벤션 변형 — 상단 주석에 원본과의 차이가 정리되어 있음)
- 로컬 실행: `local` 프로필(`-Dspring.profiles.active=local`)이 PostgreSQL 접속 정보(`db-url`/`db-username`/`db-password`)와 Supabase JWKS URI(`supabase-url`)를 요구한다. 두 가지 방식이 있다:
  - `docker-compose up`으로 `backend`(local 프로필)와 `postgres`를 함께 띄운다. `.env`(`cp .env.example .env` 후 값 채우기)를 `docker-compose.yml`이 그대로 읽고, `./gradlew bootRun`도 `.env`를 읽어 JVM 환경변수로 주입한다.
  - `docker-compose up postgres`로 DB만 띄우고(호스트 포트 `15432`) 앱은 `./gradlew bootJar` 후 `java -jar build/libs/*.jar` 또는 IDE에서 `SsccopsServerApplication`을 직접 실행한다. 로컬에 PostgreSQL을 직접 설치해 쓴다면 `createdb ssccops_server_db` 후 `jdbc:postgresql://localhost:5432/ssccops_server_db`로 접속한다.
- 프로필: `local`(PostgreSQL, OTel 비활성) / `dev` / `prod`(env 변수 주입, ddl-auto none, Swagger 비활성) / `test`(H2 인메모리, ddl-auto create-drop — 테스트 실행 시 자동 적용).

**주의**: JPA 프로필 설정에 `database-platform`(Hibernate `dialect`)을 명시하지 않는다. Hibernate가 커넥션에서 자동 감지하며, 명시하면 `HHH90000025` 경고가 뜨고 DB 엔진을 바꿀 때 드라이버와 방언이 어긋나 깨진다.

**주의**: checkstyle의 `ImportOrder`는 `java, javax, jakarta, org, net, com, *, lombok` 그룹 순서를 엄격히 검사한다. import를 추가/이동한 뒤 checkstyle이 실패하면 순서를 수동으로 고치지 말고 `./gradlew spotlessApply`로 자동 정렬할 것 (Spotless의 `importOrder` 설정이 checkstyle 규칙과 동일하게 맞춰져 있음).

**주의**: `prod`는 `ddl-auto: none`이라 엔티티 컬럼/테이블 변경(특히 리네임)이 배포만으로 반영되지 않는다. `local`/`dev`는 `ddl-auto: create-drop`이라 재기동 시 자동 반영되지만, `prod`는 배포 전 수동 DDL을 직접 실행해야 한다 — 예: `MemberEntity.authUserId`(컬럼 `auth_user_id`, 구 `spb_user_id`)처럼 컬럼명을 바꿨다면 `ALTER TABLE mbr RENAME COLUMN spb_user_id TO auth_user_id;`를 배포 전에 미리 실행한다. 마이그레이션 도구(Flyway/Liquibase)가 없어 현재는 전적으로 수동이다.

## 아키텍처

패키지 루트는 `org.sscc.ssccopsserver` (Gradle group도 `org.sscc`) — SSCC 동아리 프로젝트이므로 Spring Initializr 기본값(`com.example`)을 쓰지 않는다.

- `domain/{member,auth,operation,...}` — 도메인별로 `controller/service/repository/entity/dto/code` 하위 구조를 반복하는 계층형 패키지. 새 도메인을 추가할 때 이 구조를 그대로 따른다. (`admin`/`applyform`/`user`는 Supabase 인증 전환에 맞춰 재설계 예정이라 제거됨 — 잔재를 찾는 코드가 있다면 지우면 된다.)
- `domain/example` — 위 6계층 구조를 보여주는 참고용 템플릿 도메인(실제 기능 아님). 새 도메인을 추가할 때 이 구조를 복사해서 시작하면 된다.
- `global/apipayload` — 모든 API 응답의 공통 껍데기.
  - `ApiResponse<T>` — 생성자는 private, `success`/`successWithNoData`/`created`/`fail` 정적 팩토리로만 생성.
  - `code/success/SuccessCode`, `code/error/ErrorCode` — 인터페이스이며 `CommonErrorCode`가 전역 에러를 구현하고, 도메인 전용 에러가 필요하면 같은 방식으로 도메인 패키지 아래 `code/error` enum을 구현한다. 새 에러를 추가할 때 전역(`CommonErrorCode`)에 넣을지 도메인 전용 enum에 넣을지 먼저 판단할 것.
  - `exception/GeneralException` — 서비스 레이어에서 던지는 표준 예외, `ErrorCode`를 감싼다.
  - `handler/GlobalExceptionHandler` — `@RestControllerAdvice`. `GeneralException`은 감싼 `ErrorCode` 그대로, `MethodArgumentNotValidException`/`HttpMessageNotReadableException` 등 스프링 기본 예외는 `CommonErrorCode`로 변환해 항상 `ApiResponse.fail(...)` 포맷으로 응답한다. 컨트롤러 레벨에서 별도 try/catch를 추가하지 않는다.
- `global/security` — 인증/인가. 구글 OAuth2 로그인과 자체 JWT 발급 스택은 제거됐고, 지금은 Supabase Auth가 발급한 JWT를 `spring-boot-starter-oauth2-resource-server`로 검증한다. JWKS URI(`{SUPABASE_URL}/auth/v1/.well-known/jwks.json`)는 프로필별로 다른 Supabase 프로젝트를 가리키도록 `application-{profile}.yaml`에 분리돼 있다.
  - `global/security/jwt/SupabaseJwtAuthenticationConverter` — JWT의 `sub`(UUID)로 `MemberService.findByAuthUserId(...)`를 호출해 회원을 **조회만** 한다. **인증 시점에 회원을 만들지 않는다** — 만들어 버리면 "로그인은 했지만 아직 가입하지 않은 사용자"라는 상태가 존재할 수 없고, 조회 요청 하나에도 쓰기 트랜잭션이 열린다. `mbr` 행이 생기는 유일한 경로는 회원가입 API다. `MemberEntity.authUserId`(컬럼 `auth_user_id`)는 특정 인증 벤더 이름을 쓰지 않는다 — 지금은 Supabase Auth의 사용자 ID를 담지만 인증 수단이 바뀌어도 이 컬럼명은 유지된다. JWT의 `role` 클레임은 RLS용 Postgres 역할이라 인가 판단에 쓰지 않는다.
  - principal은 `MemberEntity`가 아니라 `global/security/AuthenticatedUser`다 — `authUserId`·`email`·`name`(`user_metadata.full_name`/`name`)·`provider`(`app_metadata.provider`)와 **nullable한 `MemberEntity`**를 담는다. 가입 여부는 별도 등급 코드가 아니라 `member == null`로 표현된다.
  - 회원이 필요한 엔드포인트는 principal을 캐스팅하지 말고 **`@CurrentMember MemberEntity`**로 받는다(`global/security/resolver`). 미가입 주체는 이 리졸버 한 곳에서 **403 `SIGNUP_REQUIRED`**(`MemberErrorCode`)로 끊기므로 주입된 값은 항상 non-null이다. 401(토큰 없음·무효)과 구분되며, 프론트는 이 코드를 받으면 재로그인이 아니라 가입 화면으로 보낸다. 리졸버 등록은 `global/config/WebConfig`.
  - principal에 실린 `MemberEntity`는 **준영속**이다(인증 필터는 트랜잭션 밖). 등급·상태 같은 지연 로딩 필드를 꺼내려면 식별자만 쓰고 회원 도메인 Service로 다시 조회해야 한다 — `MemberService.getProfile(memberId)`가 조회 트랜잭션 안에서 DTO로 굳혀 돌려준다.
  - `GrantedAuthority`는 아직 부여하지 않는다 — 역할(ADMIN/USER/PREUSER) 기반 인가는 별도로 AOP를 통해 구현할 예정이라, 현재 `hasRole` 기반 규칙(`/admin/**`)은 사실상 항상 거부된다.
  - 인증 실패(서명/만료 오류 등)는 `CustomAuthenticationEntryPoint`, 권한 부족은 `CustomAccessDeniedHandler`가 `ApiResponse` 포맷으로 응답한다.
  - `SecurityConfig`가 필터체인을 구성: CSRF/formLogin/httpBasic/logout 비활성화, `SessionCreationPolicy.STATELESS`, `RoleHierarchy`(ADMIN ⊃ USER ⊃ PREUSER — 실제 부여 로직은 미구현), `/admin/**`은 ADMIN 전용, 비prod Swagger 경로와 `/actuator/health`·`/actuator/info`만 permitAll(배포 헬스 프로브는 토큰을 붙일 수 없다 — `prometheus`·`metrics`·`loggers`는 계속 보호), 나머지는 인증 필요.
  - CORS는 `SecurityConfig.corsConfigurationSource()` 한 곳에서만 정의한다. 허용 오리진은 `frontend.url`이며 **쉼표로 여러 개**를 넣을 수 있다(Cloudflare Workers 프리뷰 도메인 대응). 시큐리티 필터체인이 먼저 처리하므로 `WebMvcConfigurer.addCorsMappings()`로 중복 정의하지 말 것 — `WebConfig`는 인자 리졸버 등록에만 쓴다.
- `domain/auth` — 로그인·로그아웃 자체는 Supabase(클라이언트) 책임이라 서버에 엔드포인트가 없다. 서버가 답하는 것은 "이 토큰이 우리 서비스의 누구인가" 하나뿐이고 그게 `GET /v1/auth/session`이다. **미가입 사용자에게도 200**으로 응답한다(`signedUp: false`, `member: null`) — 가입이 필요하다는 것도 정상적인 세션 상태이지 오류가 아니며, 403으로 끊으면 프론트가 가입 화면으로 갈 근거를 얻지 못한다. 응답의 `member` 블록(`MemberProfileResponse`)은 회원가입 응답과 같은 모양을 쓴다.
- 관측성: OpenTelemetry(OTLP) + Micrometer(Prometheus/OTLP) + Logstash JSON 로깅(`prod` 프로파일에서만 JSON, 그 외 텍스트 — `logback-spring.xml`)이 이미 연결돼 있다. 로컬에 OTLP collector가 없으면 애플리케이션 종료 시 `Connection refused` 경고가 뜨는데 무해하다.

## 커밋 · 브랜치 · PR 컨벤션

`.github/workflows/`가 강제하는 것과 사람이 지켜야 하는 규칙이 나뉜다 (자세한 배경은 로컬 전용 `.private-workspace/CONTRIBUTING.md` 참고 — git에는 포함되지 않음):

- 브랜치: 이슈 생성 시 `issue-branch-creator.yml`이 제목 앞 태그(`[Feat]`/`[Fix]`/`[Refactor]`)를 읽어 `{type}/#{이슈번호}-{슬러그}` 형식으로 자동 생성. 직접 만들어야 한다면 같은 형식을 따른다.
- 커밋 메시지: 이슈가 있으면 `#{이슈번호} {type}({scope}): 설명`, 없으면 `{type}({scope}): 설명`. 타입은 `pr-labeler.yml`이 그대로 파싱해 PR 라벨을 붙이므로 표기를 벗어나면 라벨이 안 붙는다 — `feat`/`fix`/`refactor`/`design`/`style`/`docs`/`test`/`chore`/`init`/`rename`/`remove`/`cicd`/`hotfix`.
- PR 제목은 `[#이슈번호] 총 작업 내용` — Squash merge 시 그대로 커밋 제목이 되므로 형식을 반드시 지킨다. 이 레포는 Squash and merge만 사용.
- `main`으로 향하는 PR은 `integrate-prod.yml`이 Spotless → Checkstyle → Test/JaCoCo → SonarQube Quality Gate → `bootJar` 순서로 실행되며, Quality Gate를 통과하지 못하면 머지할 수 없다.
- `main`에 머지되면 `deploy-prod.yml`이 GHCR(`ghcr.io/<owner>/ssccops-server`)로 이미지를 푸시하고 Coolify webhook으로 프로덕션 배포를 트리거한다.
