-- ============================================================================
-- V13 — 파일 대상 구분 CHECK 제약을 넓힌다 (file_rfrnc.trgt_se_cd)
-- ============================================================================
-- **V1이 옮겨 온 제약은 값이 `SESSION` 하나뿐이다.** prod `pg_dump`를 그대로 담았고, 그때는
-- 파일이 붙는 자리가 학술 출석 인증사진 하나였으므로 맞는 정의였다.
--
-- 그 뒤 `FileTargetType`에 `RAG_DOCUMENT`가 들어왔는데(#402 · ADR-0029) **그 변경이 이 제약을
-- 넓히지 않았다.** 그래서 규정 문서 업로드(`POST /v1/assistant/documents`)는 형식·크기와 무관하게
-- 마지막 `file_rfrnc` INSERT에서 제약 위반으로 터지고, `GlobalExceptionHandler`가 그것을
-- `COMMON500 «서버 내부 오류입니다.»`로 내보낸다 — 화면이 보여 준 그 문장이다.
--
-- **`ddl-auto: update`인 로컬에서도 그대로였다** — `update`는 기존 CHECK 제약을 넓히지 않는다.
-- enum에 값을 더한 뒤 몇십 번을 다시 띄워도 `SESSION` 하나다. `test`가 초록인 것은 그쪽이
-- Flyway가 꺼져 있고 H2 스키마를 Hibernate가 **현재 enum으로** 새로 만들기 때문이며,
-- `FlywayMigrationValidateTest`가 못 잡은 것은 Hibernate의 `validate`가 테이블·컬럼·타입만 보고
-- CHECK 제약은 보지 않기 때문이다.
--
-- **`V6__widen_share_target_check.sql`과 같은 결함이다.** 그 파일이 «값을 더할 때 두 자리를 함께
-- 고친다»를 주석으로 못 박아 두었는데 그 규칙이 `file_rfrnc`에는 닿지 않았다 — 문장으로만 적어
-- 둔 것이 두 번째로 샌 것이므로, 이번에는 기계가 본다:
-- `FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums`가 `@Enumerated(STRING)` 필드
-- 스물아홉 개를 `pg_constraint`와 전수 대조한다. 셋째 번은 없다.
--
-- **제약을 이름이 아니라 컬럼으로 찾아 지운다.** V6은 이름을 박아 `DROP ... IF EXISTS` 했는데,
-- 그 이름은 PostgreSQL이 자동으로 지은 것(`{테이블}_{컬럼}_check`)이라 규칙상 dev·prod가 같을
-- 뿐 확인된 사실은 아니다. 이름이 다른 환경이 하나라도 있으면 옛 좁은 제약이 그대로 살아남아
-- 이 파일이 «성공했는데 여전히 500»을 만든다 — 그 실패는 배포에서만 보인다. 컬럼으로 찾으면
-- 그 경우가 없고, 덤으로 모든 환경의 제약 이름이 아래 한 이름으로 모인다.
--
-- 값 목록은 `FileTargetType`의 사본이다 — 정본은 그쪽이다.
-- ============================================================================

DO $$
DECLARE
    stale_constraint text;
BEGIN
    IF to_regclass('public.file_rfrnc') IS NULL THEN
        RETURN;
    END IF;

    -- trgt_se_cd 하나에만 걸린 CHECK 제약을 이름과 무관하게 전부 걷어낸다
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
            'RAG_DOCUMENT'::character varying
        ])::"text"[])));
END $$;
