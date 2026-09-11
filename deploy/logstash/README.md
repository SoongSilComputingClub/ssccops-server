# Logstash — 서버 로그를 Elasticsearch data stream으로 (ssccops#297 · ADR-0024)

이 디렉터리가 **정본**이고 Coolify에 붙여 넣은 것은 사본이다 — `Dockerfile`과 Coolify의
관계와 같다. 파이프라인을 고치면 여기서 고치고 Coolify compose의 `content:` 블록에 다시
붙여 넣는다.

| 파일 | 무엇 |
|---|---|
| `pipeline/logstash.conf` | 파이프라인. TCP 5000 `json_lines` 입력 → `event.dataset`으로 data stream 라우팅. **파싱하지 않는다** |
| `compose.snippet.yml` | Coolify `devops / core / elasticsearch-with-kibana` 스택에 넣은 `logstash`·`logstash-key-generator` 두 서비스 |

## 어디에 붙이나

Coolify → `devops` 프로젝트 → `core` → `elasticsearch-with-kibana` → **Edit Compose file**.
`services:` 아래에 두 서비스를 더한다. 파이프라인은 `content:` 블록으로 컨테이너 안
`/usr/share/logstash/pipeline/logstash.conf`에 놓인다(이미지의 기본 `logstash.conf`를 덮어쓴다 —
같은 디렉터리에 다른 이름으로 두면 기본 파이프라인과 **합쳐져** beats 입력·stdout 출력이 붙는다).

스택의 **Network attachment는 «Connect to the predefined Coolify network»**여야 한다. 그래야
다른 프로젝트의 api 컨테이너가 `logstash-<스택 uuid>:5000`에 닿는다(스택 네트워크만 쓰면 닿지 않는다).

## 자격 — `elastic`을 쓰지 않는다

`logstash-key-generator`가 배포마다 한 번 돌며 ES에 역할 `logstash_writer`(`logs-ssccops.*`에
`auto_configure`·`create_doc`·`create_index`·`view_index_metadata`, cluster `monitor`)를 PUT 하고
(역할을 넓히거나 좁히려면 이 파일을 고쳐 재배포한다), 같은 이름의 사용자를 다룬다:

| 상황 | 하는 일 |
|---|---|
| 사용자 없음 | 만들고 비밀번호를 **Runtime Logs에 한 번** 찍는다 |
| 사용자 있음 · `LOGSTASH_ES_PASSWORD` 비어 있음 | 비밀번호를 **새로 발급**해 찍는다 — 값을 잃었거나 회전하려는 경우 |
| 사용자 있음 · `LOGSTASH_ES_PASSWORD` 있음 | 아무것도 안 한다 |

찍힌 값을 스택 환경변수 `LOGSTASH_ES_PASSWORD`에 넣고 **Force Restart** 한다(«Restart»는
compose 변경을 다시 읽지 않는다 — 새 서비스·`content:`·env 변경은 Force Restart 여야 적용된다).
회전은 변수를 비우고 Force Restart 두 번(발급 → 넣기)이다.

⚠️ compose의 `content:` 블록은 **처음 저장할 때만** 파일이 된다. 그 뒤 compose에서 `content:`를
고쳐도 파일은 바뀌지 않는다 — **Persistent Storage → Logstash → 파일 Content**에서 고치고 저장한
뒤 Force Restart 한다. 이 README의 파이프라인과 그 화면의 내용이 같아야 한다.

API key로 붙이지 않는 이유: Logstash의 `elasticsearch` output은 `api_key` 인증에 TLS를 요구하는데
이 ES는 스택 안에서만 열린 HTTP다. 첫 시도가 그 오류(`Using api_key authentication requires
SSL/TLS`)로 죽었고 generator가 그 key를 거둔다.

## 서버 쪽

api 컨테이너 환경변수(Coolify `ssccops-server` 프로젝트, dev·prod 각각):

```
LOGSTASH_HOST=logstash-<스택 uuid>     # 컨테이너 이름. Coolify 가 서비스명-uuid 로 짓는다
LOGSTASH_PORT=5000                     # 생략 가능(기본값)
```

없으면 appender가 등록되지 않는다(`logback-spring.xml`). 어느 stream으로 가는지는
`service.environment`(= 활성 프로파일)가 정하므로 dev·prod가 Logstash 하나를 같이 쓴다:

- `logs-ssccops.application-dev` · `logs-ssccops.application-prod`
- `logs-ssccops.audit-dev` · `logs-ssccops.audit-prod`

## 검증

Logstash 컨테이너의 Terminal 또는 같은 네트워크의 아무 컨테이너에서:

```sh
printf '%s\n' '{"@timestamp":"2026-09-11T00:00:00Z","message":"hello","log":{"level":"INFO"},"service":{"name":"ssccops-server","environment":"dev"},"event":{"dataset":"ssccops.audit"}}' \
  | nc logstash 5000
```

Kibana → Discover → `logs-ssccops.audit-dev`에 그 줄이 보이면 끝. 안 보이면 Logstash Runtime
Logs의 `[logstash.outputs.elasticsearch]`부터 본다 — 401이면 비밀번호, `index_not_found`류면
역할의 인덱스 패턴.
