-- Supabase Free 일시정지 방지 — GitHub Actions가 칠 heartbeat 함수 (#487 · ssccops#394 · ADR-0040).
--
-- dev·prod의 앱 DB는 Supabase Postgres이고 Free 플랜은 7일간 «사용자 DB 쿼리»가 적으면 프로젝트를
-- 멈춘다. 1차는 서버 안 스케줄러(DatabaseKeepAlive · 하루 한 번 SELECT 1)이고, 이 함수는 2차 —
-- 메타 레포 워크플로가 `POST /rest/v1/rpc/heartbeat`로 부른다(서버가 죽어 있을 때의 보험 + 멈추면
-- 빨개지는 감시). 대시보드 SQL Editor로 만들지 않는 것은 «baseline + 마이그레이션이 곧 DB»(ssccops#213)
-- 때문이다 — 어느 환경에 함수가 있는지 이 파일이 답한다.
--
-- anon에 주는 것은 이 함수의 EXECUTE 하나뿐이다. 테이블에는 어떤 grant도 주지 않는다 — Flyway가
-- 만든 테이블에는 Supabase 기본 grant(anon·authenticated)가 붙어 있지 않아 anon 키로 읽히지 않는
-- 상태(2026-09-19 실측: 42501)이고, 그것이 유지돼야 한다.
--
-- `anon` 역할은 Supabase에만 있다. 로컬 Postgres·Testcontainers(FlywayMigrationValidateTest)에는
-- 없으므로 역할이 있을 때만 grant한다 — 없으면 이 마이그레이션이 그 환경에서 죽는다.
-- `NOTIFY pgrst`는 PostgREST의 스키마 캐시를 새로 읽게 한다(Supabase는 DDL 이벤트 트리거로도
-- 갱신하지만, 안 되는 경우 함수가 있어도 PGRST202로 보인다). 다른 Postgres에서는 듣는 이가 없어 무해하다.

CREATE OR REPLACE FUNCTION public.heartbeat()
    RETURNS integer
    LANGUAGE sql
    STABLE
AS $$
    SELECT 1
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        GRANT EXECUTE ON FUNCTION public.heartbeat() TO anon;
    END IF;
END
$$;

NOTIFY pgrst, 'reload schema';
