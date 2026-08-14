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

-- 조직 역할(role) 마스터.
--
-- indct_seqno는 화면 정렬 순번이지만 여기서는 **서열 오름차순**(회장 1 → 부회장 2 → 총무 3 → 국장 4 → …)
-- 으로 채운다. role 테이블에는 서열 컬럼이 따로 없어서, "국장 이상"(#9)을 판정하려면 기댈 곳이
-- 이 순번뿐이다. 판정 로직 자체는 #9의 범위라 여기서 만들지 않고, 그 위에 얹을 수 있는 순서만 보장한다.
-- 역할을 새로 추가할 때 서열 중간에 끼워 넣으려면 뒤쪽 순번을 밀어야 한다는 뜻이기도 하다.
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

-- 하위 업무 유형(sub_work_type). 운영 등록 화면의 업무 유형 4종과 1:1로 대응한다.
-- 유형은 코드가 아니라 기준 데이터라서 여기서 넣는다 (REQ-010 · POL-005 — 승인 정책의 데이터화).
-- 등록 API가 이 행을 id로 참조하므로 식별자를 고정해 넣는다.
-- 완료 점검 항목(cmptn_chck_artcl_cn)은 한 줄에 하나씩 적고, 하위 업무 등록 시 그대로 체크리스트가 된다.
--
-- 승인자 역할(autzr_role_cd)은 웹 AUTZR_ROLE_NM 어휘를 쓴다. 배정 근거는 완료 점검 항목이다 —
-- 예산지출은 '회계 장부 반영'이 걸려 있어 총무(TREASURER), 대외공지는 '대외 명의 사용 확인'이
-- 걸려 있어 대표 명의를 가진 회장(PRESIDENT)이 승인 주체다.
-- 승인이 필요 없는 유형(aprv_need_yn = FALSE)은 승인자를 두지 않는다 — 승인 주체가 있는데
-- 승인을 거치지 않는다는 모순된 상태를 데이터로 만들지 않기 위해서다. 웹도 같은 규칙으로 저장한다.
INSERT INTO sub_work_type (
    sub_work_type_id, type_nm, aprv_need_yn, autzr_role_cd,
    min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn)
SELECT 1, '예산지출', TRUE, 'TREASURER', FALSE, TRUE, '견적서·영수증 확보
예산 항목·잔액 확인
지출 승인 완료
회계 장부 반영'
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE sub_work_type_id = 1);

INSERT INTO sub_work_type (
    sub_work_type_id, type_nm, aprv_need_yn, autzr_role_cd,
    min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn)
SELECT 2, '대외공지', TRUE, 'PRESIDENT', FALSE, FALSE, '공지 문안 검수
게시 채널·일정 확정
대외 명의 사용 확인
게시 결과 링크 첨부'
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE sub_work_type_id = 2);

INSERT INTO sub_work_type (
    sub_work_type_id, type_nm, aprv_need_yn, min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn)
SELECT 3, '내부행사', FALSE, FALSE, FALSE, '일시·장소 확정
참석자 명단 확인
준비물·역할 분담
진행 결과 정리'
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE sub_work_type_id = 3);

INSERT INTO sub_work_type (
    sub_work_type_id, type_nm, aprv_need_yn, min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn)
SELECT 4, '스터디운영', FALSE, FALSE, FALSE, '커리큘럼·일정 공유
출석 기록 확인
과제·산출물 확인
운영 결과 정리'
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE sub_work_type_id = 4);

-- 위 INSERT는 이미 시드된 DB(dev/prod)에서는 건너뛰므로, 비워 둔 채 들어가 있던 승인자 역할을
-- 여기서 채운다. autzr_role_cd IS NULL 조건이 멱등성과 함께 "화면에서 지정한 값은 건드리지 않는다"를
-- 같이 보장한다.
UPDATE sub_work_type SET autzr_role_cd = 'TREASURER'
WHERE sub_work_type_id = 1 AND aprv_need_yn = TRUE AND autzr_role_cd IS NULL;

UPDATE sub_work_type SET autzr_role_cd = 'PRESIDENT'
WHERE sub_work_type_id = 2 AND aprv_need_yn = TRUE AND autzr_role_cd IS NULL;

-- 폼 라벨(form_lbl)은 일부러 시드하지 않는다 (#31에서 결정).
-- 후보로 거론된 어휘(신규모집·회원연장·행사·스터디·연도·학기) 중 연도·학기는 해마다 값이
-- 달라져(2026 → 2027, 1학기 → 2학기) 시드로 굳히면 매년 이 파일을 고쳐야 하고, 고치지 않으면
-- 화면에는 지난해 라벨만 남는다. 나머지도 라벨 관리 화면(#34)에서 추가·비활성화하는 운영
-- 데이터라 초기값을 서버가 정할 근거가 없다 — 기준 코드(mbr_grd·mbr_stts)와 다른 성격이다.
-- 폼 상태(form_stts_cd)는 FormStatus enum이 코드값을 갖고 명칭은 화면이 갖는 어휘라
-- 별도 코드 테이블이 없어 여기 시드할 것도 없다.
