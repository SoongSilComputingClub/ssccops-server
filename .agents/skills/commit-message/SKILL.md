---
name: commit-message
description: Draft and create a git commit message following this repo's CONTRIBUTING.md convention (issue-numbered "type(scope): 설명" format). Use whenever the user asks to commit changes or write a commit message in this repo.
---

# Commit Message Skill

이 저장소의 커밋 컨벤션(로컬 전용 `.private-workspace/CONTRIBUTING.md` 2절 — git에는 포함되지 않으므로 원격 클론에는 없을 수 있음)에 맞춰 커밋 메시지를 작성하고 커밋을 생성한다.

## 절차

1. `git status`와 `git diff --staged`(스테이징된 게 없으면 `git diff`)로 변경 내용을 파악한다. 스테이징된 파일이 없으면 어떤 파일을 커밋할지 사용자에게 확인한다 — 임의로 `git add -A`를 하지 않는다 (의도치 않은 파일이 섞일 위험).
2. `git branch --show-current`로 현재 브랜치명을 확인한다.
   - 브랜치가 `{type}/#{이슈번호}-{슬러그}` 형식이면 이슈 번호를 추출한다.
   - `main`/`develop`이거나 패턴이 안 맞으면 이슈 번호 없이 진행한다 (사용자가 이슈 번호를 직접 알려주면 그것을 우선한다).
3. 변경 내용에 맞는 커밋 타입을 고른다:
   - `feat` 새 기능 추가
   - `fix` 버그 수정
   - `refactor` 코드 리팩토링
   - `design` UI 디자인 변경
   - `style` 코드 포맷팅 (로직 변경 없음)
   - `docs` 문서 수정
   - `test` 테스트 코드 (로직 변경 없음)
   - `chore` 기타 (빌드, assets, 패키지 등)
   - `init` 초기 생성
   - `rename` 파일/폴더명 수정 또는 이동
   - `remove` 파일 삭제
   - `cicd` CI/CD 관련
   - `hotfix` 긴급 수정

   브랜치 타입(feat/fix/refactor)과 커밋 타입이 항상 같을 필요는 없다 — 실제 diff 성격에 맞춘다.
4. scope는 변경된 도메인/모듈명(예: `member`, `auth`, `common`)으로 짧게 잡는다. 여러 모듈에 걸친 전역적인 변경이면 생략 가능.
5. 메시지를 조립한다:
   - 이슈 번호가 있으면: `#{이슈번호} {type}({scope}): 설명`
   - 없으면: `{type}({scope}): 설명`
   - 설명은 한국어로 간결하게, 한 커밋에는 한 가지 문제만 담는다 — diff가 여러 관심사를 섞고 있으면 나누어 커밋할지 먼저 사용자에게 확인한다.
6. 작성한 메시지를 사용자에게 보여주고 커밋 실행 전에 확인받는다.
7. 승인되면 커밋한다 (본문이 여러 줄이면 heredoc으로 `git commit -F -` 또는 `-m` 다중 지정). 여러 사람의 작업을 모아 커밋하는 경우 등 필요하면 `Co-authored-by:` 트레일러를 추가한다.

## 참고

- 타입 표기는 `.github/workflows/pr-labeler.yml`이 그대로 파싱해 PR 라벨을 붙이므로, 위 12개 타입 밖의 단어를 쓰지 않는다.
- 커밋 메시지의 `#{이슈번호}`는 GitHub이 자동으로 링크해주지 않는다(PR 제목에서만 자동 링크됨) — 이슈를 닫아야 하면 별도로 `refs #{번호}` 또는 PR 본문에서 `closes #{번호}`를 명시한다.
