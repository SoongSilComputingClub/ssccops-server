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

**주의**: `prod`는 `ddl-auto: none`이라 엔티티 컬럼/테이블 변경(특히 리네임)이 배포만으로 반영되지 않는다. `local`/`dev`는 `ddl-auto: create-drop`이라 재기동 시 자동 반영되지만, `prod`는 배포 전 수동 DDL을 직접 실행해야 한다 — 예: `MemberEntity.authUserId`(컬럼 `auth_user_id`, 구 `spb_user_id`)처럼 컬럼명을 바꿨다면 `ALTER TABLE mbr RENAME COLUMN spb_user_id TO auth_user_id;`를 배포 전에 미리 실행한다. 널 허용 여부도 마찬가지다 — `mbr.stdnt_no`를 nullable로 바꿨으므로(#21, 졸업 회원 가입) `ALTER TABLE mbr ALTER COLUMN stdnt_no DROP NOT NULL;`이 배포 전에 필요하다. 마이그레이션 도구(Flyway/Liquibase)가 없어 현재는 전적으로 수동이다.

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
- `domain/member` — `mbr` 행이 생기는 **유일한 경로**는 `POST /v1/members/signup`이다. 인증 시점에는 회원을 만들지 않으므로(#20) 다른 진입점을 만들지 말 것. 이 엔드포인트만 `@CurrentMember`가 아니라 `@AuthenticationPrincipal AuthenticatedUser`로 주체를 받는다 — `@CurrentMember`는 미가입 주체를 403으로 끊어 가입 자체를 막는다. 응답은 세션 조회와 같은 `MemberProfileResponse`이며, 별도 가입 응답 DTO를 두지 않는 것은 한쪽만 필드가 늘어 두 응답이 어긋나는 것을 막기 위해서다. 등급은 항상 `TEMP`로 고정하고, 등급·상태의 최초 부여는 `mbr_grd_hstry`·`mbr_stts_hstry`에 `bfr_*_cd = NULL`로 한 건씩 남긴다. `mbr.stdnt_no`는 졸업 회원을 위해 **nullable**이며 `uk_mbr_student_number`는 유지되므로 학번 미입력은 빈 문자열이 아니라 **NULL**로 저장해야 한다. 선조회만으로는 동시 요청을 못 막으므로 UNIQUE 위반(`DataIntegrityViolationException`)도 같은 409로 옮긴다.
- `domain/form` — 폼(지원서·신청서). 문항은 별도 테이블이 아니라 `form.qitem_cpst_cn`(JSONB) 한 컬럼에 들어 있다 — 폼마다 문항의 개수·유형·검증 규칙이 전부 달라 정규화해도 매번 전량 조회가 되기 때문이다. 그 대가로 **DB가 구조를 보장하지 않으므로 서버가 최종 방어선**이며, 그 검사를 모아 둔 곳이 `service/QuestionCompositionValidator`다(페이지 범위·`qitemId` 유일성·선택지·분기 대상 페이지 실재 여부·`Pattern.compile()` 성공 여부·최대 선택 수). 공개 폼 응답 검증(#35)이 같은 규칙을 반대 방향으로 쓰므로 규칙을 다른 곳에 복제하지 말 것. 유형과 무관한 잔여 속성(비선택형에 남은 `optionList` 등)은 거절이 아니라 **정리**하고, 유형에 해당하는 값이 잘못된 것만 400 `INVALID_QUESTION_COMPOSITION`으로 거절한다.
  - `qitemId`는 응답(`form_rspns_hstry.rspns_cn`)의 **key**다. 그래서 응답이 한 건이라도 있는 폼은 기존 `qitemId`의 삭제·변경을 409 `QUESTION_ITEM_IN_USE`로 막는다(문항 추가는 허용). 임시저장(DRAFT) 응답도 판단에 포함한다 — 목록의 `responseCount`가 DRAFT를 빼는 것과는 기준이 다르다.
  - 폼 목록(`GET /v1/forms`)은 `qitemCpstCn`을 싣지 않는다. 라벨은 `FormEntity`에 컬렉션 연관을 열지 않고 `FormLabelRelationRepository.findAllByFormIdIn`으로 한 번에 모아 오며, 응답 건수는 `FormResponseCount` 집계 프로젝션을 쓴다(폼 1 + 라벨 1 + 집계 1, 총 3회). 상태 필터는 NULL 비교 대신 **전체 상태 집합**을 넘긴다 — 열거형 파라미터에 NULL을 넣고 `:status is null`로 분기하면 Hibernate가 타입을 추론하지 못한다.
  - 라벨 지정 교체는 통째로 지우고 다시 넣지 않고 **차집합만** 움직인다. 같은 `(form_id, form_lbl_id)` 쌍을 한 트랜잭션에서 지웠다 넣으면 Hibernate가 INSERT를 DELETE보다 먼저 흘려보내 UNIQUE 제약에 걸린다.
  - 상태(`form_stts_cd`)를 바꾸는 길은 **`POST /v1/forms/{formId}/status` 하나뿐**이다(#33). `PUT /v1/forms/{formId}`는 본문에 `formSttsCd`가 실려 와도 **무시한다** — 편집 자동 저장(ssccops #63)이 상세 응답을 초안으로 받아 그대로 되돌려 보내므로 그 본문에는 늘 현재 상태가 실려 있고, 받아 쓰면 타이핑 한 번이 접수 상태를 덮어쓴다. 거절하지 않고 무시하는 것은 거절하면 자동 저장이 통째로 멈추기 때문이다. `FormSaveRequest.formSttsCd`는 생성(POST)에서만 쓰인다('바로 접수 시작'). `labelIds` 생략은 **전부 떼기**로 갈린다.
  - 전이표는 `FormStatusAction`(액션 → 대상 상태 + 허용 진입 상태)이 갖고, 전이 가능 여부와 사전 검증은 `FormEntity.changeStatus`가 던진다 — DRAFT→OPEN·OPEN→CLOSED·CLOSED→OPEN(마감 철회)만 허용하고 나머지는 400 `INVALID_FORM_STATUS_TRANSITION`. 문항 0개인 폼을 여는 것은 400 `FORM_HAS_NO_QUESTION`이며, 이 검증은 `FormEntity.create(..., OPEN)`(바로 접수 시작)에도 같이 걸린다 — 같은 결과(열린 폼)에 도달하는 두 경로가 다른 규칙을 쓰면 안 된다. 상태 전이 이력 테이블은 만들지 않는다(데이터사전에 없음, 감사 로그 #8이 확정되면 그쪽에 얹는다).
  - **"지금 이 폼이 응답을 받을 수 있는가"의 유일한 구현은 `service/FormReceiptPolicy`다**(#33). `form_stts_cd == OPEN && (rcpt_bgng_dt == null || now >= rcpt_bgng_dt) && (rcpt_end_dt == null || now <= rcpt_end_dt)`이며 경계는 양쪽 모두 포함, NULL은 '제한 없음'이다. `now`는 주입된 `Clock`(`global/config/ClockConfig`)에서 온다. 공개 폼 응답 제출(#35)·응답 자동 저장(#36)은 이 판정을 다시 구현하지 말고 `isAcceptingResponses(form)`만 호출한다.
  - 응답자용 공개 경로는 `GET /v1/forms/{formId}/public`·`POST /v1/forms/{formId}/responses`(#35)이며 운영자용 `FormController`와 컨트롤러·응답 스키마를 **분리한다**(`PublicFormController`·`PublicFormResponse`) — 응답자에게 `creatrMbrId`·응답 집계·`formSttsCd`를 줄 이유가 없고, 한 응답에 합치면 운영자용 필드가 늘 때마다 공개 링크로 새어 나갈 것이 함께 는다. **'공개'는 누구나 링크를 열 수 있다는 뜻이지 익명 제출이 아니다** — 두 경로 모두 인증이 필요하고 등급 제한은 없다(임시회원도 응답한다, ssccops #61). 접수 불가면 문항을 뺀 200이 아니라 **409 `FORM_NOT_ACCEPTING`**으로 끊는다(DRAFT·CLOSED·기간 전·기간 후를 한 코드로 묶는다) — 문항을 실을지 말지를 DTO 조립의 분기 하나에 맡기면 DRAFT 폼의 문항이 링크만으로 새어 나가는 데 그 분기 하나면 충분하다.
  - 제출된 답의 재검증은 `service/ResponseAnswerValidator`가 갖는다(#35, `QuestionCompositionValidator`의 짝). 유형별 어휘(`CHOICE_TYPES`·`TEXT_TYPES`·`BRANCHABLE_TYPES`)는 두 클래스가 나눠 쓰므로 복제하지 말 것. **분기(`branchMap`)로 건너뛴 페이지의 필수 문항은 필수가 아니다** — 제출된 답으로 웹 `nextTarget()`과 같은 이동을 되짚어 도달한 페이지 집합을 재현하고 그 밖은 필수 검사에서 뺀다(이 규칙이 없으면 분기 폼은 어떤 답으로도 제출되지 않는다). 정규식은 `matches()`가 아니라 `find()`로 본다(웹의 `new RegExp().test()`와 같은 뜻이어야 한다). 빈 값(`""`·`[]`)인 key는 저장하지 않고, 폼에 없는 `qitemId`는 조용히 버리지 않고 400이다. 저장 형태는 다중선택만 배열이고 나머지는 문자열이라, 웹이 배열로 보내는 단일선택은 서버가 벗겨 굳힌다.
  - 응답은 한 회원당 한 폼에 1건이다. 선조회 409에 더해 `(form_id, mbr_id)` UNIQUE 위반도 같은 `RESPONSE_ALREADY_SUBMITTED`로 옮긴다(동시 제출). 이미 있는 행이 **DRAFT면 막지 않고 그 행을 제출로 바꾼다** — 막으면 자동 저장(#36)을 쓴 응답자는 UNIQUE 때문에 새 행도 만들지 못해 영영 제출할 수 없다. `mbr_id`·`rspns_stts_cd`·`sbmsn_dt`는 전부 서버가 채우며 `sbmsn_dt`는 주입된 `Clock`에서 온다.
  - **접수 기간이 끝난 폼은 자동으로 CLOSED가 되지 않는다** — 자동 마감 배치를 두지 않기로 결정했다(#33). 배치가 쓴 CLOSED와 운영자가 누른 CLOSED가 구별되지 않아 '마감 철회'가 무엇을 되돌리는지 알 수 없고, 배치로 닫힌 폼의 종료 일시를 미뤄도 상태가 CLOSED로 남으며, 다중 인스턴스에서 스케줄러 중복 실행을 막을 장치가 없기 때문이다. 대신 목록·상세·전이 응답이 파생 값 `receiptStatus`(`FormReceiptStatus`: DRAFT·SCHEDULED·ACCEPTING·EXPIRED·CLOSED)를 함께 내리고 화면이 그 값으로 배지를 고른다(ssccops-web #9). 저장하지 않고 조회할 때마다 `FormReceiptPolicy`가 다시 계산하므로 `formSttsCd`와 어긋날 수 없다.
- `domain/auth` — 로그인·로그아웃 자체는 Supabase(클라이언트) 책임이라 서버에 엔드포인트가 없다. 서버가 답하는 것은 "이 토큰이 우리 서비스의 누구인가" 하나뿐이고 그게 `GET /v1/auth/session`이다. **미가입 사용자에게도 200**으로 응답한다(`signedUp: false`, `member: null`) — 가입이 필요하다는 것도 정상적인 세션 상태이지 오류가 아니며, 403으로 끊으면 프론트가 가입 화면으로 갈 근거를 얻지 못한다. 응답의 `member` 블록(`MemberProfileResponse`)은 회원가입 응답과 같은 모양을 쓴다.
- 관측성: OpenTelemetry(OTLP) + Micrometer(Prometheus/OTLP) + Logstash JSON 로깅(`prod` 프로파일에서만 JSON, 그 외 텍스트 — `logback-spring.xml`)이 이미 연결돼 있다. 로컬에 OTLP collector가 없으면 애플리케이션 종료 시 `Connection refused` 경고가 뜨는데 무해하다.

## 커밋 · 브랜치 · PR 컨벤션

`.github/workflows/`가 강제하는 것과 사람이 지켜야 하는 규칙이 나뉜다 (자세한 배경은 로컬 전용 `.private-workspace/CONTRIBUTING.md` 참고 — git에는 포함되지 않음):

- 브랜치: 이슈 생성 시 `issue-branch-creator.yml`이 제목 앞 태그(`[Feat]`/`[Fix]`/`[Refactor]`)를 읽어 `{type}/#{이슈번호}-{슬러그}` 형식으로 자동 생성. 직접 만들어야 한다면 같은 형식을 따른다.
- 커밋 메시지: 이슈가 있으면 `#{이슈번호} {type}({scope}): 설명`, 없으면 `{type}({scope}): 설명`. 타입은 `pr-labeler.yml`이 그대로 파싱해 PR 라벨을 붙이므로 표기를 벗어나면 라벨이 안 붙는다 — `feat`/`fix`/`refactor`/`design`/`style`/`docs`/`test`/`chore`/`init`/`rename`/`remove`/`cicd`/`hotfix`.
- PR 제목은 `[#이슈번호] 총 작업 내용` — Squash merge 시 그대로 커밋 제목이 되므로 형식을 반드시 지킨다. 이 레포는 Squash and merge만 사용.
- `main`으로 향하는 PR은 `integrate-prod.yml`이 Spotless → Checkstyle → Test/JaCoCo → SonarQube Quality Gate → `bootJar` 순서로 실행되며, Quality Gate를 통과하지 못하면 머지할 수 없다.
- `main`에 머지되면 `deploy-prod.yml`이 GHCR(`ghcr.io/<owner>/ssccops-server`)로 이미지를 푸시하고 Coolify webhook으로 프로덕션 배포를 트리거한다.
