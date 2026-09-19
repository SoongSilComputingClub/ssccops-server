-- 운영 건 파일 첨부 (#493 · ssccops#410 · ADR-0042 «추가»라 이력 대상 아님).
--
-- 1. file_rfrnc.trgt_se_cd CHECK에 OPERATION 추가 — V13 규칙 그대로(값 목록과 FileTargetType이 정본 한 쌍,
--    FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가 대조).
-- 2. file_rfrnc에 첨부 메타 열 넷 — 원본 파일명·크기·올린 사람·시각. 전부 NULL 허용: 기존 행(회차 사진·규정
--    문서·갤러리)은 채울 값이 없고, 그 대상들은 이 열을 읽지 않는다. FK를 걸지 않는 것은 rgtr_mbr_id가
--    표시용이라서다 — 회원이 지워져도 첨부는 남는다(파일 참조 행 자체가 FK 없이 대상을 가리키는 것과 같다).

DO $$
DECLARE
    stale_constraint text;
BEGIN
    IF to_regclass('public.file_rfrnc') IS NULL THEN
        RETURN;
    END IF;
    FOR stale_constraint IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_attribute a
            ON a.attrelid = c.conrelid
           AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'public.file_rfrnc'::regclass
           AND c.contype = 'c'
           AND a.attname = 'trgt_se_cd'
           AND cardinality(c.conkey) = 1
    LOOP
        EXECUTE format(
            'ALTER TABLE "public"."file_rfrnc" DROP CONSTRAINT %I', stale_constraint);
    END LOOP;
    ALTER TABLE "public"."file_rfrnc"
        ADD CONSTRAINT "file_rfrnc_trgt_se_cd_check"
        CHECK ((("trgt_se_cd")::"text" = ANY ((ARRAY[
            'SESSION'::character varying,
            'RAG_DOCUMENT'::character varying,
            'CONTENT_POST'::character varying,
            'OPERATION'::character varying
        ])::"text"[])));
END $$;

ALTER TABLE "public"."file_rfrnc"
    ADD COLUMN IF NOT EXISTS "orgnl_file_nm" character varying(255),
    ADD COLUMN IF NOT EXISTS "file_size" bigint,
    ADD COLUMN IF NOT EXISTS "rgtr_mbr_id" bigint,
    ADD COLUMN IF NOT EXISTS "crt_dt" timestamp(6) with time zone;
