-- 콘텐츠 관리 권한 (ssccops#381 · Epic ssccops#378 · ADR-0038).
--
-- 콘텐츠 컨트롤러(ContentPageController · ContentPostController)의 클래스 레벨 @RequireAuthority가
-- 가리킬 값이다 — 페이지·포스트의 생성·수정·게시·게시 취소·갤러리·이력 전부 한 권한이다.
-- 조회/쓰기/게시로 쪼개지 않은 것은 행사(EVENT_MANAGE · D8)와 같은 판단이다: 홍보국 몇 명이
-- 쓰는 기능이라 쪼갤 자식이 없고, 쪼개 두면 «쓸 수는 있는데 게시는 못 하는» 역할이 화면
-- 조작 한 번으로 생긴다.
--
-- V11이 세운 규칙 그대로다 — 시드를 더할 때도 새 파일, 멱등 가드(WHERE NOT EXISTS) 유지,
-- test 프로필은 Flyway가 꺼져 있어 application-test.yaml의 data-locations와 SeedScript.LOCATIONS에
-- 이 파일을 함께 적는다(빠뜨리면 AuthorityFixture가 «data.sql이 넣어야 할 권한이 없다»로 죽는다).

-- SUPER 직속이다 (형제 표시 순번은 각 부모 아래에서 1부터):
--
--   SUPER 최고 관리자
--   ├── EXECUTIVE 임원 (1)
--   ├── SUB_WORK_TYPE_MANAGE 하위 업무 유형 관리 (2)
--   ├── APPROVAL 결재·투표 (3)
--   ├── RAG_DOCUMENT_MANAGE 규정 문서 관리 (4)
--   └── CONTENT_MANAGE 콘텐츠 관리 (5)   ← 이 파일
--
-- EXECUTIVE의 자식으로 두지 않은 이유는 V11과 같다 — 펼침이 위→아래 한 방향이라 EXECUTIVE
-- 보유 역할에서 이 권한만 떼어내려면 트리를 옮기는 마이그레이션이 필요해진다. SUPER 아래에
-- 매다는 것은 빠뜨릴 수 없다(AuthorityPolicy에 SUPER 특별 취급 분기가 없다, #71).
INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'CONTENT_MANAGE', '콘텐츠 관리', 'SUPER', '공개 사이트 페이지·포스트의 작성·수정·게시·게시 취소·갤러리·개정 이력.', TRUE, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'CONTENT_MANAGE');

-- 회장·부회장에 명시 부여로 시작한다. 실제 주인은 홍보국인데 **시드에는 홍보국장·홍보국원
-- 역할이 없다**(V3의 POSITION 역할은 회장·부회장·총무·국장·국원·프로젝트장·스터디장이고
-- 부서별 국장은 역할별 권한 화면에서 만든다 — V3 주석). 없는 역할에 부여할 수 없으므로
-- 배포 뒤 운영진이 홍보국 역할을 만들고 역할별 권한 화면에서 이 권한을 켠다(#65 — 넓히고
-- 좁히는 것은 배포가 아니라 화면 조작이다). '국장'·'국원' 전체에 붙이지 않은 것은 그러면
-- 모든 부서가 공개 사이트를 고칠 수 있게 되기 때문이다. 총무를 뺀 것은 V11과 다른 점인데,
-- 회칙 코퍼스와 달리 공개 사이트 문안은 총무의 일이 아니다.
INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'CONTENT_MANAGE', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm IN ('회장', '부회장')
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'CONTENT_MANAGE');
