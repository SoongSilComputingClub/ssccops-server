# AGENTS.md

SSCC(숭실컴퓨팅클럽) 지원서 관리 백엔드 — Spring Boot 3.5 / Java 17 / Gradle.

## 빌드 · 테스트 · 린트

- 전체 빌드: `./gradlew build` (compileJava → checkstyle → spotless → test → jacoco 순서로 전부 수행)
- 테스트만: `./gradlew test`
- 단일 테스트 클래스: `./gradlew test --tests "org.sscc.ssccopsserver.domain.user.service.UserServiceImplTest"`
- 단일 테스트 메서드: `./gradlew test --tests "*.UserServiceImplTest.메서드명"`
- 포맷 검사: `./gradlew spotlessCheck` / 자동 정렬·수정: `./gradlew spotlessApply`
- 스타일 검사: `./gradlew checkstyleMain checkstyleTest` (설정: `config/checkstyle/checkstyle.xml`, Naver 컨벤션 변형 — 상단 주석에 원본과의 차이가 정리되어 있음)
- 로컬 실행: `./gradlew bootJar` 후 `java -jar build/libs/*.jar`, 또는 IDE에서 `SsccopsServerApplication` 직접 실행. 기본(`application.yaml`)은 MySQL 접속 정보(`db-url`/`db-username`/`db-password`)가 필요하므로 `local` 프로필(`-Dspring.profiles.active=local`) + `docker-compose up mysql`로 DB만 띄우는 방식을 권장.
- 프로필: `local`(MySQL, OTel 비활성) / `staging` / `prod`(env 변수 주입, ddl-auto none, Swagger 비활성) / `test`(H2 인메모리, ddl-auto create-drop — 테스트 실행 시 자동 적용).

**주의**: checkstyle의 `ImportOrder`는 `java, javax, jakarta, org, net, com, *, lombok` 그룹 순서를 엄격히 검사한다. import를 추가/이동한 뒤 checkstyle이 실패하면 순서를 수동으로 고치지 말고 `./gradlew spotlessApply`로 자동 정렬할 것 (Spotless의 `importOrder` 설정이 checkstyle 규칙과 동일하게 맞춰져 있음).

## 아키텍처

패키지 루트는 `org.sscc.ssccopsserver` (Gradle group도 `org.sscc`) — SSCC 동아리 프로젝트이므로 Spring Initializr 기본값(`com.example`)을 쓰지 않는다.

- `domain/{admin,applyform,user}` — 도메인별로 `controller/service/repository/entity/dto/code` 하위 구조를 반복하는 계층형 패키지. 새 도메인을 추가할 때 이 구조를 그대로 따른다.
- `global/apipayload` — 모든 API 응답의 공통 껍데기.
  - `ApiResponse<T>` — 생성자는 private, `success`/`successWithNoData`/`created`/`fail` 정적 팩토리로만 생성.
  - `code/success/SuccessCode`, `code/error/ErrorCode` — 인터페이스이며 도메인별 enum(`UserErrorCode`, `ApplyFormErrorCode`, `JwtErrorCode`, `OAuth2ErrorCode`, `CommonErrorCode` 등)이 구현. 새 에러를 추가할 때 전역(`CommonErrorCode`)에 넣을지 도메인 전용 enum에 넣을지 먼저 판단할 것.
  - `exception/GeneralException` — 서비스 레이어에서 던지는 표준 예외, `ErrorCode`를 감싼다.
  - `handler/GlobalExceptionHandler` — `@RestControllerAdvice`. `GeneralException`은 감싼 `ErrorCode` 그대로, `MethodArgumentNotValidException`/`HttpMessageNotReadableException` 등 스프링 기본 예외는 `CommonErrorCode`로 변환해 항상 `ApiResponse.fail(...)` 포맷으로 응답한다. 컨트롤러 레벨에서 별도 try/catch를 추가하지 않는다.
- `global/security` — 인증/인가.
  - OAuth2 소셜 로그인(Google)이 성공/실패하면 `SocialSuccessHandler`/`SocialFailureHandler`가 JWT 발급으로 이어진다 (`security/jwt/` 하위: `JwtUtil`, `JwtService`, `JwtFilter`, `/jwt/exchange`·`/jwt/refresh` 컨트롤러).
  - `JwtFilter`는 `OncePerRequestFilter`를 직접 구현 — Spring Security 기본 인증 필터 체인을 쓰지 않고 `Authorization: Bearer` 헤더를 수동 파싱해 `SecurityContextHolder`에 인증 정보를 채운다. 필터 단계라 `GlobalExceptionHandler`를 타지 않으므로, 토큰 검증 실패 시 `ApiResponse` 포맷 응답을 필터 안에서 직접 작성한다.
  - `SecurityConfig`가 필터체인을 구성: CSRF/formLogin/httpBasic 비활성화, `SessionCreationPolicy.STATELESS`, `RoleHierarchy`(ADMIN ⊃ USER ⊃ PREUSER), `/admin/**`은 ADMIN 전용, `/jwt/exchange`·`/jwt/refresh`(+ 비prod의 Swagger 경로)만 permitAll, 나머지는 인증 필요.
  - Refresh Token은 DB(`UserRefreshEntity`/`UserRefreshRepository`)에 저장되며 `refresh-token.cleanup-cron`(application.yaml, 매일 03:00)으로 만료분을 정리한다 (`ScheduleConfig`).
- 관측성: OpenTelemetry(OTLP) + Micrometer(Prometheus/OTLP) + Logstash JSON 로깅(`prod` 프로파일에서만 JSON, 그 외 텍스트 — `logback-spring.xml`)이 이미 연결돼 있다. 로컬에 OTLP collector가 없으면 애플리케이션 종료 시 `Connection refused` 경고가 뜨는데 무해하다.

## 커밋 · 브랜치 · PR 컨벤션

`.github/workflows/`가 강제하는 것과 사람이 지켜야 하는 규칙이 나뉜다 (자세한 배경은 로컬 전용 `.private-workspace/CONTRIBUTING.md` 참고 — git에는 포함되지 않음):

- 브랜치: 이슈 생성 시 `issue-branch-creator.yml`이 제목 앞 태그(`[Feat]`/`[Fix]`/`[Refactor]`)를 읽어 `{type}/#{이슈번호}-{슬러그}` 형식으로 자동 생성. 직접 만들어야 한다면 같은 형식을 따른다.
- 커밋 메시지: 이슈가 있으면 `#{이슈번호} {type}({scope}): 설명`, 없으면 `{type}({scope}): 설명`. 타입은 `pr-labeler.yml`이 그대로 파싱해 PR 라벨을 붙이므로 표기를 벗어나면 라벨이 안 붙는다 — `feat`/`fix`/`refactor`/`design`/`style`/`docs`/`test`/`chore`/`init`/`rename`/`remove`/`cicd`/`hotfix`.
- PR 제목은 `[#이슈번호] 총 작업 내용` — Squash merge 시 그대로 커밋 제목이 되므로 형식을 반드시 지킨다. 이 레포는 Squash and merge만 사용.
- `main`으로 향하는 PR은 `integrate-prod.yml`이 Spotless → Checkstyle → Test/JaCoCo → SonarQube Quality Gate → `bootJar` 순서로 실행되며, Quality Gate를 통과하지 못하면 머지할 수 없다.
- `main`에 머지되면 `deploy-prod.yml`이 GHCR(`ghcr.io/<owner>/ssccops-server`)로 이미지를 푸시하고 Coolify webhook으로 프로덕션 배포를 트리거한다.
