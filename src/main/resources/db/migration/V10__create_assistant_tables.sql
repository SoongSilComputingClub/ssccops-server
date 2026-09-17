-- ============================================================================
-- V10 — 규정 도우미(RAG) 스키마: vector 확장 · vector_store · rag_doc
-- ============================================================================
-- #396(Epic ssccops#321 · ADR-0028 · ADR-0029)이 만드는 두 테이블이다.
--
-- **스타터가 만들게 두지 않는다.** Spring AI의 PgVector 스타터는 부팅 때 스스로 CREATE TABLE을
-- 할 수 있지만(`spring.ai.vectorstore.pgvector.initialize-schema`) 그 값을 false로 두고 여기에
-- 옮겨 적었다 — dev·prod는 `ddl-auto: validate`이고, 마이그레이션이 모르는 테이블이 생기면
-- «baseline + 마이그레이션이 곧 DB»라는 전제가 깨진다(ssccops#213). 그 전제가 깨지면 다음에
-- 스키마가 어긋났을 때 무엇이 정본인지 물을 자리가 없다.
--
-- ⚠️ **`vector` 확장은 Supabase에서 `extensions` 스키마에 깔린다**(dev·prod 둘 다 v0.8.2 ·
-- ssccops#324에서 2026-09-13 확인). 타입 이름 `vector`가 해석되려면 `search_path`에
-- `extensions`가 있어야 하는데 `postgres` 역할에는 이미 걸려 있다. **전용 DB 역할을 만들게 되면
-- 그 역할에도 같은 설정이 필요하다.** 아래 `CREATE EXTENSION IF NOT EXISTS vector`는 이미 깔린
-- 곳에서는 스키마와 무관하게 아무 일도 하지 않고, 빈 DB(Testcontainers)에서는 `public`에 깐다 —
-- 그래서 `FlywayMigrationValidateTest`는 **이 조건(search_path)을 검증하지 못한다.**
--
-- **인덱스(HNSW·IVFFlat)를 만들지 않는다**(ADR-0028). 768차원이라 pgvector의 인덱스 상한
-- 2,000 안이지만, 청크 수천 개면 순차 스캔이 더 빠르고 Supabase Free의 진짜 제약은 디스크가
-- 아니라 RAM이다 — 거기 닿는 것은 수만 행에 HNSW를 거는 순간이다. 청크 3,000에 닿으면 다시 본다.
-- ============================================================================

-- pgvector. 멱등이며, 이미 다른 스키마(Supabase의 `extensions`)에 있으면 아무 일도 하지 않는다.
CREATE EXTENSION IF NOT EXISTS vector;

-- ----------------------------------------------------------------------------
-- vector_store — 청크 본문 · 메타데이터 · 임베딩
-- ----------------------------------------------------------------------------
-- **테이블명·컬럼명을 Spring AI가 정한다.** 우리 표준단어로 개명하면 프레임워크가 찾지 못하므로
-- 데이터사전에도 그 이름 그대로 등재했다(ssccops#325 — 국문명을 «벡터_저장소»·«콘텐츠»·
-- «메타데이터»·«임베딩»으로 고르면 사전의 수식이 같은 영문 ID를 만들어 준다).
--
-- 아래 정의는 `PgVectorStore`가 initialize-schema=true일 때 실행하는 DDL을 옮긴 것이며 셋이 다르다.
--
--   1. `id uuid DEFAULT uuid_generate_v4()` → **`gen_random_uuid()`**. 앞의 것은 `uuid-ossp`
--      확장을 요구하는데 뒤의 것은 PostgreSQL 13부터 코어에 있다 — 확장 하나를 덜 깐다.
--   2. `metadata json` → **`jsonb`**. 검색 필터가 이 컬럼을 `::jsonb`로 캐스팅해 JSON path로
--      훑으므로(`PgVectorFilterExpressionConverter`) json으로 두면 **행마다** 캐스팅이 붙는다.
--      쓰기는 원래부터 `?::jsonb`이고, 스토어의 스키마 검증은 컬럼 **이름**만 보므로 안전하다.
--   3. `content`·`embedding`에 **NOT NULL**. 사전이 둘을 필수로 등재했고, 둘 중 하나가 비면 그
--      행은 검색에 절대 걸리지 않는 죽은 청크다 — 조용히 쌓이느니 적재가 그 자리에서 실패한다.
--
-- **FK가 없다.** rag_doc 1건이 청크 여러 건을 낳지만 이 테이블은 프레임워크의 것이라 컬럼을
-- 더할 수 없다 — 소유 관계는 `metadata->>'ragDocId'`에 있고, 재색인·삭제는 그 값으로 지운다
-- (`RagChunkStore`). 대가는 고아 청크이며 정리는 규정 도우미 도메인의 책임이다.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."vector_store" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "content" "text" NOT NULL,
    "metadata" "jsonb",
    -- 768차원. 3072이면 pgvector 인덱스 상한 2,000을 넘어 **재적재 없이는 인덱스를 못 건다**
    -- (ADR-0028). 값은 spring.ai.vectorstore.pgvector.dimensions와 같아야 하며, 그쪽을 명시해
    -- 둔 것은 모델 클래스의 dimensions()가 모델 이름 상수표를 먼저 보고 3072을 답하기 때문이다.
    -- 타입 이름을 스키마로 한정하지 않는다 — Supabase는 이 확장을 `extensions` 스키마에 깔고
    -- Testcontainers는 `public`에 깐다. 해석은 search_path가 하며 그 조건은 이 파일 상단에 있다.
    "embedding" "vector"(768) NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM "pg_constraint" WHERE "conname" = 'vector_store_pkey') THEN
        ALTER TABLE ONLY "public"."vector_store"
            ADD CONSTRAINT "vector_store_pkey" PRIMARY KEY ("id");
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- rag_doc — 문서 판본
-- ----------------------------------------------------------------------------
-- 행이 만들어지는 경로는 업로드 API 하나다(ADR-0029) — 레포 커밋도 Gradle 태스크도 적재 경로가
-- 아니다. 원본 파일은 R2에 두고 여기에는 메타데이터만 남으며, 그 참조는 `file_rfrnc`가
-- `trgt_se_cd = 'RAG_DOCUMENT'`로 갖는다(#220 — FK를 걸지 않으므로 스키마 변경이 아니다).
--
-- **`del_dt`가 없다 — 삭제는 하드다**(ADR-0029). 폼(#329)·행사(#347)의 소프트 삭제와 갈리는
-- 것은 그쪽이 «치우기»이고 이쪽은 «잘못 올린 파일을 없었던 것으로 만들기»이기 때문이다. 남길
-- 값이 있는 옛 판본은 `aplcn_stts_cd = 'SUPERSEDED'`가 이미 맡는다.
--
-- **상태가 두 축이다.** 한 컬럼에 겹치면 «색인은 끝났지만 아직 시행 전인 개정안»을 표현할 수
-- 없는데 첫 업로드 대상이 바로 그것이다. 검색 조건은 `INDEXED AND EFFECTIVE` 둘이다.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."rag_doc" (
    "rag_doc_id" bigint NOT NULL,
    -- 판본을 가로지르는 열쇠. 화면에서 바뀌지 않는 값이며 제목·파일명으로 대신하지 않는다 —
    -- form.sys_form_cd가 폼에서 한 일과 같은 자리다(#140). 표준코드 그룹이 아니라 자유 문자열인
    -- 것은 사전에 코드그룹으로 등재하지 않았기 때문이다(ssccops#325).
    "doc_cd" character varying(20) NOT NULL,
    -- 인용 카드에 찍히는 표시명. 기본값은 원본 파일명에서 확장자를 뗀 것이고 운영진이 고칠 수 있다
    "doc_nm" character varying(200) NOT NULL,
    -- STRUCTURED · GENERIC (RagDocumentType). 요청이 신고하지 않고 확장자가 정한다 (ADR-0029)
    "doc_type_cd" character varying(20) NOT NULL,
    -- 같은 doc_cd 안에서 1부터. 새 판본을 올릴 때마다 오른다
    "doc_ver" smallint DEFAULT 1 NOT NULL,
    -- PENDING · INDEXING · INDEXED · FAILED (RagIndexStatus). 사람이 정하는 값이 아니라
    -- 워커가 적는 값이라 수정 API를 열지 않는다
    "indx_stts_cd" character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    -- DRAFT · EFFECTIVE · SUPERSEDED (RagApplyStatus). 업로드는 언제나 DRAFT로 들어온다
    "aplcn_stts_cd" character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    -- 색인 실패 사유. FAILED가 아니면 NULL. 워커는 요청 밖에서 돌아 돌려줄 응답이 없으므로
    -- 이 컬럼이 오류 코드의 자리를 대신한다
    "fail_rsn_cn" "text",
    -- 이 판본이 효력을 갖는 날. **DRAFT는 NULL이 정상이다** — 의결 전 개정안에는 발효일이 없다
    "enfc_bgng_ymd" "date",
    -- 올린 파일의 이름. 목록의 «문서명»(doc_nm)과 달리 바뀌지 않는다
    "orgnl_file_nm" character varying(200) NOT NULL,
    -- 바이트. 상한 10MB는 도메인이 끊는다(서블릿 계층은 더 높게 둬 도메인 오류 코드가 붙게 한다 · #84)
    "file_sz" integer NOT NULL,
    -- 색인 완료 시점에 채운다. 그 전에는 NULL(화면의 «—»)
    "chunk_cnt" integer,
    "indx_bgng_dt" timestamp(6) with time zone,
    "indx_end_dt" timestamp(6) with time zone,
    -- 올린 회원. 요청 본문이 아니라 인증 주체에서 온다(#78) — 커밋 이력이 하던 «누가 언제
    -- 바꿨는가»를 이 컬럼이 대신한다(ADR-0029). **행위자 참조라 ON DELETE CASCADE가 아니다**
    -- (#361 · V9) — 회원 하드 삭제는 이 행이 있으면 409로 막히고, 그 문구는
    -- MemberReferenceConstraints가 준다.
    "rgtr_mbr_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT "rag_doc_doc_type_cd_check"
        CHECK ((("doc_type_cd")::"text" = ANY ((ARRAY[
            'STRUCTURED'::character varying,
            'GENERIC'::character varying])::"text"[]))),
    CONSTRAINT "rag_doc_indx_stts_cd_check"
        CHECK ((("indx_stts_cd")::"text" = ANY ((ARRAY[
            'PENDING'::character varying,
            'INDEXING'::character varying,
            'INDEXED'::character varying,
            'FAILED'::character varying])::"text"[]))),
    CONSTRAINT "rag_doc_aplcn_stts_cd_check"
        CHECK ((("aplcn_stts_cd")::"text" = ANY ((ARRAY[
            'DRAFT'::character varying,
            'EFFECTIVE'::character varying,
            'SUPERSEDED'::character varying])::"text"[])))
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM "information_schema"."columns"
                    WHERE "table_schema" = 'public'
                      AND "table_name" = 'rag_doc'
                      AND "column_name" = 'rag_doc_id'
                      AND "is_identity" = 'YES') THEN
        ALTER TABLE "public"."rag_doc"
            ALTER COLUMN "rag_doc_id"
            ADD GENERATED BY DEFAULT AS IDENTITY (
                SEQUENCE NAME "public"."rag_doc_rag_doc_id_seq"
                START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
            );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM "pg_constraint" WHERE "conname" = 'rag_doc_pkey') THEN
        ALTER TABLE ONLY "public"."rag_doc"
            ADD CONSTRAINT "rag_doc_pkey" PRIMARY KEY ("rag_doc_id");
    END IF;

    -- 같은 문서의 같은 판본은 하나뿐이다. 판본이 겹치지 않는 것은 표시 규칙이 아니라
    -- 식별 규칙이라 DB가 강제한다
    IF NOT EXISTS (SELECT 1 FROM "pg_constraint" WHERE "conname" = 'uk_rag_doc_doc_cd_ver') THEN
        ALTER TABLE ONLY "public"."rag_doc"
            ADD CONSTRAINT "uk_rag_doc_doc_cd_ver" UNIQUE ("doc_cd", "doc_ver");
    END IF;

    IF NOT EXISTS (SELECT 1 FROM "pg_constraint" WHERE "conname" = 'fk_rag_doc_rgtr_mbr') THEN
        ALTER TABLE ONLY "public"."rag_doc"
            ADD CONSTRAINT "fk_rag_doc_rgtr_mbr"
            FOREIGN KEY ("rgtr_mbr_id") REFERENCES "public"."mbr"("mbr_id");
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 시행 중인 판본은 문서당 최대 하나 — 부분 유니크 인덱스
-- ----------------------------------------------------------------------------
-- 조건부라 평범한 UNIQUE로 표현되지 않는다. `@Table`의 UNIQUE로 두면 Hibernate가 조건 없는
-- 제약을 만들어 **같은 문서의 판본이 둘째부터 아예 못 들어온다.**
--
-- **H2(테스트)는 부분 인덱스를 지원하지 않는다.** 그래서 이 규칙은 두 겹이며 — 초안 1건 규칙
-- (uk_form_rspns_hstry_one_draft · #143) · 폼 전속(uk_event_form · #347)과 같은 모양이다 —
-- 애플리케이션 판정(기존 EFFECTIVE를 잠그고 같은 트랜잭션에서 SUPERSEDED로 내린다)이 **유일한
-- 방어선인 환경이 있다.** 그 판정은 적용 전환 API(#401)의 몫이고, 이 인덱스가 PostgreSQL에서
-- 동시 요청을 막는 최종 방어선이다. 인덱스가 실제로 이 모양인지는 FlywayMigrationValidateTest가
-- 본다 — H2에는 없는 제약이라 거기서만 검증된다.
-- ----------------------------------------------------------------------------

CREATE UNIQUE INDEX IF NOT EXISTS "uk_rag_doc_effective"
    ON "public"."rag_doc" USING "btree" ("doc_cd")
    WHERE (("aplcn_stts_cd")::"text" = 'EFFECTIVE'::"text");

-- 그 밖의 인덱스는 만들지 않는다. 이 표는 문서 판본이라 행이 수십 단위이고, 워커가 훑는
-- `indx_stts_cd = 'PENDING'`도 목록 화면의 정렬도 그 크기에서는 순차 스캔이 답이다.
-- FK(rgtr_mbr_id)에 인덱스를 걸지 않는 것도 같은 이유다 — 등록자를 축으로 읽는 질의가 없다
-- (V5가 sub_work_id에 건 것은 그쪽에 그 축이 있었기 때문이다).
