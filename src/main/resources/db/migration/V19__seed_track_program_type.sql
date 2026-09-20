-- 학술 활동 유형 '트랙'과 그 리더 역할 '트랙장' (#510).
--
-- 학술 활동 항목은 실제로 스터디·트랙·프로젝트 셋인데 기준정보에는 둘뿐이었다. 운영진이
-- 기획안 폼(PROPOSAL)의 programType 선택지에 '트랙'을 먼저 더해 둔 탓에 **접수는 되는데
-- 승인이 되지 않는** 기획안이 쌓였다 — 응답은 선택지 문자열('트랙')로 저장되는데
-- acdm_actv.acdm_actv_type_cd는 acdm_actv_type을 가리키는 FK라, 그 둘을 잇는 이름 조회
-- (ProposalResponseParser.resolveType)가 빈손이면 이관이 성립하지 않는다.
--
-- 폼 선택지와 이 테이블은 FK로 묶여 있지 않다. 한쪽만 늘릴 수 있다는 것이 «배포 없이 유형을
-- 늘린다»의 대가이며, 그 어긋남을 파서가 사유와 함께 거절하는 것으로 감수하고 있다.
--
-- V11이 세운 규칙 그대로다 — 시드를 더할 때도 새 파일, 멱등 가드(WHERE NOT EXISTS) 유지,
-- test 프로필은 Flyway가 꺼져 있어 application-test.yaml의 data-locations와
-- SeedScript.LOCATIONS에 이 파일을 함께 적는다.

-- indct_seqno는 3이다. 2를 주고 프로젝트를 3으로 미는 편이 '스터디·트랙·프로젝트'라는 원래
-- 어휘 순서에 맞지만, 그러려면 이미 시드된 행을 UPDATE로 덮어야 한다 — 이 파일들의 멱등
-- 가드가 지키는 것이 «운영진이 화면에서 고친 값을 배포가 되돌리지 않는다»이므로 그 길을
-- 택하지 않는다. 표시 순번은 유형 관리 화면(PATCH /v1/academic-program-types/{typeCd})에서
-- 언제든 바꿀 수 있는 값이고, 바뀐 뒤에는 이 배포가 그것을 되돌리지 않는 쪽이 맞다.
INSERT INTO acdm_actv_type (acdm_actv_type_cd, type_nm, indct_seqno, use_yn, crt_dt, mdfcn_dt)
SELECT 'TRACK', '트랙', 3, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM acdm_actv_type WHERE acdm_actv_type_cd = 'TRACK');

-- 리더 역할. 승인 후속 처리(AcademicProgramApprovalEffectsServiceImpl)가 유형 코드로 역할명을
-- 찾아 스터디장/프로젝트장을 부여하는데, 그 맵에 없는 유형은 IllegalStateException → 500이다.
-- 그래서 이 행과 그 맵의 한 줄은 **함께** 가야 한다 — 유형만 넣으면 400이 500으로 바뀔 뿐
-- 승인은 여전히 막힌다.
--
-- role_id는 지정하지 않는다. IDENTITY 컬럼에 값을 박아 넣으면 시퀀스가 그대로 머물러, 역할
-- 관리 화면이 역할을 추가하는 순간 PK가 충돌한다(V3의 role 시드와 같은 이유). 멱등 판정은
-- 역할명으로 한다.
--
-- indct_seqno 8은 POSITION 분류 안의 표시 순번이다(회장1 … 프로젝트장6 · 스터디장7). **서열이
-- 아니다** — 분류마다 1부터 다시 시작하므로 분류를 가르지 않고 비교하면 안 된다(V3 주석 · #9).
INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 8, '트랙장', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '트랙장');

-- 권한(role_authrt_rel)은 주지 않는다. V3에서 스터디장·프로젝트장도 권한이 없고 — "권한 없는
-- 역할은 아무것도 못 한다"가 기본값이다 — 리더가 자기 활동을 다루는 자격은 역할이 아니라
-- acdm_actv.leadr_mbr_id 비교(AcademicProgramOwnershipPolicy)에서 나온다. 여기에 권한을 붙이면
-- '트랙장이라는 사실'이 남의 활동을 여는 열쇠가 된다.
