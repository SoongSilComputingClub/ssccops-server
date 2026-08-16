-- 기준 코드·기준 데이터 시드.
-- H2/Postgres 양쪽에서 동일하게 동작하도록 ON CONFLICT 대신 WHERE NOT EXISTS로 멱등성을 보장한다.
-- spring.sql.init.mode=always라 매 기동마다 실행되므로, 이미 시드된 DB에서 두 번째 실행이
-- 아무것도 바꾸지 않아야 한다. 값을 고칠 때도 UPDATE로 덮어쓰지 말 것 — 화면에서 손댄 값을 되돌린다.

-- 회원 등급(mbr_grd). 코드값·명칭은 웹이 이미 화면에 쓰고 있는 어휘(shared/config/codes.ts MBR_GRD_NM)와
-- 글자 하나까지 맞춘다. 어긋나면 예외가 아니라 빈 라벨로 떨어져 조용히 깨진다.
-- 회원관리 화면정의서 9.2의 '비회원'은 넣지 않는다 — 인증 시점에 회원을 만들지 않기로 했으므로(#20)
-- 가입 전 상태는 등급이 아니라 mbr 행의 부재로 표현된다.
-- 표시순번은 회칙상 승급 순서(임시 → 준 → 활동 → 정)를 그대로 쓴다.
INSERT INTO mbr_grd (mbr_grd_cd, mbr_grd_nm, indct_seqno)
SELECT 'TEMP', '임시회원', 1
WHERE NOT EXISTS (SELECT 1 FROM mbr_grd WHERE mbr_grd_cd = 'TEMP');

INSERT INTO mbr_grd (mbr_grd_cd, mbr_grd_nm, indct_seqno)
SELECT 'ASSOC', '준회원', 2
WHERE NOT EXISTS (SELECT 1 FROM mbr_grd WHERE mbr_grd_cd = 'ASSOC');

INSERT INTO mbr_grd (mbr_grd_cd, mbr_grd_nm, indct_seqno)
SELECT 'ACTIVE', '활동회원', 3
WHERE NOT EXISTS (SELECT 1 FROM mbr_grd WHERE mbr_grd_cd = 'ACTIVE');

INSERT INTO mbr_grd (mbr_grd_cd, mbr_grd_nm, indct_seqno)
SELECT 'FULL', '정회원', 4
WHERE NOT EXISTS (SELECT 1 FROM mbr_grd WHERE mbr_grd_cd = 'FULL');

-- 회원 상태(mbr_stts). 웹 MBR_STTS_NM과 동일한 어휘·순서.
-- 표시순번은 학적 흐름(재학 → 휴학 → 졸업) 뒤에 자격 상실(탈퇴 → 제명)을 붙인 순서다.
-- GRADUATED가 없어 졸업 회원 가입이 막혀 있었고(#21), WITHDRAWN/EXPELLED가 없어
-- 담당자 배정에서 자격 상실 회원을 걸러낼 기준이 없었다.
INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'ENROLLED', '재학', 1
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'ENROLLED');

INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'LEAVE', '일반휴학', 2
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'LEAVE');

INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'MIL_LEAVE', '군휴학', 3
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'MIL_LEAVE');

INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'GRADUATED', '졸업', 4
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'GRADUATED');

INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'WITHDRAWN', '탈퇴', 5
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'WITHDRAWN');

INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'EXPELLED', '제명', 6
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'EXPELLED');

-- 역할 분류(role_clsf). 등급·상태와 달리 화면(/members/role-labels)에서 추가·수정·삭제하는
-- 사용자 관리 코드테이블이라, 여기 넣는 것은 고정 어휘가 아니라 초기값이다.
-- 그래서 서버 코드에 enum으로 굳히지 않고 시드로만 둔다.
INSERT INTO role_clsf (role_clsf_cd, role_clsf_nm, indct_seqno)
SELECT 'POSITION', '직책', 1
WHERE NOT EXISTS (SELECT 1 FROM role_clsf WHERE role_clsf_cd = 'POSITION');

INSERT INTO role_clsf (role_clsf_cd, role_clsf_nm, indct_seqno)
SELECT 'DEPT', '부서', 2
WHERE NOT EXISTS (SELECT 1 FROM role_clsf WHERE role_clsf_cd = 'DEPT');

INSERT INTO role_clsf (role_clsf_cd, role_clsf_nm, indct_seqno)
SELECT 'PROJECT', '프로젝트', 3
WHERE NOT EXISTS (SELECT 1 FROM role_clsf WHERE role_clsf_cd = 'PROJECT');

INSERT INTO role_clsf (role_clsf_cd, role_clsf_nm, indct_seqno)
SELECT 'STUDY', '스터디', 4
WHERE NOT EXISTS (SELECT 1 FROM role_clsf WHERE role_clsf_cd = 'STUDY');

INSERT INTO role_clsf (role_clsf_cd, role_clsf_nm, indct_seqno)
SELECT 'EVENT', '행사', 5
WHERE NOT EXISTS (SELECT 1 FROM role_clsf WHERE role_clsf_cd = 'EVENT');

-- 시스템 분류는 조직이 만든 자리가 아니라 시스템이 쓰는 역할을 담는 칸이다(#71).
-- '최고관리자'를 POSITION에 넣으면 역할 목록에서 회장·부회장 옆에 직책인 것처럼 서게 된다.
INSERT INTO role_clsf (role_clsf_cd, role_clsf_nm, indct_seqno)
SELECT 'SYSTEM', '시스템', 6
WHERE NOT EXISTS (SELECT 1 FROM role_clsf WHERE role_clsf_cd = 'SYSTEM');

-- 조직 역할(role) 마스터.
--
-- indct_seqno는 화면 정렬 순번이다. **서열로 쓰지 않는다** — 분류(role_clsf)마다 1번부터
-- 다시 시작하므로 분류를 가르지 않고 비교하면 '프로젝트장(PROJECT 1)'이 '국장(POSITION 4)'보다
-- 높게 계산된다(#9 · VR-M11). 인가는 서열이 아니라 역할에 부여된 권한(role_authrt_rel)으로 한다.
--
-- role_id는 지정하지 않는다. IDENTITY 컬럼에 값을 박아 넣으면 시퀀스가 그대로 1에 머물러,
-- 나중에 역할 관리 화면이 역할을 추가하는 순간 PK가 충돌한다. 멱등 판정은 역할명으로 한다.
INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 1, '회장', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '회장');

INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 2, '부회장', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '부회장');

INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 3, '총무', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '총무');

INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 4, '국장', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '국장');

INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 5, '국원', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '국원');

INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 6, '프로젝트장', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '프로젝트장');

INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 7, '스터디장', 'POSITION'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '스터디장');

-- 최초 가입자에게 배정되는 역할(#71). 이 역할만이 SUPER 권한을 갖는다.
-- indct_seqno는 분류마다 1부터 다시 시작하므로 SYSTEM 안에서 1이다 — POSITION의 '회장'과
-- 값이 같지만 서열이 아니라 분류 안의 표시 순번이라 비교 대상이 아니다.
--
-- 이 역할을 특별히 잠그지 않는다. 부여·회수는 역할 관리 화면에서 하는 평범한 조작이며,
-- 그것이 최초 가입자가 최고관리자를 다른 사람에게 넘겨주는 정상 경로다.
INSERT INTO role (indct_seqno, role_nm, role_clsf_cd)
SELECT 1, '최고관리자', 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE role_nm = '최고관리자');

-- 권한(authrt) 트리. 코드가 @RequireAuthority로 직접 가리키는 값이라 전부 sys_yn = TRUE다 (#9).
--
-- 단일 부모 트리이며 부여는 위→아래 한 방향으로만 펼쳐진다. 상위를 부여하면 자손 전부를
-- 부여한 것이지만, 자손을 가졌다고 상위가 생기지는 않는다.
--
--   SUPER 최고 관리자
--   └── EXECUTIVE 임원
--       ├── OPERATOR 운영자
--       │   ├── WORK_MANAGE · SUB_WORK_TYPE_READ · RESPONSE_REVIEW · MEETING_MANAGE
--       │   └── FORM_MANAGE ├── FORM_READ · FORM_WRITE · FORM_STATUS_CHANGE
--       ├── SUB_WORK_TYPE_MANAGE · FORM_LABEL_MANAGE · MEMBER_MANAGE · ROLE_MANAGE
--
-- indct_seqno는 형제들 사이의 표시 순번이다(각 부모 아래에서 1부터). 서열이 아니다 —
-- 인가는 순번이 아니라 트리의 부모-자식 관계로만 판정한다.
--
-- 상위(up_authrt_cd)가 FK라 부모를 먼저 넣어야 한다. 감사 컬럼(crt_dt·mdfcn_dt)은 이 파일이
-- JPA를 거치지 않는 순수 SQL이라 직접 적는다 — 빠뜨리면 NOT NULL 위반으로 기동이 깨진다.
INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'SUPER', '최고 관리자', NULL, '시스템의 모든 권한. 회원이 한 명도 없을 때 최초 가입자에게 부여된다.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'SUPER');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'EXECUTIVE', '임원', 'SUPER', '운영 전반의 권한. 아래 모든 권한을 포함한다.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'EXECUTIVE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'OPERATOR', '운영자', 'EXECUTIVE', '업무·폼 운영에 필요한 권한 묶음.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'OPERATOR');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'SUB_WORK_TYPE_MANAGE', '하위 업무 유형 관리', 'EXECUTIVE', '하위 업무 유형의 등록·수정·사용 여부 전환.', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'SUB_WORK_TYPE_MANAGE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'FORM_LABEL_MANAGE', '폼 라벨 관리', 'EXECUTIVE', '폼 라벨의 생성·비활성화.', TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'FORM_LABEL_MANAGE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'MEMBER_MANAGE', '회원 관리', 'EXECUTIVE', '회원 정보·등급·상태의 조회와 변경.', TRUE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'MEMBER_MANAGE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'ROLE_MANAGE', '역할·권한 관리', 'EXECUTIVE', '역할의 생성·부여와 역할별 권한 지정.', TRUE, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'ROLE_MANAGE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'WORK_MANAGE', '업무 관리', 'OPERATOR', '업무·하위 업무의 등록·조회·상태 전이.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'WORK_MANAGE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'SUB_WORK_TYPE_READ', '하위 업무 유형 조회', 'OPERATOR', '하위 업무 유형 목록 조회(등록 폼의 드롭다운).', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'SUB_WORK_TYPE_READ');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'MEETING_MANAGE', '회의 관리', 'OPERATOR', '회의의 등록·조회·상태 전이와 안건 관리.', TRUE, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'MEETING_MANAGE');

-- #101: 업무·하위 업무를 조회만 할 수 있는 권한. WORK_MANAGE의 자식이라 그 보유자(국장 이상,
-- 그리고 WORK_MANAGE를 직접 부여받은 임의의 역할)는 트리 펼침으로 자동 보유한다.
-- OPERATOR의 자식이 아니라 WORK_MANAGE의 자식인 것은, OPERATOR를 거치지 않고 WORK_MANAGE만
-- 직접 부여받은 역할도 자신이 다룰 수 있는 업무는 당연히 읽을 수 있어야 하기 때문이다 —
-- 형제 노드로 두면 그 경로에서는 조회가 막힌다. 국원에게는 이 권한 하나만 직접 매핑한다.
INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'WORK_READ', '업무·하위 업무 조회', 'WORK_MANAGE', '업무·하위 업무의 목록·상세 조회.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'WORK_READ');

-- #101: 회의를 조회만 할 수 있는 권한. WORK_READ와 같은 이유로 MEETING_MANAGE의 자식이다.
INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'MEETING_READ', '회의 조회', 'MEETING_MANAGE', '회의의 목록·상세·안건 조회.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'MEETING_READ');

-- #101: 회의 안건만 쓸 수 있는 권한. MEETING_MANAGE의 자식이라 국장 이상은 자동 보유하고,
-- 국원은 회의 생성·전이는 못 하지만 안건 작성은 이 권한으로 통과한다.
INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'MEETING_AGENDA_WRITE', '회의 안건 작성', 'MEETING_MANAGE', '회의 안건의 등록·수정·철회.', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'MEETING_AGENDA_WRITE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'FORM_MANAGE', '폼 관리', 'OPERATOR', '폼 조회·작성·접수 상태 변경을 아우르는 묶음.', TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'FORM_MANAGE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'RESPONSE_REVIEW', '응답 심사', 'OPERATOR', '폼 응답의 조회와 심사 결과 반영.', TRUE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'RESPONSE_REVIEW');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'FORM_READ', '폼 조회', 'FORM_MANAGE', '운영자용 폼 목록·상세 조회.', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'FORM_READ');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'FORM_WRITE', '폼 작성·수정', 'FORM_MANAGE', '폼 생성·수정·복제와 라벨 지정 교체.', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'FORM_WRITE');

INSERT INTO authrt (authrt_cd, authrt_nm, up_authrt_cd, authrt_expln, sys_yn, indct_seqno, crt_dt, mdfcn_dt)
SELECT 'FORM_STATUS_CHANGE', '폼 접수 상태 변경', 'FORM_MANAGE', '폼의 접수 시작·마감 전이.', TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM authrt WHERE authrt_cd = 'FORM_STATUS_CHANGE');

-- 이미 시드된 DB(dev·prod)에는 EXECUTIVE가 최상위(up_authrt_cd IS NULL)로 들어가 있다.
-- 위 INSERT는 건너뛰므로 여기서 SUPER 아래로 옮긴다 — sub_work_type.autzr_role_cd와 같은
-- 가드 UPDATE다. IS NULL 조건이 멱등성과 "화면에서 손댄 값은 건드리지 않는다"를 함께 지킨다:
-- 운영진이 EXECUTIVE를 어딘가로 옮겨 두었다면 그 판단을 되돌리지 않는다.
UPDATE authrt SET up_authrt_cd = 'SUPER', mdfcn_dt = CURRENT_TIMESTAMP
WHERE authrt_cd = 'EXECUTIVE' AND up_authrt_cd IS NULL;

-- #101: 하위 업무 유형 관리(SUB_WORK_TYPE_MANAGE)를 EXECUTIVE에서 떼어 SUPER 바로 아래로
-- 옮긴다. EXECUTIVE 자식으로 두면 회장·부회장뿐 아니라 총무까지 자동으로 갖게 되는데,
-- 총무는 이 권한을 갖지 않는 것이 요구사항이다(총무의 다른 EXECUTIVE 자손인 회원관리·
-- 역할관리·폼라벨관리는 그대로 유지 — 그래서 총무를 EXECUTIVE에서 통째로 떼지 않고 이
-- 노드 하나만 옮긴다). up_authrt_cd = 'EXECUTIVE' 가드가 위 EXECUTIVE 재배치 UPDATE와
-- 같은 이유로 멱등성과 "화면에서 이미 옮긴 값은 건드리지 않는다"를 함께 지킨다.
UPDATE authrt SET up_authrt_cd = 'SUPER', mdfcn_dt = CURRENT_TIMESTAMP
WHERE authrt_cd = 'SUB_WORK_TYPE_MANAGE' AND up_authrt_cd = 'EXECUTIVE';

-- 역할↔권한 초기 매핑(role_authrt_rel). 회장·부회장·총무 → EXECUTIVE · 국장 → OPERATOR이며
-- 국원·프로젝트장·스터디장은 부여하지 않는다 — "권한 없는 역할은 아무것도 못 한다"가 기본값이다.
--
-- role_id를 값으로 적지 않고 역할명으로 조회해 넣는다. role_id는 IDENTITY라 환경마다 값이 다르다
-- (그래서 코드도 역할이 아니라 권한만 가리킨다). role_nm은 UNIQUE가 아니므로 같은 이름의 역할이
-- 여럿이면 각각에 붙는데, 그것이 의도한 동작이다.
--
-- 멱등 판정은 (role_id, authrt_cd) 쌍으로 한다 — uk_role_authrt_rel_role_authority와 같은 기준이다.
INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'EXECUTIVE', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm IN ('회장', '부회장', '총무')
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'EXECUTIVE');

INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'OPERATOR', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm = '국장'
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'OPERATOR');

-- 최고관리자 → SUPER (#71). 최초 가입자가 배정받는 역할이며, 이 매핑이 부트스트랩의 전부다.
-- 회원에게 권한을 직접 붙이는 테이블을 새로 만들지 않는 것은 인가 판정 경로를 하나로 두기
-- 위해서다(BR-M28) — AuthorityPolicy는 언제나 '회원 → 역할 → 권한'만 본다.
INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'SUPER', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm = '최고관리자'
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'SUPER');

-- #101: 회장·부회장에게만 하위 업무 유형관리를 명시적으로 되돌려준다. SUB_WORK_TYPE_MANAGE를
-- EXECUTIVE에서 떼어냈으므로(위 authrt UPDATE) 더는 자동 상속되지 않는다 — 총무·국장은
-- 여기 이름이 없어 그대로 제외된다.
INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'SUB_WORK_TYPE_MANAGE', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm IN ('회장', '부회장')
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'SUB_WORK_TYPE_MANAGE');

-- #101: 국원은 업무·하위 업무·회의를 조회만 하고(WORK_READ·MEETING_READ), 회의는 안건 작성만
-- 한다(MEETING_AGENDA_WRITE). 하위 업무의 "본인이 담당자인 건만 쓰기"는 코드가 갖는
-- 판정(SubWorkOwnershipPolicy)이라 role_authrt_rel로 표현되지 않는다.
INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'WORK_READ', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm = '국원'
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'WORK_READ');

INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'MEETING_READ', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm = '국원'
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'MEETING_READ');

INSERT INTO role_authrt_rel (role_id, authrt_cd, crt_dt)
SELECT r.role_id, 'MEETING_AGENDA_WRITE', CURRENT_TIMESTAMP
FROM role r
WHERE r.role_nm = '국원'
  AND NOT EXISTS (
    SELECT 1 FROM role_authrt_rel x WHERE x.role_id = r.role_id AND x.authrt_cd = 'MEETING_AGENDA_WRITE');

-- 하위 업무 유형(sub_work_type). 운영 등록 화면의 업무 유형 4종과 1:1로 대응한다.
-- 유형은 코드가 아니라 기준 데이터라서 여기서 넣는다 (REQ-010 · POL-005 — 승인 정책의 데이터화).
--
-- sub_work_type_id는 지정하지 않는다. IDENTITY 컬럼에 값을 박아 넣으면 시퀀스가 1에 머물러,
-- 관리 화면(#43)이 유형을 추가하는 순간 시드 행과 PK가 충돌한다 — 위 role과 같은 이유다.
-- 멱등 판정은 유형명으로 한다(uk_sub_work_type_name이 있어 이름이 곧 키다).
-- 완료 점검 항목(cmptn_chck_artcl_cn)은 한 줄에 하나씩 적고, 하위 업무 등록 시 그대로 체크리스트가 된다.
--
-- 승인자 역할(autzr_role_cd)은 웹 AUTZR_ROLE_NM 어휘를 쓴다. 배정 근거는 완료 점검 항목이다 —
-- 예산지출은 '회계 장부 반영'이 걸려 있어 총무(TREASURER), 대외공지는 '대외 명의 사용 확인'이
-- 걸려 있어 대표 명의를 가진 회장(PRESIDENT)이 승인 주체다.
-- 승인이 필요 없는 유형(aprv_need_yn = FALSE)은 승인자를 두지 않는다 — 승인 주체가 있는데
-- 승인을 거치지 않는다는 모순된 상태를 데이터로 만들지 않기 위해서다. 웹도 같은 규칙으로 저장한다.
--
-- 감사 컬럼(crt_dt·mdfcn_dt)은 여기서 직접 넣는다. 값은 평소 JPA Auditing이 채우지만 이 파일은
-- JPA를 거치지 않는 순수 SQL이고 컬럼에 DB 기본값도 두지 않아, 빠뜨리면 NOT NULL 위반으로
-- 기동이 깨진다(#43). 유형을 새로 시드할 때도 두 컬럼을 같이 적을 것.
-- use_yn도 마찬가지다. prod 수동 DDL에는 DEFAULT TRUE를 걸지만, local/dev/test는 엔티티에서
-- 생성한 스키마라 기본값이 없다 — 프로필에 따라 갈리지 않도록 시드가 값을 직접 적는다.
INSERT INTO sub_work_type (
    type_nm, aprv_need_yn, autzr_role_cd,
    min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn, use_yn, crt_dt, mdfcn_dt)
SELECT '예산지출', TRUE, 'TREASURER', FALSE, TRUE, '견적서·영수증 확보
예산 항목·잔액 확인
지출 승인 완료
회계 장부 반영', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE type_nm = '예산지출');

INSERT INTO sub_work_type (
    type_nm, aprv_need_yn, autzr_role_cd,
    min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn, use_yn, crt_dt, mdfcn_dt)
SELECT '대외공지', TRUE, 'PRESIDENT', FALSE, FALSE, '공지 문안 검수
게시 채널·일정 확정
대외 명의 사용 확인
게시 결과 링크 첨부', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE type_nm = '대외공지');

INSERT INTO sub_work_type (
    type_nm, aprv_need_yn, min_need_agre_cnt_yn, expnd_yn,
    cmptn_chck_artcl_cn, use_yn, crt_dt, mdfcn_dt)
SELECT '내부행사', FALSE, FALSE, FALSE, '일시·장소 확정
참석자 명단 확인
준비물·역할 분담
진행 결과 정리', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE type_nm = '내부행사');

INSERT INTO sub_work_type (
    type_nm, aprv_need_yn, min_need_agre_cnt_yn, expnd_yn,
    cmptn_chck_artcl_cn, use_yn, crt_dt, mdfcn_dt)
SELECT '스터디운영', FALSE, FALSE, FALSE, '커리큘럼·일정 공유
출석 기록 확인
과제·산출물 확인
운영 결과 정리', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE type_nm = '스터디운영');

-- 위 INSERT는 이미 시드된 DB(dev/prod)에서는 건너뛰므로, 비워 둔 채 들어가 있던 승인자 역할을
-- 여기서 채운다. autzr_role_cd IS NULL 조건이 멱등성과 함께 "화면에서 지정한 값은 건드리지 않는다"를
-- 같이 보장한다.
UPDATE sub_work_type SET autzr_role_cd = 'TREASURER'
WHERE type_nm = '예산지출' AND aprv_need_yn = TRUE AND autzr_role_cd IS NULL;

UPDATE sub_work_type SET autzr_role_cd = 'PRESIDENT'
WHERE type_nm = '대외공지' AND aprv_need_yn = TRUE AND autzr_role_cd IS NULL;

-- 폼 라벨(form_lbl)은 일부러 시드하지 않는다 (#31에서 결정).
-- 후보로 거론된 어휘(신규모집·회원연장·행사·스터디·연도·학기) 중 연도·학기는 해마다 값이
-- 달라져(2026 → 2027, 1학기 → 2학기) 시드로 굳히면 매년 이 파일을 고쳐야 하고, 고치지 않으면
-- 화면에는 지난해 라벨만 남는다. 나머지도 라벨 관리 화면(#34)에서 추가·비활성화하는 운영
-- 데이터라 초기값을 서버가 정할 근거가 없다 — 기준 코드(mbr_grd·mbr_stts)와 다른 성격이다.
-- 폼 상태(form_stts_cd)는 FormStatus enum이 코드값을 갖고 명칭은 화면이 갖는 어휘라
-- 별도 코드 테이블이 없어 여기 시드할 것도 없다.
