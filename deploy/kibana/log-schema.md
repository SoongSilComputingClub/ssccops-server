# 로그 필드 표준표 (SSOT · ssccops#341 · ADR-0024)

서버가 내는 로그 한 줄의 **필드 사전**이다. 계약(ECS 구조 · dataset 둘 · 보존)은
[ADR-0024](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0024-log-contract-ecs-datasets-retention.md)에
있고, 그 뒤 더해진 필드(`client.id` ADR-0026 · `url.path`·`http.request.method`·`user_agent.original` #389 ·
`service.version` #411)까지 **여기가 정본**이다. 순서는 이 프로젝트 원칙 그대로 — **표(사전)를 먼저 확정하고
코드가 따라간다.** 필드를 더하거나 이름을 바꾸면 이 표부터 고친 뒤 코드와 테스트를 맞춘다.

값을 내는 코드는 넷뿐이다. 표의 «출처» 열이 그것이고, 각 클래스의 테스트가 이름을 못 박는다.

| 출처 | 어느 줄에 | 테스트 |
|---|---|---|
| `global/logging/EcsJsonEncoder` | **모든** 줄 — 공통 구조 | `EcsJsonEncoderTest` |
| `global/audit/AuditLog` | 감사(`event.dataset = ssccops.audit`) | `AuditLogTest` · `AuditPointsTest` |
| `global/security/handler/RequestLogFields` (401 `CustomAuthenticationEntryPoint` · 403 `CustomAccessDeniedHandler`) | 인증·인가 거절 줄 | `CustomAuthenticationEntryPointTest` · `CustomAccessDeniedHandlerTest` |
| Logstash `deploy/logstash/pipeline/logstash.conf` | 서버가 안 낸 것을 채운다 | — |

**필드는 중첩 JSON이다** — `{"log":{"level":"INFO"}}`이지 `{"log.level":"INFO"}`가 아니다. Elasticsearch는 둘을 같게
보지만 Logstash 조건문은 중첩 키만 찾는다. 아래 «필드» 열의 점 표기는 그 중첩 경로를 뜻한다.

## 공통 — 모든 줄 (`EcsJsonEncoder`)

| 필드 | ECS 타입 | 언제 | 값 규칙 | 예시 |
|---|---|---|---|---|
| `@timestamp` | date | 항상 | UTC ISO 8601, 밀리초, `Z` | `2026-09-13T16:11:14.123Z` |
| `message` | text | 항상 | 사람이 읽는 문장. 감사 줄은 `AuditEvent.message` 없으면 `event.action` 값 | `Member grade changed` |
| `log.level` | keyword | 항상 | `TRACE` `DEBUG` `INFO` `WARN` `ERROR` | `INFO` |
| `log.logger` | keyword | 항상 | 로거 이름. 감사 줄은 고정 `AUDIT` | `org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl` |
| `process.thread.name` | keyword | 항상 | | `http-nio-8080-exec-3` |
| `service.name` | keyword | 항상 | `spring.application.name` = `ssccops-server` | `ssccops-server` |
| `service.environment` | keyword | 항상 | 활성 프로파일 `dev` · `prod` · `local`. 없으면 Logstash가 `unknown` | `dev` |
| `service.version` | keyword | **`META-INF/build-info.properties`가 있을 때** | `build.version` = `build.gradle`의 `version` = 릴리스 태그(`v` 뺀 값). `/actuator/info`의 `build.version`·배포 이력(`deploy-history` 브랜치)의 `version`과 **같은 파일**에서 나온다. 파일이 없으면(IDE 실행) 키 자체가 없다 — `unknown`을 지어내지 않는다 | `0.2.8` |
| `trace.id` | keyword | MDC `trace_id`가 있을 때만 | OpenTelemetry trace id(32 hex) | `4bf92f3577b34da6a3ce929d0e0e4736` |
| `span.id` | keyword | MDC `span_id`가 있을 때만 | 16 hex | `00f067aa0ba902b7` |
| `error.type` | keyword | 예외가 붙은 줄만 | 예외 클래스 단순 이름 | `IllegalStateException` |
| `error.message` | text | 예외가 붙은 줄만 | `Throwable.getMessage()` | `pool exhausted` |
| `error.stack_trace` | wildcard | 예외가 붙은 줄만 | 전체 스택 | `java.lang.IllegalStateException: …\n\tat …` |
| `event.dataset` | keyword | **인코더는 내지 않는다** | 감사 줄은 `AuditLog`가 `ssccops.audit`, 나머지는 Logstash가 `ssccops.application`을 채운다. 인코더가 먼저 내면 같은 키가 두 번 나가 JSON이 깨진다 | `ssccops.application` |

- `StructuredArguments.kv("x", map)`으로 넘긴 객체는 **루트에 그대로** 중첩된다. 아래 두 절의 필드가 전부 이 길로 실린다.
- 예외가 붙은 줄에 `kv("error", …)`를 함께 넘기지 말 것 — `error` 키가 두 번 나간다. 감사 실패 줄은 예외 없이 `error.code`만 싣는다.

## 감사 — `event.dataset = ssccops.audit` (`AuditLog`)

`log.logger = AUDIT` · `log.level = INFO` 고정. 성공은 **커밋 뒤**, 실패는 즉시 쓴다.

| 필드 | ECS 타입 | 언제 | 값 규칙 | 예시 |
|---|---|---|---|---|
| `event.dataset` | keyword | 항상 | 고정 `ssccops.audit` — Logstash가 이 값으로 `logs-ssccops.audit-{env}` stream에 넣는다 | `ssccops.audit` |
| `event.action` | keyword | 항상 | `AuditAction.code()` — `{도메인}.{대상}.{동사}` snake_case. **enum에 있는 값만** (자유 문자열 없음) | `member.grade.change` · `authz.deny` |
| `event.outcome` | keyword | 항상 | `success` · `failure` | `success` |
| `user.id` | keyword | 항상 | 행위자. 회원이면 **회원 id**(숫자 문자열) · 미가입이면 auth user id(UUID) + `user.signed_up=false` · 요청 밖(기동 시드·배치)이면 `system`. **이름·이메일 아님** | `42` · `system` |
| `user.signed_up` | boolean | 미가입 주체일 때만 | 고정 `false`. 가입자는 키 없음 | `false` |
| `client.id` | keyword | 토큰에 `client_id` 클레임이 있을 때만 | Supabase OAuth 2.1 클라이언트 id(MCP · Claude). 웹 로그인 토큰에는 없어 `client` 객체 자체가 없다 — «web»을 지어내지 않는다. 토큰 원문 없음 | `b1c4…` |
| `source.ip` | ip | 요청 안에서 부른 줄 | `X-Forwarded-For` 첫 값, 없으면 `remoteAddr`. **감사 줄에만** — 401·403·일반 줄에는 없다. 요청 밖이면 `source`가 빈 객체 `{}` | `203.0.113.7` |
| `audit.target.type` | keyword | 항상 | `AuditAction.targetType()` 기본값. 부르는 쪽이 `target(type, id)`로 바꿀 수 있다 | `member` · `sub_work` · `handler` |
| `audit.target.id` | keyword | 대상 id가 있을 때 | 식별자 문자열 | `17` |
| `audit.changed_fields` | keyword[] | 바뀐 필드가 있을 때 | **필드 이름만**. 값은 싣지 않는다(프로필 수정) | `["phoneNumber","email"]` |
| `audit.change.before` | keyword | 전후 값이 코드일 때 | 코드값(등급·상태) — 사람이 쓴 문장 아님 | `TEMP` |
| `audit.change.after` | keyword | 〃 (`after`만 있을 수 있다 — 전이·게시) | | `ASSOC` |
| `audit.decision` | keyword | 결정 분류가 있을 때 | 전이 이름 · 요구 권한 코드 · 역할 id · `rows=N`. **사유 문장 아님** | `APPROVE_COMPLETE` · `MEMBER_MANAGE` |
| `error.code` | keyword | `event.outcome = failure`일 때 | 응답의 `ApiResponse.code`와 같은 값 | `FORBIDDEN` · `MEMBER_HAS_DEPENDENTS` |

**개인정보가 실릴 자리가 없다** — `AuditEvent`가 식별자·필드 이름·코드값·결정 분류만 받는다. `AuditLogTest`가 출력에
전화·이메일 모양이 없는지 본다. 새 감사 지점에서 값을 실어 보내는 인자를 열지 말 것.

## 인증·인가 거절 — 401 · 403 (`RequestLogFields`)

`event.dataset` 없음(→ `ssccops.application`). `message`는 `Authentication failed: …` / `Access denied: …`.

| 필드 | ECS 타입 | 언제 | 값 규칙 | 예시 |
|---|---|---|---|---|
| `url.path` | wildcard | 항상 | `getRequestURI()` — **쿼리 문자열 없음** | `/v1/members/17` |
| `http.request.method` | keyword | 항상 | | `GET` |
| `user_agent.original` | keyword | `User-Agent` 헤더가 있을 때만 | 원문. 없으면 `user_agent` 객체 자체가 없다(빈 문자열을 지어내지 않는다) | `Mozilla/5.0 …` |
| `log.level` | keyword | | 401: `/mcp`·`/.well-known/**`는 `INFO`(MCP 발견 절차의 정상 첫 단계), 그 외 `WARN`. 403: 경로와 무관하게 `WARN` | `WARN` |

**싣지 않는 것**: `Authorization` 값(로그 열람 권한이 인증 권한이 된다) · 쿼리 문자열 · `source.ip`(감사 줄에만).

## Logstash가 더하는 것 (`deploy/logstash/pipeline/logstash.conf`)

| 필드 | 값 |
|---|---|
| `event.dataset` | 없으면 `ssccops.application` |
| `service.environment` | 없으면 `unknown` |
| `data_stream.type` / `dataset` / `namespace` | `logs` / `{event.dataset}` / `{service.environment}` → stream `logs-ssccops.{dataset}-{env}` |

`url.path`가 `/actuator`로 시작하는 줄은 **버린다**(헬스 프로브·배포 이력 폴링). 파싱·이름 바꾸기는 하지 않는다 — 모양은
서버가 정한다.

## 싣지 않는 것

| | 왜 |
|---|---|
| **접근 로그** (`http.response.status_code` · 요청마다 한 줄) | 지금은 401·403만 남긴다. 전 요청 로그는 볼륨(14일 보존 5GB rollover)과 «어느 회원이 어느 화면을 봤나»의 개인정보 판단이 따로 필요하다 — 정하기 전에는 열지 않는다 |
| **웹(Vercel · Cloudflare) 로그** | Vercel Hobby는 Log Drain이 없고(보존 1시간) Cloudflare Logpush는 유료. 웹은 범위 밖이며 **채널은 서버 로그의 `client.id`·`user_agent.original`로 가른다** |
| `source.ip` (감사 밖) | ADR-0024 — 개인정보를 좁게 |
| 이름 · 전화 · 이메일 · 학번 · 사유 문장 | 어느 줄에도 없다. `AuditEvent` 타입이 막고 `AuditLogTest`가 본다 |
| `Authorization` · 토큰 · 쿼리 문자열 | 위 401·403 절 |
| ECS의 나머지 필드 전부 | 쓰지 않는 필드를 표에 두면 사전이 아니라 목록이 된다 |

## 확인하는 곳

- 필드 이름이 바뀌었는지: 위 테스트 넷. `EcsJsonEncoderTest`는 **평면 키(점 든 이름)가 하나도 없음**도 본다.
- 실제로 Kibana에 잡히는지: dev 배포 뒤 Discover에서 `service.version : "0.2.x"` — 배포 이력 레코드의 `version`과 같아야 한다.
- 매핑은 `ecs@mappings` 컴포넌트 템플릿(`setup.sh`)이라 위 ECS 필드는 타입이 맞고, `audit.*`·`user.signed_up`은 동적 매핑이다
  (`changed_fields`는 keyword 배열, `signed_up`은 boolean으로 잡힌다).
