# ssccops-server

[![Integrate](https://github.com/SoongSilComputingClub/ssccops-server/actions/workflows/integrate-dev.yml/badge.svg?branch=develop)](https://github.com/SoongSilComputingClub/ssccops-server/actions/workflows/integrate-dev.yml)

SSCC(숭실대학교 컴퓨팅 동아리) **운영 시스템의 API**입니다. 동아리 운영에 필요한 일 — 업무·회의·결재,
회원과 권한, 폼과 응답, 학술 프로그램, 행사와 신청, 홍보 콘텐츠, 알림 — 을 한 서버가 맡고,
웹 세 앱([`ssccops-web`](https://github.com/SoongSilComputingClub/ssccops-web))이 이 서버를 봅니다.

Spring Boot 3.5 · Java 17 · Gradle · PostgreSQL · Flyway. 인증은 Supabase Auth가 발급한 JWT를
리소스 서버로 검증하고, 권한은 트리 구조의 자체 권한 코드로 판정합니다.

## 도메인

도메인별 규칙·결정·함정은 **각 도메인의 `AGENTS.md`가 정본**입니다.

| 도메인 | 무엇 |
|---|---|
| [`operation`](src/main/java/org/sscc/ssccopsserver/domain/operation) | 업무·하위 업무·회의와 승인·점검 목록·상태 이력 |
| [`member`](src/main/java/org/sscc/ssccopsserver/domain/member) | 회원·등급·역할·권한 트리 |
| [`form`](src/main/java/org/sscc/ssccopsserver/domain/form) | 폼·문항·응답·검토(수정 요청) |
| [`event`](src/main/java/org/sscc/ssccopsserver/domain/event) | 행사·분류·참가 신청 |
| [`academicprogram`](src/main/java/org/sscc/ssccopsserver/domain/academicprogram) | 학술 프로그램 모집·회차·출석·기획안 |
| [`content`](src/main/java/org/sscc/ssccopsserver/domain/content) | 공개 사이트의 페이지·포스트 |
| [`notification`](src/main/java/org/sscc/ssccopsserver/domain/notification) | 알림과 웹 푸시(VAPID) |
| [`file`](src/main/java/org/sscc/ssccopsserver/domain/file) | 첨부·이미지(Cloudflare R2, 읽기는 서명된 URL) |
| [`share`](src/main/java/org/sscc/ssccopsserver/domain/share) | 공유 링크와 미리보기 |
| [`assistant`](src/main/java/org/sscc/ssccopsserver/domain/assistant) | 규정 도우미 — 회칙 검색·답변(RAG) |
| [`auth`](src/main/java/org/sscc/ssccopsserver/domain/auth) | 세션 조회·가입 |

운영 데이터를 Claude에서 다루는 **MCP 서버**를 `/mcp`로 함께 제공합니다(인증은 웹과 같은 JWT).

## 빠른 시작

필요한 것: Java 17 · Docker(PostgreSQL용) · Gradle은 wrapper를 씁니다

```bash
cp .env.example .env          # DB_*, SUPABASE_URL 을 채웁니다
docker compose up postgres    # DB만 띄우고
./gradlew bootRun             # 앱은 호스트에서 (http://localhost:8080)
```

앱까지 컨테이너로 띄우려면 `docker compose up` 한 줄이면 됩니다(`DB_HOST`를 compose가 덮어씁니다).
`.env` 한 벌을 `docker compose`와 `bootRun`이 함께 읽습니다.

확인: `GET /actuator/health` · API 문서는 `local`·`dev`에서 `/swagger-ui.html`(prod는 끕니다).
**DB를 새로 만들면 첫 가입자가 최고관리자가 됩니다.**

### 환경 변수

정본은 [`.env.example`](.env.example)입니다.

| 필수 | 무엇 |
|---|---|
| `DB_NAME` · `DB_USERNAME` · `DB_PASSWORD` · `DB_HOST` · `DB_PORT` | PostgreSQL 접속 |
| `SUPABASE_URL` | JWT 검증용 JWKS·issuer. **웹 세 앱과 같은 프로젝트**여야 합니다 |
| `FRONTEND_URL` | CORS 허용 오리진(쉼표로 여러 개) |
| `APP_PUBLIC_BASE_URL` | 이 API 자신의 공개 주소 — 이미지 읽기 주소를 조립합니다 |

| 선택 | 없으면 |
|---|---|
| `R2_*` | 파일·이미지 업로드만 동작하지 않습니다 — 단 **변수 자체는 있어야 기동합니다**(빈 값으로 두세요) |
| `GEMINI_API_KEY` | 규정 도우미(RAG)만 동작하지 않습니다 |
| `SSCCOPS_PUSH_VAPID_*` | 웹 푸시만 동작하지 않습니다(알림 목록은 그대로) |

## 검사

```bash
./gradlew build          # compileJava → checkstyle → spotless → test → jacoco
./gradlew test
./gradlew spotlessApply  # 포맷 자동 정렬
```

PR마다 CI가 같은 셋과 Sonar, **OpenAPI 하위 호환 검사**를 돌립니다 — 응답 필드 삭제·타입 변경처럼
웹을 깨뜨리는 변경은 라벨 승인 없이는 막힙니다.

### 스키마

**Flyway 마이그레이션이 정본**입니다(`src/main/resources/db/migration`). `local`은 `ddl-auto: update`,
`dev`·`prod`는 `validate`라 스키마 변경은 반드시 마이그레이션 파일로 들어갑니다. 테스트는 H2 인메모리에
Flyway를 끄고 돕니다.

## 기여

1. 이슈를 먼저 만듭니다(운영 요구는 비공개 메타 저장소에서 옵니다). 이슈를 열면 봇이
   `{type}/#N` 브랜치를 만듭니다 — `feat/#123` · `fix/#123` · `refactor/#123` · `chore/#123`
2. 커밋 첫 줄은 `#N type(scope): 무엇을`
3. PR 제목은 `[#N] 무엇을`, 본문에 `- 근거: ssccops#M` 한 줄이 있어야 통과합니다(`pr-guard`)
4. `develop`으로 **squash** 머지합니다. `main`은 릴리스 전용입니다

**개발 규칙의 정본은 [`AGENTS.md`](AGENTS.md)**이고 도메인마다 자기 `AGENTS.md`가 있습니다 —
계층 구조, 응답 봉투와 오류 코드, 권한 판정, 테스트 규약, 감사 로그가 그쪽에 있습니다. README는
그것을 옮겨 적지 않습니다.
