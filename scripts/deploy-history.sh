#!/usr/bin/env bash
#
# 배포 이력 조회 (#410 · ssccops#340). deploy-history.yml 이 orphan 브랜치 `deploy-history` 에
# 쌓는 prod.jsonl / dev.jsonl 을 gh api 로 읽어 표로 낸다 — 브랜치를 체크아웃하지 않는다.
#
#   scripts/deploy-history.sh current [prod|dev]      # 지금 떠 있는 것 한 줄 + 포함 PR
#   scripts/deploy-history.sh list    [prod|dev] [N]  # 최근 N건 (기본 10)
#   scripts/deploy-history.sh json    [prod|dev]      # jsonl 원문 (jq 로 더 볼 때)
#
# 환경 기본값은 prod. 레포는 REPO=owner/name 으로 바꾼다(기본 SoongSilComputingClub/ssccops-server —
# 웹 레포의 같은 브랜치도 REPO 만 바꾸면 읽힌다).
#
# Windows Git Bash 의 gh --json/jq 는 CR 을 섞는다 — 전부 tr -d '\r' 을 거친다.
set -euo pipefail

REPO="${REPO:-SoongSilComputingClub/ssccops-server}"
BRANCH="deploy-history"
CMD="${1:-current}"
ENVIRONMENT="${2:-prod}"
LIMIT="${3:-10}"

case "$ENVIRONMENT" in prod|dev) ;; *) echo "환경은 prod 또는 dev: $ENVIRONMENT" >&2; exit 2 ;; esac

records() {
  # contents API 는 1MB 까지 base64 로 준다 — 한 줄 ~1KB 라 수백 번 배포까지는 넉넉하다
  local content
  content=$(gh api "repos/$REPO/contents/$ENVIRONMENT.jsonl?ref=$BRANCH" --jq .content 2>/dev/null | tr -d '\r\n' || true)
  if [ -z "$content" ]; then
    echo "레코드가 없다: $REPO 의 $BRANCH 브랜치에 $ENVIRONMENT.jsonl 이 아직 없다" >&2
    exit 1
  fi
  printf '%s' "$content" | base64 -d | tr -d '\r' | grep -v '^[[:space:]]*$'
}

# 한 줄을 표의 한 행으로. 열: version · sha · deployed_at · status · PR 수 · 이슈 · ADR
row() {
  jq -r '[
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
  printf '%-10s %-8s %-21s %-11s %-4s %-30s %s\n' version sha deployed_at status PRs issues ADR
  while IFS=$'\t' read -r version sha deployed status prs issues adr; do
    printf '%-10s %-8s %-21s %-11s %-4s %-30s %s\n' "$version" "$sha" "$deployed" "$status" "$prs" "$issues" "$adr"
  done
}

case "$CMD" in
  current)
    latest=$(records | tail -n 1)
    echo "## $REPO · $ENVIRONMENT"
    printf '%s\n' "$latest" | row | print_table
    echo
    echo "recorded_at: $(jq -r .recorded_at <<< "$latest") · actor: $(jq -r .actor <<< "$latest")"
    echo "run: $(jq -r .workflow_run_url <<< "$latest")"
    echo
    jq -r '.prs[] | "  #\(.number) \(.title)  ← \(.issue // "-") ⟵ \(.parent_issue // "-") \(.adr_refs | join(","))"' <<< "$latest"
    ;;
  list)
    echo "## $REPO · $ENVIRONMENT (최근 $LIMIT건, 최신이 위)"
    records | tail -n "$LIMIT" | tac | while IFS= read -r line; do printf '%s\n' "$line" | row; done | print_table
    ;;
  json)
    records
    ;;
  *)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
