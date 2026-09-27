-- 회의 안건은 언제나 운영 건을 가리킨다 (#593 · ssccops#528 · ADR-0055).
--
-- 그전에는 안건_명(agnd_nm)과 운영_ID(oper_id)가 상호 배타였고 독립 안건(agnd_nm만 있는 안건)이
-- 설계에 있었다. 어드민 화면에는 그것을 만들 길이 없었고(안건 상정이 언제나 agendaName: null을
-- 보낸다) 실제 데이터도 전부 연결형이었다 — MCP가 설계대로 그 길을 열면서 어긋남이 드러났다(#591).
--
-- **되돌릴 수 없는 부분은 스키마가 아니라 데이터다.** 컬럼과 제약은 새 마이그레이션으로 되돌릴 수
-- 있지만(ADD COLUMN · DROP NOT NULL) 아래에서 지우는 행은 돌아오지 않는다. 지우는 쪽으로 정한 것은
-- 실패시키면 배포가 Flyway에서 멈추는데 그 행이 있는지 미리 볼 경로가 없기 때문이다(ADR-0055).

-- 지우기 전에 몇 건인지 남긴다 — 이 로그가 «무엇을 잃었나»의 유일한 기록이다.
DO $$
DECLARE
    orphan_count bigint;
BEGIN
    SELECT count(*) INTO orphan_count FROM mtg_dtl WHERE oper_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE NOTICE 'V24: 운영 건이 없는 안건 %건을 지운다 (ADR-0055)', orphan_count;
    ELSE
        RAISE NOTICE 'V24: 운영 건이 없는 안건이 없다';
    END IF;
END $$;

DELETE FROM mtg_dtl WHERE oper_id IS NULL;

ALTER TABLE mtg_dtl ALTER COLUMN oper_id SET NOT NULL;

ALTER TABLE mtg_dtl DROP COLUMN agnd_nm;
