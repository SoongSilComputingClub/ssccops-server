#!/usr/bin/env bash
# ============================================================================
# SonarQube 분석 결과를 읽어 job 요약으로 남긴다.
# ============================================================================
# **부르는 곳은 integrate-dev.yml 의 analyze job 하나다** (develop push, ssccops#238).
# 예전에는 integrate-prod.yml(main)도 같은 스크립트를 썼는데, 이 서버는 Community Build 라
# 브랜치를 가르지 못해 **두 워크플로의 분석이 같은 자리를 덮어썼다** — 그래서 main 쪽을
# 걷어냈다. 스크립트 형태는 그대로 둔다: PR 분석이 돌아오는 날 부르는 곳이 다시 늘어난다.
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
#   REF_NAME                       분석한 ref (표시용. 질의에는 쓰지 않는다 — 아래 참고)
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
echo "Ref: ${REF_NAME:-?}"

# ----------------------------------------------------------------------------
# **질의에 branch 파라미터를 넣지 않는다** (ssccops#238).
#
# 이 서버는 SonarQube Community Build 26.8.0 이고 브랜치 플러그인이 없다(ssccops#234).
# 그래서 스캐너가 `sonar.branch.name` 을 선언하지 못하고 — 선언하면 업그레이드하라는 오류로
# 분석이 죽는다 — **모든 분석이 프로젝트 기본 브랜치 한 자리에 쌓인다.**
#
# 그 상태에서 `&branch=<브랜치명>` 으로 조회하면 **없는 브랜치를 묻는 것**이라 응답이 비고,
# 아래 `// "0"` 폴백이 그것을 0%로 보고했다. #284 가 URL 인코딩을 고쳤지만 그것은 다른
# 결함이었고, 이쪽은 인코딩이 맞아도 여전히 빗나간다 — **제출할 때 브랜치를 밝히지 않았으니
# 조회에서 무엇을 하든 같은 데이터를 되읽는다.**
#
# 그래서 파라미터를 뺀다. 지금 분석이 develop push 한 곳에서만 돌므로 프로젝트 기본 브랜치의
# 상태가 곧 develop 의 상태다. 리포트도 그렇게 말한다.
#
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
#   jq의 `// []` `// "0"` 폴백은 그대로 둔다 — 응답이 비었을 때 리포트가 죽는 것보다 0으로
#   보이는 편이 낫다.
#   **다만 그 폴백이 진짜 오류를 두 번 가렸다** — 브랜치명 인코딩(#284)과 branch 파라미터
#   자체(#238). 0%가 나오면 "커버리지가 없다"가 아니라 "질의가 빗나갔다"부터 의심할 것.
# ----------------------------------------------------------------------------
QG_JSON=$(curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$ANALYSIS_ID")
QG_STATUS=$(echo "$QG_JSON" | jq -r '.projectStatus.status // "UNKNOWN"')

# **무엇이 게이트를 깨뜨렸는가** (ssccops#233 · #235).
#
# 그전까지 이 스크립트는 `ERROR` 한 단어만 찍었다. 응답의 conditions[] 에 어느 지표가 어느
# 임계값에서 걸렸는지가 **이미 들어 있는데** 버리고 있었다 — 추가 왕복이 없다.
#
# ssccops#235(게이트를 언제·어떤 기준으로 잠글지)는 이 목록 없이는 시작할 수 없다.
# 무엇이 걸리는지 모르는 채로 임계값을 정할 수는 없다.
QG_CONDITIONS=$(echo "$QG_JSON" | jq -r '
  [ (.projectStatus.conditions // [])[] | select(.status != "OK") ]
  | if length == 0 then empty
    else ("| 지표 | 실제 | 조건 | 임계값 |", "|---|---|---|---|"),
         (.[] | "| `\(.metricKey)` | \(.actualValue // "?") | \(.comparator // "?") | \(.errorThreshold // "?") |")
    end')

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
# 프로젝트 필터는 `componentKeys` 다. 두 가지를 실제로 시도해 보고 정했다 (ssccops#237).
#
# | 파라미터 | 결과 |
# |---|---|
# | `projectKeys` | **존재하지 않는 이름.** SonarQube 는 모르는 파라미터를 오류로 만들지 않고 조용히 무시한다 — 필터가 통째로 빠져 인스턴스 전체가 돌아왔다 |
# | `components`  | 0건. 이 버전에서는 파일·디렉터리 키를 기대하는 것으로 보인다 |
# | `componentKeys` | 프로젝트 키를 받는다 |
#
# `projectKeys` 로 돌던 동안 ssccops-server 리포트의 상위가 `typescript:S6759` 235건이었다 —
# **이 저장소에는 .ts 파일이 한 개도 없다.** server 와 web 두 프로젝트의 숫자가 합쳐져 있었고,
# 직전에 고친 "총계 761건"(ssccops#236)도 그 합이었다. 합계만 볼 때는 아무도 이상하다고
# 느끼지 못했고, 규칙 분포를 찍고 나서야 드러났다.
#
# **그리고 `branch` 를 붙이지 않는다.** 이 서버에는 브랜치 분석이 없다(Community Edition
# 추정 — 확인은 ssccops#234). 유효한 프로젝트 필터에 `branch` 를 함께 주면 그 브랜치가
# 존재하지 않는 것으로 취급돼 **0건이 돌아온다.** 실제로 그렇게 나왔다.
#
# 그동안 이 사실이 가려져 있었던 것은 필터 이름이 틀려(`projectKeys`) 조건이 통째로 무시됐기
# 때문이다 — 필터가 없으니 branch 도 함께 무시됐고 인스턴스 전체가 돌아왔다. 필터를 고치는
# 순간 branch 가 살아나 0건이 됐다. **#284 가 고친 URL 인코딩도 같은 자리다** — 인코딩이
# 맞아도 없는 브랜치를 가리키는 것은 그대로였고, **커버리지가 계속 0%였던 이유가 이것이다.**
#
# 그래서 지금 숫자는 **프로젝트 기본 브랜치 기준**이다. 브랜치별로 보려면 에디션이
# 먼저다(ssccops#234). branch 파라미터는 질의에서도 대시보드 링크에서도 걷어냈다(ssccops#238).
#
# **검증은 세 가지다: (1) typescript: 규칙이 나오면 필터가 또 빠진 것이고,
# (2) 전부 0이면 필터가 너무 좁은 것이며, (3) 커버리지가 0%면 branch 가 되살아난 것이다.**
ISSUES_JSON=$(curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=1&facets=types,rules")

# facet 이 비어 있어도 리포트는 살아야 한다 — 이 파일의 다른 폴백과 같은 태도다.
issue_count() {
  echo "$ISSUES_JSON" | jq -r --arg t "$1" \
    '[ (.facets // [])[] | select(.property=="types") | (.values // [])[] | select(.val==$t) | .count ] | first // 0'
}
BUGS=$(issue_count BUG)
VULNS=$(issue_count VULNERABILITY)
SMELLS=$(issue_count CODE_SMELL)

# 규칙별 상위 목록 (ssccops#237).
#
# 타입별 합계만으로는 **무엇부터 볼지 알 수 없다.** 761건이 761가지 문제인 경우는 드물고,
# 같은 규칙이 여러 파일에서 걸린 것이 대부분이라 규칙으로 묶으면 판단 단위가 몇 개로 줄어든다.
# facets=rules 는 위 요청에 이미 얹혀 오므로 추가 왕복이 없다.
#
# **PR 코멘트가 아니라 job 요약에만 넣는다** — 규칙이 수십 개라 코멘트에 실으면 리뷰가 묻힌다.
RULES_TABLE=$(echo "$ISSUES_JSON" | jq -r '
  [ (.facets // [])[] | select(.property=="rules") | (.values // [])[] ]
  | sort_by(-.count) | .[:15]
  | if length == 0 then empty
    else ("| 규칙 | 건수 |", "|---|---|"), (.[] | "| `\(.val)` | \(.count) |")
    end')

# 취약점만의 규칙 분포 (ssccops#233).
#
# 위 RULES_TABLE 은 버그·취약점·코드 스멜을 **한 표에 섞어 놓는다.** 분류의 첫 단추는
# "어느 규칙이 취약점인가"인데 그것을 읽을 수 없었다 — server 의 상위가 `java:S4684` 80건일 때
# 그 80이 취약점 93건 중 80인지 코드 스멜 108건 중 80인지 표만 봐서는 갈리지 않는다.
#
# 요청을 하나 더 보내는 것은 `types` 가 facet 이 아니라 **필터**라서다. 같은 응답에서
# 두 축을 동시에 얻을 수 없다. ps=1 이라 본문은 최소이고 왕복 하나가 는다.
# **branch 는 붙이지 않는다** — 이 서버에는 브랜치 분석이 없어 붙이면 0건이 온다 (ssccops#238).
VULN_ISSUES_JSON=$(curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&types=VULNERABILITY&ps=1&facets=rules")

VULN_RULES_TABLE=$(echo "$VULN_ISSUES_JSON" | jq -r '
  [ (.facets // [])[] | select(.property=="rules") | (.values // [])[] | select(.count > 0) ]
  | sort_by(-.count) | .[:15]
  | if length == 0 then empty
    else ("| 규칙 | 건수 |", "|---|---|"), (.[] | "| `\(.val)` | \(.count) |")
    end')

# 보안 핫스팟은 세지 않는다 (ssccops#239 종결).
#
# 한때 여기서 `api/hotspots/search` 를 따로 불렀고(ssccops#233 · #295), 그 값이 오래 `?` 였다가
# 403 으로 밝혀져 "토큰에 권한을 주면 돌아온다" 로 남겨 두었다. 그런데 이 서버(Community Build
# 26.8.0)의 대시보드가 답을 먼저 했다 —
#
#     The concept of Security Hotspots is deprecated. Security Hotspot findings now appear as
#     security issues or vulnerabilities in the Issues page.
#
# **핫스팟이 이슈 모델로 합쳐졌다.** 별도 API 가 답하는 것은 옛 개념의 빈 목록이고(양쪽
# 프로젝트 모두 0), 실제 지적은 위 `api/issues/search` 의 취약점 수에 이미 들어 있다. 그러니
# 권한을 푸는 것이 아니라 **호출을 걷어내는 것**이 맞다 — 권한을 풀어 0 을 받아 봐야 "취약점
# 수가 보안 지적의 전부가 아니다" 라는 각주가 거짓이 된다.
#
# 이 줄을 리포트에서 함께 지운다. `?` 든 `세지 못했다` 든 `0` 이든, 없는 개념의 자리를 남겨
# 두면 매 실행마다 같은 질문("이건 왜 이러지")을 다시 하게 만든다.

MEASURES_JSON=$(curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/measures/component?component=$PROJECT_KEY&metricKeys=coverage,duplicated_lines_density")
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

**분석한 커밋:** \`${REF_NAME:-?}\` @ \`${GITHUB_SHA:0:7}\`

### 이슈
- 버그: ${BUGS}
- 취약점: ${VULNS}
- 코드 스멜: ${SMELLS}

### 측정값
- 커버리지: ${COVERAGE}%
- 중복도: ${DUPLICATION}%

Dashboard: ${DASHBOARD_URL}

> **이 수치는 프로젝트 기본 브랜치 기준이다** — 이 서버는 Community Build 라 브랜치를 가르지 못한다(ssccops#234). 분석은 develop push 한 곳에서만 돌므로 곧 develop 의 상태다.
>
> **보안 핫스팟은 따로 세지 않는다** — 이 서버 버전에서 핫스팟은 이슈로 합쳐져 위 취약점 수에 들어 있다 (ssccops#239).
>
> Quality Gate는 **머지를 막지 않는다** (ssccops#231). 기준을 정한 뒤에 잠근다.
EOF
)

echo "$BODY" >> "$GITHUB_STEP_SUMMARY"

# **본문도 stdout 에 찍는다.** 규칙표에 붙여 둔 이유(아래)가 총계·커버리지에도 똑같이
# 걸린다 — 기준선 숫자가 job 요약에만 있으면 Actions API 로 읽히지 않아, 사람이 브라우저를
# 열어 옮겨 적기 전에는 이슈에도 남지 않는다. ssccops#238 의 검증이 실제로 여기서 막혔다.
echo "$BODY"

# 게이트를 깨뜨린 조건 (ssccops#233 · #235). 통과했으면 표가 비어 아무것도 찍지 않는다.
if [ -n "$QG_CONDITIONS" ]; then
  {
    echo
    echo "### Quality Gate 실패 조건"
    echo
    echo "$QG_CONDITIONS"
    echo
    echo "> 게이트를 언제 잠글지는 이 목록을 보고 정한다 (ssccops#235)."
  } >> "$GITHUB_STEP_SUMMARY"

  echo "--- Quality Gate 실패 조건 ---"
  echo "$QG_CONDITIONS"
fi

# 취약점만의 규칙 분포 (ssccops#233). 취약점이 없으면 표가 비어 찍지 않는다.
if [ -n "$VULN_RULES_TABLE" ]; then
  {
    echo
    echo "### 취약점 규칙별 분포"
    echo
    echo "$VULN_RULES_TABLE"
    echo
    echo "> 아래 전체 분포와 달리 **취약점만** 센다. 합이 위의 취약점 총계와 맞지 않으면"
    echo "> 필터가 빗나간 것이다 (ssccops#237 에서 실제로 그랬다)."
  } >> "$GITHUB_STEP_SUMMARY"

  echo "--- 취약점 규칙별 분포 ---"
  echo "$VULN_RULES_TABLE"
fi

# 규칙별 분포는 job 요약에 붙인다 (ssccops#237).
if [ -n "$RULES_TABLE" ]; then
  {
    echo
    echo "### 규칙별 상위 15개"
    echo
    echo "$RULES_TABLE"
    echo
    echo "> 지적 수가 곧 문제의 가짓수는 아니다 — 같은 규칙이 여러 파일에서 걸린 것이 대부분이라,"
    echo "> 규칙으로 묶으면 판단 단위가 몇 개로 줄어든다 (ssccops#233)."
  } >> "$GITHUB_STEP_SUMMARY"

  # **stdout 에도 찍는다.** job 요약은 UI 에서만 보이고 Actions API 로는 읽히지 않는다 —
  # 로그에 없으면 사람이 브라우저를 열기 전에는 아무도(자동화 포함) 이 표를 볼 수 없다.
  echo "--- 규칙별 상위 15개 ---"
  echo "$RULES_TABLE"
fi

# Quality Gate 실패로 이 스크립트를 실패시키지 않는다 — 위 주석 참고.
if [ "$QG_STATUS" != "OK" ]; then
  echo "::warning::Quality Gate 가 $QG_STATUS 다. 지금은 막지 않는다 (ssccops#231)."
fi
