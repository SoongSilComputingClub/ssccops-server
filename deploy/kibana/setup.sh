#!/usr/bin/env sh
# Elasticsearch·Kibana 초기 설정 (ssccops#300 · ADR-0024). 여러 번 돌려도 같은 결과다.
#
# 어디서: Coolify → devops/core → elasticsearch-with-kibana → Terminal → elasticsearch 컨테이너.
# 그 컨테이너에는 curl과 ELASTIC_PASSWORD가 있고 kibana:5601 이 같은 스택 네트워크다.
#
#   curl -sL <이 파일의 raw 주소> | sh
#   # 또는 파일을 붙여 넣고: sh setup.sh
#
# 무엇을:
#   1. ILM 정책       ssccops-application(hot 7d rollover → 14d 삭제) · ssccops-audit(30d → 365d 삭제)
#   2. 인덱스 템플릿  logs-ssccops.application / logs-ssccops.audit — 기본 logs@* 위에 정책만 얹는다
#   3. Data View      logs-ssccops.application-* · logs-ssccops.audit-*
#   4. Kibana 역할    ssccops-ops(일반 읽기) · ssccops-audit-reader(감사 읽기)
#   5. 저장 객체      saved-objects.ndjson (대시보드 2 · 시각화 4 · 저장 검색 6) — 같은 디렉터리에 있을 때만
#
# 보존 기간을 바꾸려면 1번의 min_age 를 고치고 다시 돌린다 — 정책 갱신은 기존 backing index 에도 적용된다.
# 템플릿은 **새** backing index 부터 적용된다. 이미 있는 stream 에 바로 적용하려면
#   curl -u "$A" -X POST "$E/logs-ssccops.audit-dev/_rollover"
set -eu

E="${ES_URL:-http://localhost:9200}"
K="${KIBANA_URL:-http://kibana:5601}"
A="elastic:${ELASTIC_PASSWORD:?ELASTIC_PASSWORD 가 필요하다 (elasticsearch 컨테이너 안에서 돌릴 것)}"
H='Content-Type: application/json'
X='kbn-xsrf: true'

put() { # method url body
  code=$(curl -s -o /tmp/setup.out -w '%{http_code}' -u "$A" -H "$H" -H "$X" -X "$1" "$2" -d "$3")
  case "$code" in 2*) echo "  ok  $1 $2" ;; *) echo "  FAIL($code) $1 $2"; cat /tmp/setup.out; echo; exit 1 ;; esac
}

echo "1. ILM"
put PUT "$E/_ilm/policy/ssccops-application" '{"policy":{"phases":{"hot":{"actions":{"rollover":{"max_primary_shard_size":"5gb","max_age":"7d"}}},"delete":{"min_age":"14d","actions":{"delete":{}}}}}}'
put PUT "$E/_ilm/policy/ssccops-audit" '{"policy":{"phases":{"hot":{"actions":{"rollover":{"max_primary_shard_size":"5gb","max_age":"30d"}}},"delete":{"min_age":"365d","actions":{"delete":{}}}}}}'

echo "2. index templates"
put PUT "$E/_index_template/logs-ssccops.application" '{"index_patterns":["logs-ssccops.application-*"],"data_stream":{},"priority":500,"composed_of":["logs@mappings","logs@settings","ecs@mappings"],"template":{"settings":{"index.lifecycle.name":"ssccops-application","index.number_of_replicas":0}},"_meta":{"description":"ssccops application logs (ADR-0024) - 14d"}}'
put PUT "$E/_index_template/logs-ssccops.audit" '{"index_patterns":["logs-ssccops.audit-*"],"data_stream":{},"priority":500,"composed_of":["logs@mappings","logs@settings","ecs@mappings"],"template":{"settings":{"index.lifecycle.name":"ssccops-audit","index.number_of_replicas":0}},"_meta":{"description":"ssccops audit logs (ADR-0024) - 365d"}}'

echo "3. data views"
put POST "$K/api/data_views/data_view" '{"data_view":{"id":"logs-ssccops-application","title":"logs-ssccops.application-*","name":"ssccops · application logs","timeFieldName":"@timestamp"},"override":true}'
put POST "$K/api/data_views/data_view" '{"data_view":{"id":"logs-ssccops-audit","title":"logs-ssccops.audit-*","name":"ssccops · audit logs","timeFieldName":"@timestamp"},"override":true}'

echo "4. kibana roles"
put PUT "$K/api/security/role/ssccops-ops" '{"elasticsearch":{"indices":[{"names":["logs-ssccops.application-*"],"privileges":["read","view_index_metadata"]}]},"kibana":[{"base":["read"],"spaces":["default"]}]}'
put PUT "$K/api/security/role/ssccops-audit-reader" '{"elasticsearch":{"indices":[{"names":["logs-ssccops.audit-*"],"privileges":["read","view_index_metadata"]}]},"kibana":[{"base":["read"],"spaces":["default"]}]}'

echo "5. saved objects"
if [ -f "$(dirname "$0")/saved-objects.ndjson" ]; then
  curl -s -u "$A" -H "$X" -X POST "$K/api/saved_objects/_import?overwrite=true" -F "file=@$(dirname "$0")/saved-objects.ndjson" | head -c 200; echo
else
  echo "  saved-objects.ndjson 이 옆에 없다 — Kibana → Stack Management → Saved Objects → Import 로 넣는다"
fi
echo "done"
