-- 기획안 첨부 자료 (server#685 · ssccops#607 · 데이터사전 ssccops#608).
--
-- file_rfrnc.trgt_se_cd CHECK에 FORM_RESPONSE를 더한다 — V13 규칙 그대로(값 목록과 FileTargetType이 정본
-- 한 쌍, FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가 대조). 제약은 이름이 아니라 컬럼으로
-- 찾아 지운다: local(`ddl-auto: update`)은 Hibernate가 먼저 다른 이름으로 만들었을 수 있다(V22 주석).
--
-- 열은 더하지 않는다. 원본 파일명·크기·올린 사람·시각은 V18이 운영 건 첨부를 위해 NULL 허용으로 이미 붙였고,
-- 기획안 첨부도 그 넷을 그대로 채운다. 대상(form_rspns_hstry)에 FK를 걸지 않는 것은 다른 대상과 같다 —
-- 정리는 폼 도메인의 책임이다(초안 보존 정책 FormDraftRetentionService가 첨부 행을 함께 지운다).
--
-- 값 목록은 FileTargetType의 사본이다 — 정본은 그쪽이다.

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
            'OPERATION'::character varying,
            'FORM_RESPONSE'::character varying
        ])::"text"[])));
END $$;
