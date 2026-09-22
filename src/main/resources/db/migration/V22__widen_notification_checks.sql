-- ============================================================================
-- V22 — 알림 종류·대상 종류 CHECK 제약을 넓힌다 (noti.noti_type_cd · noti.trgt_type_cd)
-- ============================================================================
-- #528 · ssccops#453(응답 결과·참가 상태 알림) · ssccops#454(테스트 알림) · ADR-0045.
--
-- V21이 세운 두 제약은 1차 사건(하위 업무 다섯 · 대상 SUB_WORK 하나)의 값 목록이다. 2차 사건이
-- 들어오며 NotificationType에 일곱, NotificationTargetType에 셋이 늘었다 — enum만 늘리고 제약을
-- 두면 첫 검토 알림 INSERT가 제약 위반으로 죽고 리스너가 ERROR 한 줄만 남긴다(V13이 두 번 겪은
-- 결함의 알림판이다). 그래서 enum과 이 파일이 같은 PR에 있고
-- FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가 둘을 전수 대조한다.
--
-- **제약을 이름이 아니라 컬럼으로 찾아 지운다**(V13 규칙). V21은 이름을 박아 만들었지만 local은
-- ddl-auto: update라 Hibernate가 먼저 다른 이름으로 만들었을 수 있다 — 이름으로 지우면 그 환경에
-- 옛 좁은 제약이 살아남아 «마이그레이션은 성공했는데 여전히 500»이 된다.
--
-- 값 목록은 NotificationType · NotificationTargetType의 사본이다 — 정본은 enum이다.
--
--   noti_type_cd  + RESPONSE_ACCEPTED · RESPONSE_REJECTED · RESPONSE_CHANGES_REQUESTED
--                 + APPLICATION_CONFIRMED · APPLICATION_WAITLISTED · APPLICATION_CANCELLED
--                 + TEST
--   trgt_type_cd  + FORM_RESPONSE · EVENT_PARTICIPANT · MEMBER
-- ============================================================================

DO $$
DECLARE
    stale_constraint text;
BEGIN
    IF to_regclass('public.noti') IS NULL THEN
        RETURN;
    END IF;

    -- noti_type_cd 하나에만 걸린 CHECK 제약을 이름과 무관하게 전부 걷어낸다
    FOR stale_constraint IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_attribute a
            ON a.attrelid = c.conrelid
           AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'public.noti'::regclass
           AND c.contype = 'c'
           AND a.attname = 'noti_type_cd'
           AND cardinality(c.conkey) = 1
    LOOP
        EXECUTE format('ALTER TABLE "public"."noti" DROP CONSTRAINT %I', stale_constraint);
    END LOOP;

    ALTER TABLE "public"."noti"
        ADD CONSTRAINT "noti_noti_type_cd_check"
        CHECK ((("noti_type_cd")::"text" = ANY ((ARRAY[
            'APPROVAL_REQUESTED'::character varying,
            'APPROVAL_APPROVED'::character varying,
            'APPROVAL_REJECTED'::character varying,
            'DEADLINE_DUE'::character varying,
            'DEADLINE_OVERDUE'::character varying,
            'RESPONSE_ACCEPTED'::character varying,
            'RESPONSE_REJECTED'::character varying,
            'RESPONSE_CHANGES_REQUESTED'::character varying,
            'APPLICATION_CONFIRMED'::character varying,
            'APPLICATION_WAITLISTED'::character varying,
            'APPLICATION_CANCELLED'::character varying,
            'TEST'::character varying
        ])::"text"[])));

    -- trgt_type_cd도 같은 방식으로
    FOR stale_constraint IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_attribute a
            ON a.attrelid = c.conrelid
           AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'public.noti'::regclass
           AND c.contype = 'c'
           AND a.attname = 'trgt_type_cd'
           AND cardinality(c.conkey) = 1
    LOOP
        EXECUTE format('ALTER TABLE "public"."noti" DROP CONSTRAINT %I', stale_constraint);
    END LOOP;

    ALTER TABLE "public"."noti"
        ADD CONSTRAINT "noti_trgt_type_cd_check"
        CHECK ((("trgt_type_cd")::"text" = ANY ((ARRAY[
            'SUB_WORK'::character varying,
            'FORM_RESPONSE'::character varying,
            'EVENT_PARTICIPANT'::character varying,
            'MEMBER'::character varying
        ])::"text"[])));
END $$;
