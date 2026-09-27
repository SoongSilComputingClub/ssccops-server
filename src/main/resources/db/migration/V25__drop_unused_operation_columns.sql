-- 값이 한 번도 들어가지 않는 운영 도메인 컬럼 10개를 지운다 (#595 · ssccops#537).
--
-- 코드는 이 컬럼들에 NULL·false·0 같은 고정값만 쓰고 아무도 읽지 않았다. 2026-09-27 prod에서도
-- 값이 없음을 확인했다(work_prgrs_rt의 옛 값 3건만 예외 — 아래).
--
--   | 컬럼                                              | 왜 남아 있었나                                   |
--   |---------------------------------------------------|--------------------------------------------------|
--   | sub_work.dly_yn                                   | #117에서 채우지 않기로 했다. 지연은 조회 시점에   |
--   |                                                   | 판정한다(SubWorkEntity.isDelayedBefore)           |
--   | work.work_prgrs_rt                                | #117(AGG-05)에서 갱신을 끊었다. 진행률은 조회 때  |
--   |                                                   | 계산한다(ProgressRate)                            |
--   | mtg.insd_mtg_dtl_cn · otsd_mtg_dtl_cn             | 회의 단위 회의록 본문 자리. 쓰는 경로가 없었다     |
--   | sub_work_aprv.aprv_stp · emrg_se_cd · emrg_rsn ·  | 긴급 예외 집행(OPS-016)과 위험도 기반 승인 단계   |
--   |   epfc_aprv_term_ymd                              | 자리. #47에서 범위 밖으로 뺐다                    |
--   | sub_work_type.crtr_amt · expnd_yn                 | 금액 기반 위험도 판정(REQ-016) 자리              |
--
-- 앞의 둘은 삭제만 «Flyway 도입 시점»으로 미뤄 두었다 — ddl-auto: update가 삭제를 반영하지 않아서였다.
-- 미래 기능 자리였던 것은 그 기능을 만들 때 새 마이그레이션으로 다시 더한다(ssccops#537).
--
-- **되돌릴 수 없는 것은 값 두 가지뿐이다** — 컬럼은 ADD COLUMN으로 돌아오지만 값은 돌아오지 않는다.
-- 둘 다 아무도 읽지 않던 값이라 지우는 판단은 바뀌지 않고, 무엇을 잃었는지만 아래 NOTICE로 남긴다.
--
--   - work.work_prgrs_rt가 0이 아닌 행 — #117이 prod에 나가기 전(v0.1.1) 옛 식이 채운 값이다. 정본이
--     아니고 화면은 이 값을 쓰지 않았다
--   - sub_work_type.expnd_yn = true — V3 시드 '예산지출' 한 줄
--
-- ⚠️ V3 시드는 sub_work_type INSERT의 컬럼 목록에 expnd_yn을 적는다. dev·prod·local은 V3가 이
-- 파일보다 먼저 돌아 무관하다. test 프로필은 Flyway 없이 V3를 엔티티로 만든 H2 스키마에 돌리므로
-- 그 컬럼을 test에서만 되살린다(src/test/resources/db/v3-seed-legacy-columns.sql).

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT work_id, work_prgrs_rt FROM "public"."work"
              WHERE work_prgrs_rt <> 0 ORDER BY work_id LOOP
        RAISE NOTICE 'V25: work_id=%의 옛 work_prgrs_rt=%를 지운다 (정본이 아니다 — #117)',
            r.work_id, r.work_prgrs_rt;
    END LOOP;
    FOR r IN SELECT sub_work_type_id, type_nm FROM "public"."sub_work_type"
              WHERE expnd_yn ORDER BY sub_work_type_id LOOP
        RAISE NOTICE 'V25: sub_work_type_id=%(%)의 expnd_yn=true를 지운다',
            r.sub_work_type_id, r.type_nm;
    END LOOP;
END $$;

ALTER TABLE "public"."sub_work" DROP COLUMN IF EXISTS "dly_yn";

ALTER TABLE "public"."work" DROP COLUMN IF EXISTS "work_prgrs_rt";

ALTER TABLE "public"."mtg" DROP COLUMN IF EXISTS "insd_mtg_dtl_cn";
ALTER TABLE "public"."mtg" DROP COLUMN IF EXISTS "otsd_mtg_dtl_cn";

ALTER TABLE "public"."sub_work_aprv" DROP COLUMN IF EXISTS "aprv_stp";
ALTER TABLE "public"."sub_work_aprv" DROP COLUMN IF EXISTS "emrg_se_cd";
ALTER TABLE "public"."sub_work_aprv" DROP COLUMN IF EXISTS "emrg_rsn";
ALTER TABLE "public"."sub_work_aprv" DROP COLUMN IF EXISTS "epfc_aprv_term_ymd";

ALTER TABLE "public"."sub_work_type" DROP COLUMN IF EXISTS "crtr_amt";
ALTER TABLE "public"."sub_work_type" DROP COLUMN IF EXISTS "expnd_yn";
