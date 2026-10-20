-- 학술 프로그램 조회 권한 (#694 · ssccops#614 · ADR-0068).
--
-- 국원이 어드민 학술 화면 전부를 **읽기만** 하게 하는 권한이다. 그전에는 학술 조회가 쓰기 권한
-- ACADEMIC_PROGRAM_MANAGE와 한 몸이라, 국원에게 «보게만» 할 길이 없었다(주면 쓰기까지 열린다).
-- 판정은 AuthorityCode.ACADEMIC_PROGRAM_READ 주석과 AcademicProgramOwnershipPolicy에 있다.
--
-- V11이 세운 규칙 그대로다 — 시드를 더할 때도 새 파일, 멱등 가드(WHERE NOT EXISTS) 유지,
-- test 프로필은 Flyway가 꺼져 있어 application-test.yaml의 data-locations와 SeedScript.LOCATIONS에
-- 이 파일을 함께 적는다(빠뜨리면 AuthorityFixture가 «data.sql이 넣어야 할 권한이 없다»로 죽는다).
-- 이름은 데이터사전 표준코드(ssccops#615)와 같다.

-- ACADEMIC_PROGRAM_MANAGE의 자식이다 (형제 표시 순번은 각 부모 아래에서 1부터):
--
--   OPERATOR 운영자
--   └── ACADEMIC_PROGRAM_MANAGE 학술 활동 관리
--       └── ACADEMIC_PROGRAM_READ 학술 프로그램 조회 (1)   ← 이 파일
--
-- WORK_READ(#101 · V3)와 같은 자리다. MANAGE만 직접 받은 역할도 읽을 수 있어야 하는데, OPERATOR 밑
-- 형제로 두면 그 경로에서 조회가 막힌다. 학술국장(국장 → OPERATOR)은 펼침으로 자동 보유한다.
INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'ACADEMIC_PROGRAM_READ', '학술 프로그램 조회', 'ACADEMIC_PROGRAM_MANAGE', '학술 프로그램의 목록·상세·회차·출석·명단·신청자·모집·승인 이력과 기획안 응답 조회.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'ACADEMIC_PROGRAM_READ');

-- 국원에 명시 부여한다(ssccops#614 · 사용자 결정 2026-10-19). 시드의 «국원»은 부서를 가르지 않는
-- 역할 하나라 학술국 밖의 국원도 읽는다 — V16이 CONTENT_MANAGE를 국원 전체에 주지 않은 것과 반대
-- 판단인데, 그쪽은 공개 사이트를 고치는 쓰기이고 이쪽은 읽기다. 좁히는 것은 배포가 아니라 역할별
-- 권한 화면의 조작이다.
--
-- 리더 역할(스터디장·프로젝트장·트랙장)에는 붙이지 않는다 — LeaderRoleGuard가 화면·API의 부여를
-- 막고(#687), 리더는 소유권(acdm_actv.leadr_mbr_id)으로 자기 프로그램만 본다.
--
-- 역할은 이름으로 찾는다(role_id는 IDENTITY라 환경마다 다르다 — V3 주석). 같은 이름의 역할이 여럿이면
-- 각각에 붙는데, 그것이 V3와 같은 의도한 동작이다.
INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'ACADEMIC_PROGRAM_READ', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm = '국원'
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'ACADEMIC_PROGRAM_READ');
