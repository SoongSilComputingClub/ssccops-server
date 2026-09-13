#!/usr/bin/env bash
#
# «정말 그 커밋이 떠 있는가»를 /actuator/info 로 확인한다 (#410 · ssccops#340).
#
#   bash .github/scripts/deploy-history-verify.sh <base-url> <sha> [timeout-seconds]
#
# git.commit.id.full(없으면 abbrev 접두사)이 sha 와 같아질 때까지 20초 간격으로 폴링한다. 배포는
# Coolify 가 develop 푸시를 받아 이미지를 빌드하고 컨테이너를 바꾸는 데 몇 분이 걸리므로 첫
# 응답이 옛 sha 인 것이 정상이다. 시간 안에 같아지지 않으면 status=unverified 로 **성공 종료**한다 —
# 이 스크립트가 실패하면 레코드 자체가 안 남고, «확인 못 함»도 기록할 가치가 있는 사실이다.
#
# 출력(GITHUB_OUTPUT 이 있으면 거기에, 없으면 stdout):
#   status=deployed|unverified · deployed_at=<UTC ISO, 확인한 시각> · build_time=<build-info 의 time>
#
# base-url 이 비어 있으면(repo variable 미설정) 폴링 없이 unverified 다.
set -uo pipefail

BASE_URL="${1:-}"
SHA="${2:?sha}"
TIMEOUT="${3:-600}"
INTERVAL=20

emit() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s\n' "$@" >> "$GITHUB_OUTPUT"
  fi
  printf '%s\n' "$@"
}

if [ -z "$BASE_URL" ]; then
  echo "배포 주소가 없어 확인을 건너뛴다 (unverified)"
  emit "status=unverified" "deployed_at=" "build_time="
  exit 0
fi

deadline=$((SECONDS + TIMEOUT))
last=''
while [ "$SECONDS" -lt "$deadline" ]; do
  info=$(curl -sf --max-time 10 "$BASE_URL/actuator/info" 2>/dev/null | tr -d '\r' || true)
  if [ -n "$info" ]; then
    # info.git.mode=full 이면 commit.id 가 {abbrev, full} 객체, simple 이면 abbrev 문자열이다
    live=$(jq -r '(.git.commit.id | if type == "object" then (.full // .abbrev) else . end) // empty' <<< "$info" 2>/dev/null || true)
    if [ -n "$live" ] && { [ "$live" = "$SHA" ] || [ "${SHA#"$live"}" != "$SHA" ]; }; then
      build_time=$(jq -r '.build.time // empty' <<< "$info")
      now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
      echo "확인: $BASE_URL 이 $live 를 돌려준다 ($now)"
      emit "status=deployed" "deployed_at=$now" "build_time=$build_time"
      exit 0
    fi
    [ "$live" != "$last" ] && echo "아직 $live (기다리는 것: $SHA)"
    last="$live"
  else
    echo "응답 없음 — 재시작 중이거나 주소가 틀렸다"
  fi
  sleep "$INTERVAL"
done

echo "::warning::${TIMEOUT}초 안에 $SHA 가 뜨지 않았다 — unverified 로 남긴다 (마지막 응답: ${last:-없음})"
emit "status=unverified" "deployed_at=" "build_time="
