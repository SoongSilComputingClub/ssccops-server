---
name: create-pr
description: Create a GitHub PR for the current branch following this repo's CONTRIBUTING.md convention ("[#이슈번호] 총 작업 내용" title, develop as default base, squash-merge only). Use whenever the user asks to open or create a PR in this repo.
---

# Create PR Skill

현재 브랜치명을 기준으로 이 저장소의 PR 컨벤션에 맞는 PR을 생성한다.

## 절차

1. `git branch --show-current`로 현재 브랜치명을 확인한다. `main`이나 `develop` 위에 있으면 작업 브랜치가 아니므로 사용자에게 알리고 중단한다.
2. 브랜치명에서 이슈 번호를 추출한다 — 형식은 `{type}/#{이슈번호}-{슬러그}` (예: `feat/#23-member-detail` → `23`). 패턴에 안 맞으면 이슈 번호 없이 진행할지 사용자에게 확인한다.
3. 로컬 커밋이 원격에 푸시돼 있는지 확인한다 (`git status`, 필요하면 `git log @{u}..HEAD`). 안 돼 있으면 푸시해도 되는지 사용자에게 확인 후 `git push -u origin {현재 브랜치명}`.
4. 이슈 번호가 있으면 `gh issue view {번호}`로 제목을 확인해 PR 제목에 참고한다. 이슈 제목 앞의 태그(`[Feat]` 등)는 제거하고 자연스러운 설명으로 다듬는다.
5. PR 제목을 `[#{이슈번호}] {총 작업 내용}` 형식으로 만든다 (이슈 번호가 없으면 대괄호 없이 설명만 사용).
6. 기본 대상 브랜치는 `develop`이다 (`main`은 운영 배포 브랜치, `develop`이 기본 PR 대상). `hotfix/` 등 예외적으로 `main`을 대상으로 해야 하는 경우가 아니면 `develop`을 사용하고, 예외가 의심되면 사용자에게 확인한다.
7. `.github/PULL_REQUEST_TEMPLATE.md`가 있으면 그 구조를 최대한 채워서 본문을 작성한다. `git log {base}..HEAD --oneline`으로 커밋 로그를 확인해 변경 사항을 요약한다.
8. `gh pr create --title "..." --base develop --body "..."` (또는 파악한 base 브랜치)로 생성하기 전에, 제목·본문·대상 브랜치를 사용자에게 보여주고 확인받는다 — PR 생성은 외부에 공개되는 행동이므로 반드시 승인 후 실행한다.
9. 생성 후 PR URL을 사용자에게 알려준다.

## 참고

- 리뷰어는 `pr-reviewer.yml`이 자동 배정하고 승인 체크는 `pr-approval-check.yml`이 자동 처리하므로, 이 스킬에서 리뷰어를 수동으로 지정하지 않는다.
- 머지 방식은 Squash and merge만 사용한다 — PR 생성 시 머지 방식을 별도로 설정하지 않는다 (Squash 시 PR 제목이 그대로 커밋 제목이 되므로 5번 형식을 정확히 지키는 것이 중요하다).
