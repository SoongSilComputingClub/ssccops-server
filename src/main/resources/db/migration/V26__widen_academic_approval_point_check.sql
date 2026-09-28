-- ============================================================================
-- V26 — 학술 승인 이력의 구분 CHECK 제약을 REOPEN까지 넓힌다 (acdm_actv_aprv.acdm_actv_aprv_se_cd)
-- ============================================================================
-- #597 · ADR-0057(종료는 쓰기를 멈추고 학술국장이 재시작으로 되돌린다).
--
-- 재시작(COMPLETED → ONGOING)이 «누가 언제 다시 열었나»를 이 테이블에 한 줄로 남긴다 —
-- AcademicProgramApprovalPoint에 REOPEN이 늘었다. 감사 로그로만 남기지 않는 것은 그것을 읽어
-- 오는 API가 없어 화면에서 보이지 않기 때문이다. 종료 줄은 지우지 않는다(이력은 덧붙이기만).
--
-- enum만 늘리고 제약을 두면 첫 재시작 INSERT가 제약 위반으로 500이 된다(V13이 두 번 겪은
-- 결함이다). 그래서 enum과 이 파일이 같은 PR에 있고
-- FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가 둘을 전수 대조한다.
--
-- **제약을 이름이 아니라 컬럼으로 찾아 지운다**(V13 · V22 규칙). baseline은 이름을 박아 두었지만
-- local은 ddl-auto: update라 Hibernate가 먼저 다른 이름으로 만들었을 수 있다 — 이름으로 지우면
-- 그 환경에 옛 좁은 제약이 살아남아 «마이그레이션은 성공했는데 여전히 500»이 된다.
--
-- 값 목록은 AcademicProgramApprovalPoint의 사본이다 — 정본은 enum이다.
--
--   acdm_actv_aprv_se_cd  SESSION · COMPLETION + REOPEN
-- ============================================================================

DO $$
DECLARE
    stale_constraint text;
BEGIN
    IF to_regclass('public.acdm_actv_aprv') IS NULL THEN
        RETURN;
    END IF;

    -- acdm_actv_aprv_se_cd 하나에만 걸린 CHECK 제약을 이름과 무관하게 전부 걷어낸다
    FOR stale_constraint IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_attribute a
            ON a.attrelid = c.conrelid
           AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'public.acdm_actv_aprv'::regclass
           AND c.contype = 'c'
           AND a.attname = 'acdm_actv_aprv_se_cd'
           AND cardinality(c.conkey) = 1
    LOOP
        EXECUTE format('ALTER TABLE "public"."acdm_actv_aprv" DROP CONSTRAINT %I', stale_constraint);
    END LOOP;

    ALTER TABLE "public"."acdm_actv_aprv"
        ADD CONSTRAINT "acdm_actv_aprv_acdm_actv_aprv_se_cd_check"
        CHECK ((("acdm_actv_aprv_se_cd")::"text" = ANY ((ARRAY[
            'SESSION'::character varying,
            'COMPLETION'::character varying,
            'REOPEN'::character varying
        ])::"text"[])));
END $$;
