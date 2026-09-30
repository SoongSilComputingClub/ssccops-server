-- ============================================================================
-- V27 — 학술 프로그램 «폐지»·«복원» (acdm_actv.acdm_actv_stts_cd · acdm_actv_aprv)
-- ============================================================================
-- #611 · ADR-0058(폐지는 종료처럼 쓰기를 멈추고, 학술국장이 폐지 전 상태로 되돌린다).
--
-- 세 가지를 한다.
--
--   1. acdm_actv.acdm_actv_stts_cd CHECK에 DISCONTINUED를 더한다
--        APPROVED · ONGOING · COMPLETED + DISCONTINUED            (AcademicProgramStatus)
--   2. acdm_actv_aprv.acdm_actv_aprv_se_cd CHECK에 DISCONTINUE·REINSTATE를 더한다
--        SESSION · COMPLETION · REOPEN + DISCONTINUE · REINSTATE  (AcademicProgramApprovalPoint)
--   3. acdm_actv_aprv.bfr_acdm_actv_stts_cd(폐지 전 상태)를 더한다 — nullable, 1과 같은 CHECK
--
-- 3이 있어야 복원이 성립한다. 복원은 «폐지 전 상태»로 돌아가는데 그것을 추론하지 않고 폐지 줄에
-- 적어 둔다 — 모집 시작은 이력 줄을 남기지 않고 모집 폼 상태는 폼 화면에서도 바뀌어 둘 다 증거가
-- 못 된다(ADR-0058). **폐지 줄에만 값이 있고** 나머지 지점(SESSION·COMPLETION·REOPEN·REINSTATE)은
-- NULL이라 NOT NULL을 걸지 않는다. 기존 행은 채울 값이 없다(전부 폐지 줄이 아니다).
-- 이름의 bfr_는 ADR-0042의 어휘다(mbr_grd_hstry.bfr_mbr_grd_cd 등 «변경 전 값»).
--
-- 3의 CHECK가 1과 같은 넷인 것은 FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가
-- @Enumerated(STRING) 컬럼마다 «enum 상수 = CHECK 허용 값»을 요구하기 때문이다 — 실제로 들어오는
-- 값은 승인·진행 중뿐이다(AcademicProgramTransition.DISCONTINUE가 그 둘에서만 출발한다).
--
-- enum만 늘리고 제약을 두면 첫 폐지가 제약 위반으로 500이 된다(V13이 두 번 겪은 결함이다). 그래서
-- enum과 이 파일이 같은 PR에 있다.
--
-- **제약을 이름이 아니라 컬럼으로 찾아 지운다**(V13 · V22 · V26 규칙). baseline은 이름을 박아
-- 두었지만 local은 ddl-auto: update라 Hibernate가 먼저 다른 이름으로 만들었을 수 있다 — 이름으로
-- 지우면 그 환경에 옛 좁은 제약이 살아남아 «마이그레이션은 성공했는데 여전히 500»이 된다. 3도
-- 같은 이유로 컬럼이 이미 있으면(local이 먼저 만들었으면) 더하지 않고 제약만 다시 건다.
--
-- 값 목록은 enum의 사본이다 — 정본은 enum이다.
--
-- ⚠️ 되돌리기: DISCONTINUED 프로그램과 DISCONTINUE·REINSTATE 이력 행이 생긴 뒤에는 그 행을 옮겨야
-- CHECK를 좁힐 수 있다. bfr_acdm_actv_stts_cd는 지울 수 있지만 그러면 복원 대상을 알 길이 없다.
-- ============================================================================

DO $$
DECLARE
    stale_constraint text;
BEGIN
    -- ── 1. acdm_actv.acdm_actv_stts_cd ─────────────────────────────────────
    IF to_regclass('public.acdm_actv') IS NOT NULL THEN
        FOR stale_constraint IN
            SELECT c.conname
              FROM pg_constraint c
              JOIN pg_attribute a
                ON a.attrelid = c.conrelid
               AND a.attnum = ANY (c.conkey)
             WHERE c.conrelid = 'public.acdm_actv'::regclass
               AND c.contype = 'c'
               AND a.attname = 'acdm_actv_stts_cd'
               AND cardinality(c.conkey) = 1
        LOOP
            EXECUTE format('ALTER TABLE "public"."acdm_actv" DROP CONSTRAINT %I', stale_constraint);
        END LOOP;

        ALTER TABLE "public"."acdm_actv"
            ADD CONSTRAINT "acdm_actv_acdm_actv_stts_cd_check"
            CHECK ((("acdm_actv_stts_cd")::"text" = ANY ((ARRAY[
                'APPROVED'::character varying,
                'ONGOING'::character varying,
                'COMPLETED'::character varying,
                'DISCONTINUED'::character varying
            ])::"text"[])));
    END IF;

    IF to_regclass('public.acdm_actv_aprv') IS NULL THEN
        RETURN;
    END IF;

    -- ── 2. acdm_actv_aprv.acdm_actv_aprv_se_cd ─────────────────────────────
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
            'REOPEN'::character varying,
            'DISCONTINUE'::character varying,
            'REINSTATE'::character varying
        ])::"text"[])));

    -- ── 3. acdm_actv_aprv.bfr_acdm_actv_stts_cd ────────────────────────────
    ALTER TABLE "public"."acdm_actv_aprv"
        ADD COLUMN IF NOT EXISTS "bfr_acdm_actv_stts_cd" character varying(20);

    FOR stale_constraint IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_attribute a
            ON a.attrelid = c.conrelid
           AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'public.acdm_actv_aprv'::regclass
           AND c.contype = 'c'
           AND a.attname = 'bfr_acdm_actv_stts_cd'
           AND cardinality(c.conkey) = 1
    LOOP
        EXECUTE format('ALTER TABLE "public"."acdm_actv_aprv" DROP CONSTRAINT %I', stale_constraint);
    END LOOP;

    ALTER TABLE "public"."acdm_actv_aprv"
        ADD CONSTRAINT "acdm_actv_aprv_bfr_acdm_actv_stts_cd_check"
        CHECK ((("bfr_acdm_actv_stts_cd")::"text" = ANY ((ARRAY[
            'APPROVED'::character varying,
            'ONGOING'::character varying,
            'COMPLETED'::character varying,
            'DISCONTINUED'::character varying
        ])::"text"[])));
END $$;
