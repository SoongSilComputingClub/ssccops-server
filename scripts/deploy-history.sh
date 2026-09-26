#!/usr/bin/env bash
#
# 배포 이력 조회 (#410 · #420 · ssccops#340 · ssccops#344). 서버·웹의 deploy-history.yml 이 **메타 레포**
# orphan 브랜치 `deploy-history` 에 쌓는 {server,web}-{prod,dev}.jsonl 을 gh api 로 읽어 표로 낸다 —
# 브랜치를 체크아웃하지 않는다. 메타 레포가 private 이라 그 레포를 읽을 수 있는 gh 로그인이 필요하다.
#
#   scripts/deploy-history.sh current <server|web> [prod|dev]      # 지금 떠 있는 것 (되메움 제외)
#   scripts/deploy-history.sh list    <server|web> [prod|dev] [N]  # 최근 N건 (기본 10)
#   scripts/deploy-history.sh json    <server|web> [prod|dev]      # jsonl 원문 (jq 로 더 볼 때)
#
# 서비스 기본값은 server, 환경 기본값은 prod. 레포는 REPO=owner/name 으로 바꾼다(기본 메타 레포).
#
# Windows Git Bash 의 gh --json/jq 는 CR 을 섞는다 — 전부 tr -d '\r' 을 거친다.
#
# **`current` 는 되메운 줄(`backfilled: true`)을 건너뛴다** (#585). 되메우기는 지난 배포를 뒤늦게
# 적은 것이라 append 로 파일의 마지막에 오는데, 그것을 «지금 떠 있는 것»으로 읽으면 조회가 거짓을
# 답한다 — v0.2.16 을 되메운 직후 실제로 그랬다. `unverified` 로 가르지 않는 이유는 **확인 창 안에
# 안 떴을 뿐 실제로는 떠 있는 레코드**가 있어서다(web-prod 0.2.16). 가르는 축은 확인 여부가 아니라
# «이 줄이 배포 사건인가 되메우기인가»다. `list` 는 되메운 줄도 보이고 맨 앞에 `*` 를 붙인다 — 숨기면 그
# 배포가 담은 PR 사슬이 다시 끊긴다.
set -euo pipefail

REPO="${REPO:-SoongSilComputingClub/ssccops}"
BRANCH="deploy-history"
CMD="${1:-current}"
SERVICE="${2:-server}"
ENVIRONMENT="${3:-prod}"
LIMIT="${4:-10}"

case "$SERVICE" in server|web) ;; *) echo "서비스는 server 또는 web: $SERVICE" >&2; exit 2 ;; esac
case "$ENVIRONMENT" in prod|dev) ;; *) echo "환경은 prod 또는 dev: $ENVIRONMENT" >&2; exit 2 ;; esac
FILE="$SERVICE-$ENVIRONMENT.jsonl"

records() {
  # contents API 는 1MB 까지 base64 로 준다 — 한 줄 ~1KB 라 수백 번 배포까지는 넉넉하다
  local content
  content=$(gh api "repos/$REPO/contents/$FILE?ref=$BRANCH" --jq .content 2>/dev/null | tr -d '\r\n' || true)
  if [ -z "$content" ]; then
    echo "레코드가 없다: $REPO 의 $BRANCH 브랜치에 $FILE 이 아직 없다" >&2
    exit 1
  fi
  printf '%s' "$content" | base64 -d | tr -d '\r' | grep -v '^[[:space:]]*$'
}

# 한 줄을 표의 한 행으로. 열: 되메움(*) · version · sha · deployed_at · status · PR 수 · 이슈 · ADR
# 표시 열은 **빈 값도 여러 바이트 글자도 쓰지 않는다** — 빈 값이면 줄이 탭으로 시작해 `read` 가
# 앞쪽을 접고(탭이 IFS 공백이다) 열이 한 칸씩 밀린다. `printf` 는 바이트를 세므로 `↩`(3바이트)는
# 그 줄만 두 칸 밀린다. 그래서 `*` 와 공백 한 칸이다.
row() {
  jq -r '[
    (if .backfilled then "*" else " " end),
    .version,
    (.git_sha | .[0:7]),
    (.deployed_at // "-"),
    .status,
    (.prs | length | tostring),
    ([.prs[].issue | select(. != null) | "#\(.)"] | join(" ")),
    ([.prs[].adr_refs[]] | unique | join(" "))
  ] | @tsv'
}

print_table() {
  printf '%-2s %-10s %-8s %-21s %-11s %-4s %-30s %s\n' ' ' version sha deployed_at status PRs issues ADR
  while IFS=$'\t' read -r back version sha deployed status prs issues adr; do
    printf '%-2s %-10s %-8s %-21s %-11s %-4s %-30s %s\n' "$back" "$version" "$sha" "$deployed" "$status" "$prs" "$issues" "$adr"
  done
}

case "$CMD" in
  current)
    # 되메운 줄은 지난 배포를 뒤늦게 적은 것이라 «지금»이 아니다 (#585)
    latest=$(records | jq -c 'select(.backfilled != true)' | tail -n 1)
    if [ -z "$latest" ]; then
      echo "되메우기가 아닌 레코드가 없다 — 'list' 로 전부 본다" >&2
      exit 1
    fi
    echo "## $SERVICE · $ENVIRONMENT ($REPO@$BRANCH)"
    printf '%s\n' "$latest" | row | print_table
    echo
    echo "recorded_at: $(jq -r .recorded_at <<< "$latest") · actor: $(jq -r .actor <<< "$latest")"
    echo "run: $(jq -r .workflow_run_url <<< "$latest")"
    echo
    jq -r '.prs[] | "  #\(.number) \(.title)  ← \(.issue // "-") ⟵ \(.parent_issue // "-") \(.adr_refs | join(","))"' <<< "$latest"
    ;;
  list)
    echo "## $SERVICE · $ENVIRONMENT (최근 $LIMIT건, 최신이 위 · $REPO@$BRANCH)"
    shown=$(records | tail -n "$LIMIT" | tac)
    printf '%s\n' "$shown" | while IFS= read -r line; do printf '%s\n' "$line" | row; done | print_table
    if printf '%s\n' "$shown" | jq -e -s 'any(.backfilled == true)' >/dev/null; then
      echo
      echo "* 되메운 기록 — 지난 배포를 뒤늦게 적은 줄이다. 'current' 는 이 줄을 건너뛴다."
    fi
    ;;
  json)
    records
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
