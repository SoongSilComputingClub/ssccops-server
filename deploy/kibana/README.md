# Kibana · Elasticsearch 설정 (ssccops#300 · ADR-0024)

Logstash(`../logstash`)가 stream에 쓰기 시작한 뒤 Kibana가 알아야 하는 것들. 전부 `setup.sh` 한
번으로 다시 세울 수 있고, 2026-09-11에 실제로 그 순서로 세웠다.

| 파일 | 무엇 |
|---|---|
| `setup.sh` | ILM · 인덱스 템플릿 · Data View · Kibana 역할 · 저장 객체 import. idempotent |
| `saved-objects.ndjson` | 대시보드 2(감사 · 일반) + 패널. Kibana Saved Objects export 형식 |

## 보존 (ILM)

| stream | 정책 | rollover | 삭제 |
|---|---|---|---|
| `logs-ssccops.application-*` | `ssccops-application` | 7일 또는 5GB | **14일** |
| `logs-ssccops.audit-*` | `ssccops-audit` | 30일 또는 5GB | **365일** |

값은 ADR-0024의 초기값이다. 바꾸려면 `setup.sh`의 `min_age`를 고치고 다시 돌린다 — 정책 갱신은
기존 backing index에도 적용된다. 템플릿(정책 연결)은 **새** backing index부터라, 이미 있는
stream에 바로 걸려면 `_rollover`를 한 번 친다.

## 접근

| Kibana 역할 | 읽는 것 | 누구에게 |
|---|---|---|
| `ssccops-ops` | `logs-ssccops.application-*` | 운영진 |
| `ssccops-audit-reader` | `logs-ssccops.audit-*` | 회장·부회장급 |

`elastic`은 사람에게 주지 않는다. 사용자는 Kibana → Stack Management → Users에서 만들고 위 역할을
붙인다(둘 다 붙이면 둘 다 본다).

## 대시보드

- **ssccops · 감사 로그** — 시간대별 `event.action` · 행위자별 건수 · 실패 목록 · 인가 거절 · 회원
  상세 조회 · 전체
- **ssccops · 일반 로그** — 시간대별 `log.level` · 로거별 · 환경별 · ERROR · WARN

Kibana에서 고친 뒤에는 **다시 export 해서 이 파일을 덮어쓴다**: Stack Management → Saved Objects →
두 대시보드 선택 → Export(관련 객체 포함) → `saved-objects.ndjson`. 정본이 레포이므로 화면에서만
고치고 두면 다음 재설치에서 사라진다.

## 확인

```
GET _cat/indices/.ds-logs-ssccops*?h=index,ilm.policy,docs.count
GET logs-ssccops.audit-dev/_ilm/explain
```
