-- test 프로필 전용 — V3 시드가 적는 옛 컬럼을 H2에 되살린다 (#595).
--
-- test는 Flyway를 끄고 V3__seed_reference_data.sql을 엔티티로 만든 H2 스키마에 그대로 돌린다
-- (application-test.yaml의 spring.sql.init.data-locations). 그런데 V3의 sub_work_type INSERT는
-- V25에서 지운 expnd_yn을 컬럼 목록에 적고, V3는 이미 적용된 마이그레이션이라 고칠 수 없다
-- (Flyway 체크섬). 엔티티에 필드가 없으니 H2 테이블에도 그 컬럼이 없어 V3가
-- `Column "EXPND_YN" not found`로 죽고, 스프링 테스트 컨텍스트가 하나도 뜨지 못한다.
--
-- dev·prod·local은 Flyway가 V3를 V25보다 먼저 돌리므로 이 문제가 없다 — test만의 사정이다.
-- 앱은 이 컬럼을 모른다. V3의 INSERT가 통과하도록 자리만 만들어 둔다.
--
-- spring.sql.init.schema-locations로 등록돼 data-locations(V3)보다 먼저 돈다.
-- **시드 파일이 적는 컬럼을 또 지우면 여기에 한 줄 더한다** — 빠뜨리면 같은 오류로 전체가 죽는다.

ALTER TABLE sub_work_type ADD COLUMN IF NOT EXISTS expnd_yn BOOLEAN;
