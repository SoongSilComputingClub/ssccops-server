-- 규정 도우미 코퍼스 관리 권한 (#402 · Epic ssccops#321 · ADR-0029).
--
-- 코퍼스 컨트롤러의 클래스 레벨 @RequireAuthority가 가리킬 값이며(업로드·재색인·삭제·적용
-- 전환·목록), 질의는 인증만 요구한다. 코퍼스를 바꾸는 것은 모든 답변의 근거를 갈아치우는
-- 조작이고 프롬프트 인젝션 완화의 첫째 층이다.
--
-- V3__seed_reference_data.sql을 고치지 않고 새 파일로 더하는 것은 이미 적용된 마이그레이션이
-- 체크섬으로 검증되기 때문이다(ssccops#213). 시드를 더할 때도 새 파일이라는 규칙이 여기서
-- 처음 쓰인다 — 매 기동 반영되던 편의는 없지만 무엇이 언제 들어갔는지가 남는다.
--
-- 멱등 가드(WHERE NOT EXISTS)는 V3와 같은 이유로 유지한다. 버전 마이그레이션이라 한 번만
-- 돌지만 test 프로필은 Flyway가 꺼져 있어 spring.sql.init이 같은 파일을 매 컨텍스트마다
-- 실행하고, 무엇보다 운영진이 화면에서 고친 값을 배포가 되돌리지 않는다는 뜻이 함께 산다.

-- SUPER 직속이다. 트리는 이렇게 된다(형제 표시 순번은 각 부모 아래에서 1부터):
--
--   SUPER 최고 관리자
--   ├── EXECUTIVE 임원 (1)
--   ├── SUB_WORK_TYPE_MANAGE 하위 업무 유형 관리 (2)
--   ├── APPROVAL 결재·투표 (3)
--   └── RAG_DOCUMENT_MANAGE 규정 문서 관리 (4)   ← 이 파일
--
-- EXECUTIVE의 자식으로 두지 않은 것은 그러면 **좁히는 쪽이 화면 조작이 아니라 배포**가 되기
-- 때문이다: 펼침은 위→아래 한 방향이라 EXECUTIVE 보유 역할에서 이 권한만 떼어낼 방법이 트리를
-- 옮기는 마이그레이션뿐이다(#101이 SUB_WORK_TYPE_MANAGE를 총무에게서 떼려고 치른 값이 그것이다).
-- SUPER 아래에 매다는 것 자체는 빠뜨릴 수 없다 — AuthorityPolicy에 SUPER 특별 취급 분기가
-- 없으므로(#71) 트리에 매달지 않으면 최고관리자가 이 권한을 포함하지 못한다.
--
-- sys_yn = TRUE는 코드(AuthorityCode)가 직접 가리키는 권한이라는 표시다(BR-M24) — 삭제·코드
-- 변경을 막아야 화면 조작 한 번으로 인가가 무력화되지 않는다.
INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'RAG_DOCUMENT_MANAGE', '규정 문서 관리', 'SUPER', '규정 문서의 업로드·재색인·삭제·적용 전환·목록. 도우미 질의는 이 권한 없이 인증만으로 한다.', TRUE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'RAG_DOCUMENT_MANAGE');

-- 회장·부회장·총무에 명시 부여로 시작한다 — EXECUTIVE를 가진 역할들이다. 최고관리자는
-- SUPER의 자손 펼침으로 이미 포함하므로 여기 이름이 없고, 국장(OPERATOR)·국원은 부여하지
-- 않는다. 목업의 «학술국장»은 그 자체로 권한이 아니다 — 넓히고 좁히는 것은 배포가 아니라
-- 역할별 권한 화면의 조작이다(#65).
--
-- role_id를 값으로 적지 않고 역할명으로 조회해 넣는 것도, 멱등 판정을 (role_id, authrt_cd)
-- 쌍으로 하는 것도 V3와 같은 이유다 — role_id는 IDENTITY라 환경마다 값이 다르다.
INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'RAG_DOCUMENT_MANAGE', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm IN ('회장', '부회장', '총무')
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'RAG_DOCUMENT_MANAGE');
