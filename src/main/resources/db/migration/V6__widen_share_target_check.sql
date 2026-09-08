-- ============================================================================
-- V6 — 공유 대상 구분 CHECK 제약을 넓힌다 (shr_lnk.shr_trgt_se_cd)
-- ============================================================================
-- **V2가 옮겨 온 제약은 값이 `SUB_WORK` 하나뿐이다.** dev에 붙어 있던 것을 그대로 옮겼고,
-- 그때는 대상이 하나였으므로 맞는 정의였다.
--
-- 그 뒤 `ShareTargetType`에 값이 다섯 늘었는데(#306 `WORK` · #311 `ACADEMIC_PROGRAM`·
-- `ACADEMIC_SESSION` · #312 `EVENT` · 이 이슈 `MEETING`) **그중 어느 것도 이 제약을 넓히지
-- 않았다.** 그래서 지금 dev·prod에서는 `SUB_WORK` 밖의 발급이 전부 제약 위반으로 터진다.
--
-- **테스트가 초록인데도 그렇다** — `test` 프로필은 Flyway가 꺼져 있고 H2 스키마를 Hibernate가
-- 현재 enum으로 만들어 주므로 이 어긋남이 테스트에 보이지 않는다. ssccops#213이 Flyway를 들인
-- 이유가 정확히 이 종류의 어긋남이며, 여기서는 그 반대 방향으로(스키마가 코드보다 좁아서)
-- 같은 일이 났다.
--
-- **값을 전부 적어 다시 만든다.** 넷을 각각 되돌리는 대신 지금 열린 여섯을 한 번에 적는 것은,
-- 네 이슈가 병렬로 돌아 각자 마이그레이션을 쓰면 번호와 제약 정의가 서로 어긋나기 때문이다.
-- `ShareTargetType`이 정본이고 이 목록은 그 사본이다 — 값을 더할 때 두 자리를 함께 고친다.
--
-- 제약 이름은 V2가 쓴 것(dev에 붙어 있는 Hibernate가 지은 이름)을 그대로 유지한다. 여기서
-- 읽기 좋은 이름으로 바꾸면 두 환경의 제약 이름이 갈린다.
--
-- 테이블이 없는 환경은 없다 — V2가 만들었다. 그래도 `to_regclass` 판정을 두는 것은 V2와 같은
-- 이유이며(`ADD CONSTRAINT`에 `IF NOT EXISTS`가 없다), `DROP ... IF EXISTS`는 제약이 다른
-- 이름으로 붙어 있거나 아예 없는 환경에서도 이 파일이 통과하게 한다.
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('public.shr_lnk') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE "public"."shr_lnk"
        DROP CONSTRAINT IF EXISTS "shr_lnk_shr_trgt_se_cd_check";

    ALTER TABLE "public"."shr_lnk"
        ADD CONSTRAINT "shr_lnk_shr_trgt_se_cd_check"
        CHECK ((("shr_trgt_se_cd")::"text" = ANY ((ARRAY[
            'SUB_WORK'::character varying,
            'WORK'::character varying,
            'MEETING'::character varying,
            'ACADEMIC_PROGRAM'::character varying,
            'ACADEMIC_SESSION'::character varying,
            'EVENT'::character varying
        ])::"text"[])));
END $$;
