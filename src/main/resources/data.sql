-- Supabase JWT 최초 로그인 시 임시회원 자동 프로비저닝에 필요한 최소 코드 데이터.
-- 회원 등급/상태 전체 코드는 회원 관리 기능이 붙을 때 별도로 채워진다.
-- H2/Postgres 양쪽에서 동일하게 동작하도록 ON CONFLICT 대신 WHERE NOT EXISTS로 멱등성을 보장한다.
INSERT INTO mbr_grd (mbr_grd_cd, mbr_grd_nm, indct_seqno)
SELECT 'TEMP', '임시회원', 1
WHERE NOT EXISTS (SELECT 1 FROM mbr_grd WHERE mbr_grd_cd = 'TEMP');

INSERT INTO mbr_stts (mbr_stts_cd, mbr_stts_nm, indct_seqno)
SELECT 'ENROLLED', '재학', 1
WHERE NOT EXISTS (SELECT 1 FROM mbr_stts WHERE mbr_stts_cd = 'ENROLLED');
