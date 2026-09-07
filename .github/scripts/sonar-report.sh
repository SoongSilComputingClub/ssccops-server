#!/usr/bin/env bash
# ============================================================================
# SonarQube 분석 결과를 읽어 PR 코멘트와 job 요약으로 남긴다.
# ============================================================================
# integrate-dev.yml(develop PR)과 integrate-prod.yml(main)이 **함께 쓴다.**
# 스크립트로 뺀 이유는 두 워크플로에 90줄짜리 bash가 복제되는 것을 막기 위해서다 —
# 그 복제는 이 저장소가 이미 경계하는 것이다(integrate-dev.yml 주석: "두 워크플로가 다른
# 명령을 쓰면 develop에서 통과한 코드가 main에서 떨어진다").
#
# **Quality Gate가 실패해도 이 스크립트는 0으로 끝난다** (ssccops#231).
# 처음 분석을 켜면 기존 코드의 지적이 수백 건 나오는데, 그 상태로 게이트를 잠그면
# 아무것도 머지할 수 없다. 먼저 숫자를 보고, 기준을 정한 뒤에 잠근다.
#
# 반대로 **인프라 오류(report-task.txt 없음·CE 태스크 실패)는 그대로 실패시킨다.**
# 토큰이 비어 분석이 안 된 것과 품질이 나쁜 것은 다른 일인데, 그 둘이 같은 실패로 보고되던
# 것이 이 이슈의 출발점이었다 — v0.2.1 릴리스까지 다섯 번의 Analyze가 전부 "실패"였고
# 원인은 언제나 빈 SONAR_TOKEN이었다. 신호를 뭉개면 그 상태로 돌아간다.
#
# 필요한 환경변수:
#   SONAR_TOKEN · SONAR_HOST_URL   분석 서버 접속
#   GH_TOKEN                       PR 코멘트 작성 (gh CLI)
#   REPO                           owner/repo
#   BRANCH                         분석 대상 브랜치명
#   PR_NUMBER                      (선택) 있으면 PR에 코멘트를 단다
# ============================================================================
set -euo pipefail

REPORT_FILE="${REPORT_FILE:-build/sonar/report-task.txt}"

if [ ! -f "$REPORT_FILE" ]; then
  echo "::error::$REPORT_FILE 이 없다. 분석이 실제로 돌지 않았다."
  exit 1
fi

CE_TASK_ID=$(grep '^ceTaskId=' "$REPORT_FILE" | cut -d'=' -f2)
PROJECT_KEY=$(grep '^projectKey=' "$REPORT_FILE" | cut -d'=' -f2)
DASHBOARD_URL=$(grep '^dashboardUrl=' "$REPORT_FILE" | cut -d'=' -f2-)

echo "ProjectKey: $PROJECT_KEY"
echo "Branch: $BRANCH"

# 브랜치명을 URL 인코딩한다. 이 저장소의 브랜치는 `{type}/#{이슈번호}-{슬러그}` 형식이라
# **이름에 `#`이 들어간다** — 그대로 쿼리에 끼우면 curl 이 그 뒤를 fragment 로 잘라내
# `branch=chore/` 만 전송되고, 없는 브랜치라 응답이 비어 커버리지가 0%로 보고된다.
# 실제로 #282 의 첫 실행이 그렇게 나왔다.
BRANCH_ENC=$(jq -rn --arg v "$BRANCH" '$v|@uri')

# ----------------------------------------------------------------------------
# CE 태스크가 끝나기를 기다린다 (분석 제출과 집계는 비동기다)
# ----------------------------------------------------------------------------
TASK_STATUS=""
for i in $(seq 1 30); do
  STATUS_JSON=$(curl -s -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/ce/task?id=$CE_TASK_ID")
  TASK_STATUS=$(echo "$STATUS_JSON" | jq -r '.task.status // "UNKNOWN"')

  if [ "$TASK_STATUS" = "SUCCESS" ]; then
    break
  fi

  # FAILED·CANCELED는 기다려도 바뀌지 않는다 — 30회를 채울 이유가 없다
  if [ "$TASK_STATUS" = "FAILED" ] || [ "$TASK_STATUS" = "CANCELED" ]; then
    echo "::error::SonarQube CE 태스크가 $TASK_STATUS 로 끝났다."
    exit 1
  fi

  echo "Waiting for SonarQube task... ($i)"
  sleep 5
done

if [ "$TASK_STATUS" != "SUCCESS" ]; then
  echo "::error::SonarQube CE 태스크가 150초 안에 끝나지 않았다 (마지막 상태: $TASK_STATUS)."
  exit 1
fi

ANALYSIS_ID=$(echo "$STATUS_JSON" | jq -r '.task.analysisId')

# ----------------------------------------------------------------------------
# Quality Gate · 이슈 · 측정값
#   jq의 `// []` `// "0"` 폴백은 그대로 둔다 — 에디션·설정에 따라 branch 파라미터가 무시되거나
#   빈 응답이 올 수 있는데, 그때 리포트가 죽는 것보다 0으로 보이는 편이 낫다.
#   **다만 그 폴백이 진짜 오류를 가린 적이 있다**(위 BRANCH_ENC 주석) — 0%가 나오면
#   "커버리지가 없다"가 아니라 "질의가 빗나갔다"부터 의심할 것.
# ----------------------------------------------------------------------------
QG_JSON=$(curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$ANALYSIS_ID")
QG_STATUS=$(echo "$QG_JSON" | jq -r '.projectStatus.status // "UNKNOWN"')

# 개수는 **facet으로 센다.** `.issues` 배열의 길이를 세면 안 된다 (ssccops#236).
#
# api/issues/search 의 기본 페이지 크기는 100이다. ps 없이 부르고 `.issues | length` 를 세면
# 전체 개수가 아니라 **첫 100건 안에서 타입별로 몇 개인지**를 세게 된다. 실제로 그랬다 —
# ssccops#232 로 분석 대상을 43% 늘렸는데(색인 460 → 697) 두 실행의 합이 정확히 100으로
# 같았다(1+71+28 = 0+71+29 = 100). "취약점 71건"은 처음부터 총계가 아니었다.
#
# ps 를 500 으로 올리는 것은 답이 아니다 — 상한만 옮기고, 넘어가는 순간 같은 오류가 조용히
# 돌아오며 넘었다는 사실조차 알 수 없다. facet 은 페이지와 무관하게 전체를 센다.
# 그래서 ps=1 로 본문을 최소화하고 facets=types 의 count 만 읽는다.
ISSUES_JSON=$(curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/issues/search?projectKeys=$PROJECT_KEY&branch=$BRANCH_ENC&resolved=false&ps=1&facets=types")

# facet 이 비어 있어도 리포트는 살아야 한다 — 이 파일의 다른 폴백과 같은 태도다.
issue_count() {
  echo "$ISSUES_JSON" | jq -r --arg t "$1" \
    '[ (.facets // [])[] | select(.property=="types") | (.values // [])[] | select(.val==$t) | .count ] | first // 0'
}
BUGS=$(issue_count BUG)
VULNS=$(issue_count VULNERABILITY)
SMELLS=$(issue_count CODE_SMELL)

MEASURES_JSON=$(curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/measures/component?component=$PROJECT_KEY&branch=$BRANCH_ENC&metricKeys=coverage,duplicated_lines_density")
COVERAGE=$(echo "$MEASURES_JSON" | jq -r '.component.measures // [] | map(select(.metric=="coverage")) | .[0].value // "0"')
DUPLICATION=$(echo "$MEASURES_JSON" | jq -r '.component.measures // [] | map(select(.metric=="duplicated_lines_density")) | .[0].value // "0"')

if [ "$QG_STATUS" = "OK" ]; then
  ICON="✅"
  RESULT="PASSED"
else
  ICON="⚠️"
  RESULT="$QG_STATUS"
fi

BODY=$(cat <<EOF
## SonarQube 분석 결과

${ICON} **Quality Gate ${RESULT}**

**브랜치:** \`${BRANCH}\`

### 이슈
- 버그: ${BUGS}
- 취약점: ${VULNS}
- 코드 스멜: ${SMELLS}

### 측정값
- 커버리지: ${COVERAGE}%
- 중복도: ${DUPLICATION}%

Dashboard: ${DASHBOARD_URL}&branch=${BRANCH_ENC}

> **보안 핫스팟은 위 숫자에 없다** — 별도 API(\`api/hotspots/search\`)라 세지 않는다. 취약점 수가 보안 지적의 전부가 아니다.
>
> Quality Gate는 **머지를 막지 않는다** (ssccops#231). 기준을 정한 뒤에 잠근다.
EOF
)

echo "$BODY" >> "$GITHUB_STEP_SUMMARY"

if [ -n "${PR_NUMBER:-}" ]; then
  gh api "repos/$REPO/issues/$PR_NUMBER/comments" -f body="$BODY"
fi

# Quality Gate 실패로 이 스크립트를 실패시키지 않는다 — 위 주석 참고.
if [ "$QG_STATUS" != "OK" ]; then
  echo "::warning::Quality Gate 가 $QG_STATUS 다. 지금은 막지 않는다 (ssccops#231)."
fi
