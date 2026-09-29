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
#   parent_issue — `owner/repo#N` (GraphQL sub_issues 의 parent, cross-repo). 못 읽으면 PR 본문 «근거»의 ssccops#N.
#                  메타 레포가 private 이라 GITHUB_TOKEN 으로는 null 이다 — 워크플로는 sscc-devops App 토큰(메타
#                  issues:read)을 GH_TOKEN 으로 넘겨 직접 읽는다(#420). 로컬에서는 gh 로그인 계정이 읽는다
#   adr_refs     — Parent 본문의 ADR-NNNN 전부 (중복 제거·정렬)
#
# 커밋→PR 은 `commits/{sha}/pulls` API 다 — squash 커밋은 merge commit 이 아니라 부모가 하나라
# `git log --merges` 로는 잡히지 않는다. base 가 main 인 PR(develop→main 릴리스 PR)은 뺀다:
# 릴리스 PR 은 «무엇이 들었나»의 답이 아니라 그 묶음의 껍데기다.
#
# Windows Git Bash 의 gh·jq 는 CR 을 섞는다 — 모든 API 출력에 tr -d '\r' 을 건다.
#
# ══ 한 번의 API 실패가 레코드를 통째로 날리지 않게 (#564 · ssccops#486) ══
#
# v0.2.16 서버 이력이 그렇게 사라졌다 — `gh` 가 JSON 을 기대한 자리에서
# `invalid character 'U' looking for beginning of value` 로 죽었고, `set -e` 가 스크립트를
# 끝내자 워크플로의 **레코드를 쓰는 단계까지 가지 못했다.** 릴리스는 정상으로 보였고 이력에서만
# 사라졌다. 늘 나는 고장이 아니라 그 한 번의 호출이 실패한 것이다.
#
# 그래서 `api()` 는 **실패하지 않는다** — 한 번 다시 부르고, 그래도 안 되면 경고를 남기고 빈
# 값을 돌려준다. 부르는 쪽은 전부 «값이 없으면 건너뛴다»로 이미 쓰여 있다. 얻는 것은 «PR 목록이
# 덜 찬 레코드»이고 잃는 것은 «레코드가 없는 배포»인데, 뒤엣것이 훨씬 나쁘다 — 목록이 비면
# 보이지만 레코드가 없으면 그 배포를 아무도 찾지 않는다(`unverified` 를 둔 것과 같은 태도다).
set -euo pipefail

REPO="${1:?owner/repo}"
BASE="${2:-}"
HEAD="${3:?head sha}"

api() {
  local out='' attempt errfile
  # **stdout 과 stderr 를 섞지 않는다** — `2>&1` 로 합치면 gh 가 성공하면서 낸 경고(폐기 예정
  # 알림 등)가 JSON 앞에 붙어 `jq` 가 그것을 먼저 만난다. 실패했을 때만 메시지가 필요하다.
  errfile=$(mktemp)
  for attempt in 1 2; do
    if out=$(gh api "$@" 2>"$errfile"); then
      rm -f "$errfile"
      printf '%s' "$out" | tr -d '\r'
      return 0
    fi
    # 일시 오류(5xx·레이트 리밋·JSON 아닌 응답)는 한 번 더 부르면 대부분 산다
    sleep $((attempt * 3))
  done
  # 마지막 실패 메시지를 그대로 남긴다 — 다음에 같은 자리가 깨지면 이 줄이 유일한 단서다
  printf '::warning::gh api 실패, 빈 값으로 지나간다 — args=[%s] 마지막 응답=%s\n' \
    "$*" "$(tr -d '\r' < "$errfile" | tr '\n' ' ' | head -c 200)" >&2
  rm -f "$errfile"
  return 0
}

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
  # `tr -d '\r'` 가 `sort -u` **앞**에 있어야 한다 (#564) — Windows Git Bash 의 jq 는 CRLF 로
  # 쓰므로 위 `jq -r '.[]'` 의 `ADR-0038\r` 과 아래 grep 의 `ADR-0038` 이 다른 줄이 되어
  # 중복이 그대로 남았다(실측: `["ADR-0038","ADR-0038"]`). 파일 머리말이 경고해 둔 그 함정인데
  # 이 줄만 빠져 있었다. 리눅스 러너에서는 드러나지 않아 **로컬로 돌려 봐야 보인다.**
  adr_refs=$( ( (jq -r '.[]' <<< "$adr_refs"; printf '%s' "$body" | grep -oE 'ADR-[0-9]{4}' || true) | tr -d '\r' | sort -u | jq -R . | jq -sc .) )
  RESULT=$(jq -c \
    --argjson number "$n" --arg title "$title" --arg issue "$issue" \
    --arg parent_issue "$parent_issue" --argjson adr_refs "$adr_refs" \
    '. + [{number: $number, title: $title,
           issue: (if $issue == "" then null else ($issue | tonumber) end),
           parent_issue: (if $parent_issue == "" then null else $parent_issue end),
           adr_refs: $adr_refs}]' <<< "$RESULT")
done

printf '%s\n' "$RESULT"
