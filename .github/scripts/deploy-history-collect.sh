#!/usr/bin/env bash
#
# 두 커밋 사이에 들어간 PR → Sub-task → cross-repo Parent → ADR 을 JSON 배열로 낸다
# (#410 · ssccops#340). deploy-history.yml 이 부르고, 로컬에서도 같은 인자로 돌려 볼 수 있다:
#
#   bash .github/scripts/deploy-history-collect.sh SoongSilComputingClub/ssccops-server v0.2.7 v0.2.8
#   bash .github/scripts/deploy-history-collect.sh SoongSilComputingClub/ssccops-server "" <sha>   # 이 커밋 하나만
#
# 출력 원소: {number, title, issue, parent_issue, adr_refs}
#   issue        — PR 제목 `[#N]` 의 N (이 레포의 Sub-task). 없으면 null
#   parent_issue — `owner/repo#N` (GraphQL sub_issues 의 parent, cross-repo). 못 읽으면 PR 본문 «근거»의 ssccops#N —
#                  메타 레포가 private 이라 GITHUB_TOKEN 으로는 null 이 정상이다
#   adr_refs     — Parent 본문의 ADR-NNNN 전부 (중복 제거·정렬)
#
# 커밋→PR 은 `commits/{sha}/pulls` API 다 — squash 커밋은 merge commit 이 아니라 부모가 하나라
# `git log --merges` 로는 잡히지 않는다. base 가 main 인 PR(develop→main 릴리스 PR)은 뺀다:
# 릴리스 PR 은 «무엇이 들었나»의 답이 아니라 그 묶음의 껍데기다.
#
# Windows Git Bash 의 gh·jq 는 CR 을 섞는다 — 모든 API 출력에 tr -d '\r' 을 건다.
set -euo pipefail

REPO="${1:?owner/repo}"
BASE="${2:-}"
HEAD="${3:?head sha}"

api() { gh api "$@" | tr -d '\r'; }

if [ -n "$BASE" ]; then
  SHAS=$(api --paginate "repos/$REPO/compare/$BASE...$HEAD" --jq '.commits[].sha' || true)
  # compare 가 실패하면(base 가 사라진 커밋 등) HEAD 하나로 좁힌다 — 기록을 비우는 것보다 낫다
  if [ -z "$SHAS" ]; then
    SHAS=$(api "repos/$REPO/commits/$HEAD" --jq .sha)
  fi
else
  SHAS=$(api "repos/$REPO/commits/$HEAD" --jq .sha)
fi

# sha → PR 번호 (중복 제거, 등장 순서 유지)
PR_NUMBERS=()
declare -A SEEN=()
while IFS= read -r sha; do
  [ -z "$sha" ] && continue
  numbers=$(api "repos/$REPO/commits/$sha/pulls" \
    --jq '.[] | select(.base.ref != "main") | .number' 2>/dev/null || true)
  if [ -z "$numbers" ]; then
    # API 가 비면 squash 제목 꼬리의 `(#N)` 로 — 이 레포는 squash_merge_commit_title=PR_TITLE 이다
    numbers=$(api "repos/$REPO/commits/$sha" --jq '.commit.message | split("\n")[0]' \
      | grep -oE '\(#[0-9]+\)$' | grep -oE '[0-9]+' || true)
  fi
  for n in $numbers; do
    if [ -z "${SEEN[$n]:-}" ]; then
      SEEN[$n]=1
      PR_NUMBERS+=("$n")
    fi
  done
done <<< "$SHAS"

parent_query='query($owner:String!,$name:String!,$number:Int!){
  repository(owner:$owner,name:$name){
    issue(number:$number){ parent { number body repository { nameWithOwner } } }
  }
}'

OWNER="${REPO%%/*}"
NAME="${REPO##*/}"
RESULT='[]'
for n in "${PR_NUMBERS[@]+"${PR_NUMBERS[@]}"}"; do
  title=$(api "repos/$REPO/pulls/$n" --jq .title)
  issue=$(printf '%s' "$title" | grep -oE '^\[#[0-9]+\]' | grep -oE '[0-9]+' || true)
  parent_issue=''
  adr_refs='[]'
  if [ -n "$issue" ]; then
    parent=$(gh api graphql -H 'GraphQL-Features: sub_issues' \
      -f query="$parent_query" -F owner="$OWNER" -F name="$NAME" -F number="$issue" 2>/dev/null \
      | tr -d '\r' | jq -c '.data.repository.issue.parent // empty' || true)
    if [ -n "$parent" ]; then
      parent_issue=$(jq -r '"\(.repository.nameWithOwner)#\(.number)"' <<< "$parent")
      adr_refs=$( (jq -r '.body // ""' <<< "$parent" | grep -oE 'ADR-[0-9]{4}' || true) | sort -u | jq -R . | jq -sc .)
    fi
  fi
  # Parent 를 못 읽었으면(GITHUB_TOKEN 은 다른 레포의 sub_issues 를 못 본다) PR 본문의 «근거» 줄을 쓴다 —
  # pr-guard 가 `ssccops#N` 또는 `ADR-NNNN` 을 강제하므로 여기엔 늘 무언가 있다 (#418, 웹 #444 와 같은 규칙)
  body=$(api "repos/$REPO/pulls/$n" --jq '.body // ""' 2>/dev/null || true)
  if [ -z "$parent_issue" ]; then
    bn=$(printf '%s' "$body" | grep -oE '(SoongSilComputingClub/)?ssccops#[0-9]+' | head -1 | grep -oE '[0-9]+$' || true)
    [ -n "$bn" ] && parent_issue="SoongSilComputingClub/ssccops#$bn"
  fi
  adr_refs=$( ( (jq -r '.[]' <<< "$adr_refs"; printf '%s' "$body" | grep -oE 'ADR-[0-9]{4}' || true) | sort -u | jq -R . | jq -sc .) )
  RESULT=$(jq -c \
    --argjson number "$n" --arg title "$title" --arg issue "$issue" \
    --arg parent_issue "$parent_issue" --argjson adr_refs "$adr_refs" \
    '. + [{number: $number, title: $title,
           issue: (if $issue == "" then null else ($issue | tonumber) end),
           parent_issue: (if $parent_issue == "" then null else $parent_issue end),
           adr_refs: $adr_refs}]' <<< "$RESULT")
done

printf '%s\n' "$RESULT"
