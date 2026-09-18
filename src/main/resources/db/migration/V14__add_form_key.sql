-- ============================================================================
-- V14 — 공개 폼 주소용 무작위 키 (form.form_key) · ADR-0036 · ssccops#359
-- ============================================================================
-- 공개 폼 주소 /f/{formId}와 익명 미리보기 /public/v1/forms/{formId}/meta의 식별자가 연속
-- 정수라 1부터 훑는 것만으로 접수 중이 아닌 폼의 제목까지 수집된다. 운영진이 UUID 주소를
-- 요청했고, 이미 뿌린 숫자 링크는 살려야 하므로 **form_id를 바꾸지 않고 키 컬럼을 하나 더 둔다**.
--
-- **UUID v4를 DB가 만든다** (gen_random_uuid — V10이 pgcrypto 없이 쓰는 것을 확인한 함수).
-- 엔티티도 저장 전에 채우므로(FormEntity @PrePersist) 둘 중 어느 쪽이 먼저든 값이 있다.
-- 기존 행은 여기서 backfill한다 — NOT NULL을 걸려면 먼저 채워야 하고, 옛 폼도 UUID 주소로
-- 공유될 수 있어야 하기 때문이다.
--
-- UNIQUE 인덱스는 조회 경로이기도 하다 — 공개 폼 API가 이 값으로 폼을 찾는다.
-- `local`(ddl-auto: update)에서는 Hibernate가 먼저 붙일 수 있으므로 IF NOT EXISTS로 둔다.
-- ============================================================================

ALTER TABLE "public"."form"
    ADD COLUMN IF NOT EXISTS "form_key" uuid;

UPDATE "public"."form" SET "form_key" = gen_random_uuid() WHERE "form_key" IS NULL;

ALTER TABLE "public"."form"
    ALTER COLUMN "form_key" SET NOT NULL,
    ALTER COLUMN "form_key" SET DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX IF NOT EXISTS "uk_form_form_key" ON "public"."form" ("form_key");
