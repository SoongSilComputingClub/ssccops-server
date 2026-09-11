#!/usr/bin/env bash
# ============================================================================
# 규칙 키를 받아 그 규칙의 지적 위치(파일·줄·메시지)를 job 요약에 찍는다 (ssccops#284).
# ============================================================================
# **분석은 돌리지 않는다.** 이미 서버에 있는 결과를 조회만 하므로 가볍고, 분석 자리를
# 덮어쓰지도 않는다(이 서버는 브랜치를 가르지 못해 분석 한 자리를 모두가 공유한다 — #238).
#
# 부르는 곳은 sonar-issues.yml(workflow_dispatch) 하나다. sonar-report.sh 가 규칙별 **건수**를
# 보여주면, 여기서 «이 규칙을 지금 고치겠다»고 정한 것의 **위치**를 한 번 본다. 매 리포트에
# 위치까지 찍지 않는 이유는 규칙 수십 개 × 건수 수백이라 요약이 읽히지 않기 때문이다.
#
# 필요한 환경변수:
#   SONAR_TOKEN · SONAR_HOST_URL   분석 서버 접속
#   PROJECT_KEY                    sonar-project.properties 의 projectKey
#   RULES                          쉼표로 이은 규칙 키 (예: java:S6809,java:S2638)
#   NEW_ONLY                       "true" 면 New Code 기간의 지적만 (게이트가 보는 창)
# ============================================================================
set -euo pipefail

if [ -z "${RULES:-}" ]; then
  echo "::error::RULES 가 비어 있다. 쉼표로 이은 규칙 키를 넣어라 (예: java:S6809,java:S2638)."
  exit 1
fi

NEW_FILTER=""
if [ "${NEW_ONLY:-false}" = "true" ]; then
  NEW_FILTER="&inNewCodePeriod=true"
fi

{
  echo "## SonarQube 지적 위치"
  echo
  echo "**프로젝트:** \`$PROJECT_KEY\` · **규칙:** \`$RULES\`$([ "${NEW_ONLY:-false}" = "true" ] && echo " · **새 코드만**")"
  echo
} >> "$GITHUB_STEP_SUMMARY"

IFS=',' read -ra RULE_LIST <<< "$RULES"
for RULE in "${RULE_LIST[@]}"; do
  RULE=$(echo "$RULE" | xargs)
  [ -z "$RULE" ] && continue

  NAME=$(curl -s -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/rules/show?key=$RULE" \
    | jq -r '.rule.name // empty' 2>/dev/null || true)

  # 500건씩 최대 5쪽 — 그 이상이면 규칙 하나가 2,500건이라 위치 목록으로 읽을 것이 아니다.
  ROWS=""
  TOTAL=0
  for PAGE in 1 2 3 4 5; do
    JSON=$(curl -s -u "$SONAR_TOKEN:" \
      "$SONAR_HOST_URL/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&rules=$RULE&ps=500&p=$PAGE${NEW_FILTER}")
    TOTAL=$(echo "$JSON" | jq -r '.paging.total // 0')
    PAGE_ROWS=$(echo "$JSON" | jq -r --arg key "$PROJECT_KEY" '
      (.issues // [])[]
      | "| `\(.component | ltrimstr($key + ":"))` | \(.line // "-") | \(.severity // "") | \(.message | gsub("\\|"; "\\|")) |"')
    ROWS="${ROWS}${PAGE_ROWS:+$PAGE_ROWS
}"
    COUNT=$(echo "$JSON" | jq -r '(.issues // []) | length')
    [ "$COUNT" -lt 500 ] && break
  done

  {
    echo "### \`$RULE\`${NAME:+ — $NAME} · ${TOTAL}건"
    echo
    if [ -z "$ROWS" ]; then
      echo "지적 없음."
    else
      echo "| 파일 | 줄 | 심각도 | 메시지 |"
      echo "|---|---|---|---|"
      printf '%s' "$ROWS"
    fi
    echo
  } | tee -a "$GITHUB_STEP_SUMMARY"
done
