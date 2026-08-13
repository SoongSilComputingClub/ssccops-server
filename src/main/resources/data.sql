-- Supabase JWT 최초 로그인 시 임시회원 자동 프로비저닝에 필요한 최소 코드 데이터.
-- 회원 등급/상태 전체 코드는 회원 관리 기능이 붙을 때 별도로 채워진다.
-- H2/Postgres 양쪽에서 동일하게 동작하도록 ON CONFLICT 대신 WHERE NOT EXISTS로 멱등성을 보장한다.
INSERT INTO mbr_grd (mbr_grd_cd, mbr_grd_nm, indct_seqno)
SELECT 'TEMP', '임시회원', 1
WHERE NOT EXISTS (SELECT 1 FROM mbr_grd WHERE mbr_grd_cd = 'TEMP');

INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'ENROLLED', '재학', 1
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'ENROLLED');

-- 하위 업무 유형(sub_work_type). 운영 등록 화면의 업무 유형 4종과 1:1로 대응한다.
-- 유형은 코드가 아니라 기준 데이터라서 여기서 넣는다 (REQ-010 · POL-005 — 승인 정책의 데이터화).
-- 등록 API가 이 행을 id로 참조하므로 식별자를 고정해 넣는다.
-- 완료 점검 항목(cmptn_chck_artcl_cn)은 한 줄에 하나씩 적고, 하위 업무 등록 시 그대로 체크리스트가 된다.
-- 승인자 역할 코드(autzr_role_cd)는 역할 기준 코드가 확정되지 않아 비워 둔다 —
-- 승인 처리(OPS-014)가 붙을 때 채운다. 승인 필요 여부만 등록 시점의 승인 상태를 가른다.
INSERT INTO sub_work_type (
    sub_work_type_id, type_nm, aprv_need_yn, min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn)
SELECT 1, '예산지출', TRUE, FALSE, TRUE, '견적서·영수증 확보
예산 항목·잔액 확인
지출 승인 완료
회계 장부 반영'
WHERE NOT EXISTS (SELECT 1 FROM sub_work_type WHERE sub_work_type_id = 1);

INSERT INTO sub_work_type (
    sub_work_type_id, type_nm, aprv_need_yn, min_need_agre_cnt_yn, expnd_yn, cmptn_chck_artcl_cn)
SELECT 2, '대외공지', TRUE, FALSE, FALSE, '공지 문안 검수
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
