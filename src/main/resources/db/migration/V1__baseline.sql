-- ============================================================================
-- V1 — baseline. prod의 현재 스키마다 (2026-09-07 기준)
-- ============================================================================
-- 출처: prod `pg_dump --schema-only`. 사람이 뜬 덤프를 Flyway가 실행할 수 있게 걸러 담았다
-- (원본 406문 → 218문). 무엇을 왜 뺐는지가 아래에 있다.
--
-- **엔티티에서 생성한 DDL이 아니다.** prod는 `ddl-auto: update`로 자라난 DB라 엔티티가 말하는
-- 스키마와 실제가 갈릴 수 있고, 실제로 갈려 있었다 — 리네임이 '새 컬럼 추가'로 처리돼 값이 든
-- 옛 컬럼 옆에 빈 새 컬럼이 쌓였다(ssccops#209 승인 마비 · #212 공유 링크 · #224 회의 안건).
-- 그 넷은 baseline을 뜨기 전에 손으로 정리했다(`document/cleanup-2026-09-07.sql`).
-- baseline은 **DB가 실제로 어떤 모양인가**여야 한다. 엔티티 기준으로 잡으면 첫 validate가 터진다.
--
-- 무엇을 뺐나
--
--   | 뺀 것 | 건수 | 왜 |
--   |---|---|---|
--   | `SET` · `set_config` | 12 | 세션 설정. Flyway는 자기 연결을 쓴다 |
--   | `CREATE EXTENSION` | 4 | `extensions`·`vault` 스키마에 설치된다 — Supabase에만 있다. |
--   |  |  | 이 스키마의 어떤 DEFAULT도 확장 함수를 부르지 않아(전부 IDENTITY·리터럴) 빼도 된다 |
--   | `OWNER TO` | 41 | 역할 이름이 환경마다 다르다 |
--   | `GRANT` · `REVOKE` | 124 | `anon`·`authenticated`·`service_role`은 Supabase 밖에 없다 |
--   | `ALTER DEFAULT PRIVILEGES` | 6 | 위와 같다 |
--   | `ALTER PUBLICATION` | 1 | Supabase realtime 전용 |
--   | `COMMENT ON SCHEMA` | 1 | 소유권이 필요하고 스키마와 무관하다 |
--
-- 남긴 것은 넷이며 그 순서로 재배열했다 — `CREATE TABLE`(40) · IDENTITY 시퀀스(34) ·
-- `ADD CONSTRAINT`(127) · 인덱스(17). 원본의 테이블별 배치를 종류별로 바꾼 것은 FK가 참조하는
-- 테이블이 먼저 만들어지도록 보장하기 위해서다.
--
-- **`shr_lnk`는 여기 없다.** prod는 `main`에서 배포되고 공유 링크(ssccops#200)는 아직
-- `develop`에만 있다. V2가 `CREATE TABLE IF NOT EXISTS`로 만들며, 이미 있는 dev에서는
-- 아무 일도 일어나지 않는다. 두 환경은 그렇게 수렴한다.
--
-- **이 파일을 고치지 말 것.** Flyway가 체크섬으로 검증하므로 한 번 적용된 뒤에 고치면 부팅이
-- 실패한다. 스키마를 바꾸려면 새 마이그레이션을 더한다.
-- ============================================================================

CREATE TABLE IF NOT EXISTS "public"."acdm_actv" (
    "acdm_actv_id" bigint NOT NULL,
    "pscp_max_cnt" integer,
    "pscp_min_cnt" integer,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "goal_cn" "text" NOT NULL,
    "prep_cn" "text",
    "schdl_cn" character varying(500),
    "acdm_actv_stts_cd" character varying(20) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "event_id" bigint NOT NULL,
    "form_rspns_id" bigint NOT NULL,
    "leadr_mbr_id" bigint NOT NULL,
    "prpsr_mbr_id" bigint NOT NULL,
    "acdm_actv_type_cd" character varying(20) NOT NULL,
    CONSTRAINT "acdm_actv_acdm_actv_stts_cd_check" CHECK ((("acdm_actv_stts_cd")::"text" = ANY ((ARRAY['APPROVED'::character varying, 'ONGOING'::character varying, 'COMPLETED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."acdm_actv_aprv" (
    "acdm_actv_aprv_id" bigint NOT NULL,
    "aprv_dt" timestamp(6) with time zone,
    "opnn_cn" "text",
    "acdm_actv_aprv_se_cd" character varying(20) NOT NULL,
    "acdm_actv_aprv_stts_cd" character varying(20) NOT NULL,
    "acdm_actv_id" bigint NOT NULL,
    "autzr_mbr_id" bigint NOT NULL,
    "sesn_id" bigint,
    CONSTRAINT "acdm_actv_aprv_acdm_actv_aprv_se_cd_check" CHECK ((("acdm_actv_aprv_se_cd")::"text" = ANY ((ARRAY['SESSION'::character varying, 'COMPLETION'::character varying])::"text"[]))),
    CONSTRAINT "acdm_actv_aprv_acdm_actv_aprv_stts_cd_check" CHECK ((("acdm_actv_aprv_stts_cd")::"text" = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REVISION_REQUESTED'::character varying, 'REJECTED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."acdm_actv_type" (
    "acdm_actv_type_cd" character varying(20) NOT NULL,
    "use_yn" boolean NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "indct_seqno" integer NOT NULL,
    "type_nm" character varying(50) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."atndc" (
    "atndc_id" bigint NOT NULL,
    "atnd_yn" boolean NOT NULL,
    "event_ptcp_id" bigint NOT NULL,
    "sesn_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."authrt" (
    "authrt_cd" character varying(50) NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "indct_seqno" smallint NOT NULL,
    "authrt_expln" character varying(500),
    "authrt_nm" character varying(50) NOT NULL,
    "sys_yn" boolean NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "up_authrt_cd" character varying(50)
);

CREATE TABLE IF NOT EXISTS "public"."crclm_artcl" (
    "crclm_artcl_id" bigint NOT NULL,
    "plan_ymd" "date",
    "seqno" integer NOT NULL,
    "ttl" character varying(256) NOT NULL,
    "acdm_actv_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."event" (
    "event_id" bigint NOT NULL,
    "event_bgng_dt" timestamp(6) with time zone,
    "mtxt_cn" "text" NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "event_end_dt" timestamp(6) with time zone,
    "ptcp_lmt_cnt" integer,
    "plc_nm" character varying(100),
    "event_stts_cd" character varying(20) NOT NULL,
    "thmb_url_addr" character varying(200),
    "event_ttl" character varying(256) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "event_clsf_cd" character varying(20) NOT NULL,
    "creatr_mbr_id" bigint NOT NULL,
    "form_id" bigint,
    CONSTRAINT "event_event_stts_cd_check" CHECK ((("event_stts_cd")::"text" = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'ARCHIVED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."event_clsf" (
    "event_clsf_cd" character varying(20) NOT NULL,
    "indct_seqno" integer NOT NULL,
    "event_clsf_nm" character varying(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."event_ptcp" (
    "event_ptcp_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "ptcp_stts_cd" character varying(20) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "event_id" bigint NOT NULL,
    "form_rspns_id" bigint,
    "mbr_id" bigint NOT NULL,
    "rgtr_mbr_id" bigint NOT NULL,
    CONSTRAINT "event_ptcp_ptcp_stts_cd_check" CHECK ((("ptcp_stts_cd")::"text" = ANY ((ARRAY['CONFIRMED'::character varying, 'WAITLISTED'::character varying, 'CANCELLED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."example_entity" (
    "id" bigint NOT NULL,
    "content" character varying(2000),
    "created_date" timestamp(6) without time zone,
    "status" character varying(255) NOT NULL,
    "title" character varying(100) NOT NULL,
    "updated_date" timestamp(6) without time zone,
    CONSTRAINT "example_entity_status_check" CHECK ((("status")::"text" = ANY ((ARRAY['ACTIVE'::character varying, 'DELETED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."file_rfrnc" (
    "file_rfrnc_id" bigint NOT NULL,
    "file_url_addr" character varying(255) NOT NULL,
    "trgt_id" bigint NOT NULL,
    "trgt_se_cd" character varying(20) NOT NULL,
    CONSTRAINT "file_rfrnc_trgt_se_cd_check" CHECK ((("trgt_se_cd")::"text" = 'SESSION'::"text"))
);

CREATE TABLE IF NOT EXISTS "public"."form" (
    "form_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "qitem_cpst_cn" "jsonb" NOT NULL,
    "rcpt_bgng_dt" timestamp(6) with time zone,
    "rcpt_end_dt" timestamp(6) with time zone,
    "form_stts_cd" character varying(20) NOT NULL,
    "form_ttl_nm" character varying(200) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "creatr_mbr_id" bigint NOT NULL,
    "mltpl_rspns_yn" boolean DEFAULT false NOT NULL,
    "qitem_ver" integer DEFAULT 1 NOT NULL,
    "sys_yn" boolean DEFAULT false NOT NULL,
    "sys_form_cd" character varying(50),
    CONSTRAINT "form_form_stts_cd_check" CHECK ((("form_stts_cd")::"text" = ANY ((ARRAY['DRAFT'::character varying, 'OPEN'::character varying, 'CLOSED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."form_lbl" (
    "form_lbl_id" bigint NOT NULL,
    "use_yn" boolean NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "lbl_nm" character varying(50) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."form_lbl_rel" (
    "form_lbl_rel_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "form_id" bigint NOT NULL,
    "form_lbl_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."form_qitem_hstry" (
    "form_qitem_hstry_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "qitem_cpst_cn" "jsonb" NOT NULL,
    "qitem_ver" integer NOT NULL,
    "chnrg_mbr_id" bigint,
    "form_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."form_rspns_hstry" (
    "form_rspns_id" bigint NOT NULL,
    "rspns_cn" "jsonb" NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "rspns_stts_cd" character varying(20) NOT NULL,
    "sbmsn_dt" timestamp(6) with time zone,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "form_id" bigint NOT NULL,
    "mbr_id" bigint NOT NULL,
    "qitem_ver" integer DEFAULT 1 NOT NULL,
    "rspns_seq" integer DEFAULT 1 NOT NULL,
    "sbmsn_seq" integer DEFAULT 1 NOT NULL,
    CONSTRAINT "form_rspns_hstry_rspns_stts_cd_check" CHECK ((("rspns_stts_cd")::"text" = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'CHANGES_REQUESTED'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."form_rspns_rvw_hstry" (
    "form_rspns_rvw_hstry_id" bigint NOT NULL,
    "rvw_prcs_se_cd" character varying(20) NOT NULL,
    "rvw_opnn_cn" "text",
    "prcs_dt" timestamp(6) with time zone NOT NULL,
    "sbmsn_seq" integer NOT NULL,
    "prcs_mbr_id" bigint NOT NULL,
    "form_rspns_id" bigint NOT NULL,
    CONSTRAINT "form_rspns_rvw_hstry_rvw_prcs_se_cd_check" CHECK ((("rvw_prcs_se_cd")::"text" = ANY ((ARRAY['SUBMIT'::character varying, 'ACCEPT'::character varying, 'REQUEST_CHANGES'::character varying, 'REJECT'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."form_tmpl" (
    "form_tmpl_id" bigint NOT NULL,
    "use_yn" boolean NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "tmpl_expln" character varying(500),
    "tmpl_nm" character varying(200) NOT NULL,
    "qitem_cpst_cn" "jsonb" NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "creatr_mbr_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."mbr" (
    "mbr_id" bigint NOT NULL,
    "scyr_no" integer,
    "auth_user_id" "uuid",
    "crt_dt" timestamp(6) with time zone,
    "scsbjt_nm" character varying(100),
    "eml" character varying(255),
    "gen_no" integer NOT NULL,
    "sys_join_ymd" "date" NOT NULL,
    "mbr_nm" character varying(50) NOT NULL,
    "telno" character varying(20),
    "stdnt_no" character varying(20),
    "mdfcn_dt" timestamp(6) with time zone,
    "mbr_grd_cd" character varying(20) NOT NULL,
    "mbr_stts_cd" character varying(20) NOT NULL,
    "clb_join_mm_no" integer,
    "clb_join_yr_no" integer
);

CREATE TABLE IF NOT EXISTS "public"."mbr_chg_hstry" (
    "mbr_chg_hstry_id" bigint NOT NULL,
    "chg_artcl_cd" character varying(20) NOT NULL,
    "crt_dt" timestamp(6) with time zone,
    "aftr_cn" character varying(500),
    "bfr_cn" character varying(500),
    "chnrg_mbr_id" bigint NOT NULL,
    "mbr_id" bigint NOT NULL,
    CONSTRAINT "mbr_chg_hstry_chg_artcl_cd_check" CHECK ((("chg_artcl_cd")::"text" = ANY ((ARRAY['STUDENT_NUMBER'::character varying, 'GENERATION_NUMBER'::character varying, 'CLUB_JOIN_YEAR'::character varying, 'CLUB_JOIN_MONTH'::character varying, 'MEMBER_NAME'::character varying, 'DEPARTMENT_NAME'::character varying, 'ACADEMIC_YEAR'::character varying, 'PHONE_NUMBER'::character varying, 'EMAIL'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."mbr_grd" (
    "mbr_grd_cd" character varying(20) NOT NULL,
    "indct_seqno" integer NOT NULL,
    "mbr_grd_nm" character varying(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."mbr_grd_hstry" (
    "mbr_grd_hstry_id" bigint NOT NULL,
    "grd_aplcn_ymd" "date" NOT NULL,
    "grd_chg_rsn_cn" character varying(500),
    "crt_dt" timestamp(6) with time zone,
    "chnrg_mbr_id" bigint,
    "mbr_id" bigint NOT NULL,
    "aftr_mbr_grd_cd" character varying(20) NOT NULL,
    "bfr_mbr_grd_cd" character varying(20)
);

CREATE TABLE IF NOT EXISTS "public"."mbr_role_rel" (
    "mbr_role_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone,
    "rprs_role_yn" boolean NOT NULL,
    "role_end_ymd" "date",
    "role_bgng_ymd" "date" NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone,
    "mbr_id" bigint NOT NULL,
    "role_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."mbr_stts" (
    "mbr_stts_cd" character varying(20) NOT NULL,
    "indct_seqno" integer NOT NULL,
    "mbr_stts_nm" character varying(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."mbr_stts_hstry" (
    "mbr_stts_hstry_id" bigint NOT NULL,
    "stts_aplcn_ymd" "date" NOT NULL,
    "stts_chg_rsn_cn" character varying(500),
    "crt_dt" timestamp(6) with time zone,
    "stts_end_prnmnt_ymd" "date",
    "chnrg_mbr_id" bigint,
    "mbr_id" bigint NOT NULL,
    "aftr_mbr_stts_cd" character varying(20) NOT NULL,
    "bfr_mbr_stts_cd" character varying(20)
);

CREATE TABLE IF NOT EXISTS "public"."mtg" (
    "mtg_id" bigint NOT NULL,
    "atnd_trgt_cd" character varying(20),
    "otsd_mtg_dtl_cn" "text",
    "insd_mtg_dtl_cn" "text",
    "mtg_plc_nm" character varying(100),
    "mtg_se_cd" character varying(20),
    "mtg_stts_cd" character varying(20),
    "oper_id" bigint NOT NULL,
    "mtg_rbprsn_id" bigint NOT NULL,
    CONSTRAINT "mtg_atnd_trgt_cd_check" CHECK ((("atnd_trgt_cd")::"text" = ANY ((ARRAY['ALL'::character varying, 'DIRECTORS'::character varying, 'AD_HOC'::character varying])::"text"[]))),
    CONSTRAINT "mtg_mtg_se_cd_check" CHECK ((("mtg_se_cd")::"text" = ANY ((ARRAY['REGULAR'::character varying, 'TOPIC'::character varying])::"text"[]))),
    CONSTRAINT "mtg_mtg_stts_cd_check" CHECK ((("mtg_stts_cd")::"text" = ANY ((ARRAY['SCHEDULED'::character varying, 'IN_PROGRESS'::character varying, 'MINUTES'::character varying, 'CLOSED'::character varying, 'CANCELED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."mtg_dtl" (
    "mtg_dtl_id" bigint NOT NULL,
    "agnd_nm" character varying(100),
    "agnd_seq" integer,
    "agnd_cn" "text",
    "rslt_cn" "text",
    "mtg_id" bigint NOT NULL,
    "oper_id" bigint,
    "prsnr_id" bigint NOT NULL,
    "agnd_prcs_se_cd" character varying(20),
    CONSTRAINT "mtg_dtl_agnd_prcs_se_cd_check" CHECK ((("agnd_prcs_se_cd")::"text" = ANY ((ARRAY['PENDING'::character varying, 'HOLD'::character varying, 'CLOSED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."oper" (
    "oper_id" bigint NOT NULL,
    "bgng_dt" timestamp(6) with time zone,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "del_dt" timestamp(6) with time zone,
    "end_dt" timestamp(6) with time zone,
    "oper_type_cd" character varying(20) NOT NULL,
    "prrty_rnk_cd" character varying(20) NOT NULL,
    "oper_ttl" character varying(256) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "pic_id" bigint NOT NULL,
    "oper_rgtr_id" bigint,
    CONSTRAINT "oper_oper_type_cd_check" CHECK ((("oper_type_cd")::"text" = ANY ((ARRAY['WORK'::character varying, 'SUB_WORK'::character varying, 'MEETING'::character varying])::"text"[]))),
    CONSTRAINT "oper_prrty_rnk_cd_check" CHECK ((("prrty_rnk_cd")::"text" = ANY ((ARRAY['HIGH'::character varying, 'NORMAL'::character varying, 'LOW'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."role" (
    "role_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone,
    "indct_seqno" integer NOT NULL,
    "role_nm" character varying(100),
    "mdfcn_dt" timestamp(6) with time zone,
    "role_clsf_cd" character varying(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."role_authrt_rel" (
    "role_authrt_id" bigint NOT NULL,
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "authrt_cd" character varying(50) NOT NULL,
    "role_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."role_clsf" (
    "role_clsf_cd" character varying(20) NOT NULL,
    "indct_seqno" integer NOT NULL,
    "role_clsf_nm" character varying(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."sesn" (
    "sesn_id" bigint NOT NULL,
    "prgrs_cn" "text" NOT NULL,
    "ntc_cn" "text",
    "actl_ymd" "date" NOT NULL,
    "sesn_stts_cd" character varying(20) NOT NULL,
    "crclm_artcl_id" bigint NOT NULL,
    "rgtr_mbr_id" bigint NOT NULL,
    CONSTRAINT "sesn_sesn_stts_cd_check" CHECK ((("sesn_stts_cd")::"text" = ANY ((ARRAY['NOT_SUBMITTED'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'REVISION_REQUESTED'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."sub_work" (
    "sub_work_id" bigint NOT NULL,
    "aprv_stts_cd" character varying(20) NOT NULL,
    "cmptn_dt" timestamp(6) with time zone,
    "cmptn_crtr_cn" "text",
    "work_cn" "text",
    "dly_yn" boolean NOT NULL,
    "ddln_dt" timestamp(6) with time zone,
    "otsd_url_addr" character varying(200),
    "sub_work_ttl" character varying(256) NOT NULL,
    "work_stts_cd" character varying(20) NOT NULL,
    "oper_id" bigint NOT NULL,
    "sub_work_type_id" bigint NOT NULL,
    "work_id" bigint NOT NULL,
    CONSTRAINT "sub_work_aprv_stts_cd_check" CHECK ((("aprv_stts_cd")::"text" = ANY ((ARRAY['NOT_REQUIRED'::character varying, 'PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'REAPPROVAL_REQUIRED'::character varying])::"text"[]))),
    CONSTRAINT "sub_work_work_stts_cd_check" CHECK ((("work_stts_cd")::"text" = ANY ((ARRAY['PLANNING'::character varying, 'IN_PROGRESS'::character varying, 'REVIEW'::character varying, 'DONE'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."sub_work_aprv" (
    "sub_work_aprv_id" bigint NOT NULL,
    "aprv_stp" character varying(20),
    "sub_work_aprv_dt" timestamp(6) with time zone NOT NULL,
    "emrg_se_cd" character varying(20),
    "emrg_rsn" "text",
    "epfc_aprv_term_ymd" "date",
    "rgtr_aprv_yn" boolean NOT NULL,
    "mbr_id" bigint NOT NULL,
    "sub_work_stts_hstry_id" bigint,
    "sub_work_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."sub_work_aprv_vote" (
    "aprv_vote_id" bigint NOT NULL,
    "agre_yn" boolean,
    "aprv_seqno" integer NOT NULL,
    "vote_dt" timestamp(6) with time zone NOT NULL,
    "sub_work_id" bigint NOT NULL,
    "mbr_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."sub_work_chck_list" (
    "sub_work_chck_list_id" bigint NOT NULL,
    "chck_artcl_cn" "text" NOT NULL,
    "cmptn_yn" boolean NOT NULL,
    "sort_seq" integer NOT NULL,
    "sub_work_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."sub_work_rjct" (
    "sub_work_rjct_id" bigint NOT NULL,
    "rjct_rsn" "text" NOT NULL,
    "rjct_dt" timestamp(6) with time zone NOT NULL,
    "mbr_id" bigint NOT NULL,
    "sub_work_stts_hstry_id" bigint,
    "sub_work_id" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."sub_work_stts_hstry" (
    "sub_work_stts_hstry_id" bigint NOT NULL,
    "chg_rsn" "text",
    "chg_dt" timestamp(6) with time zone NOT NULL,
    "aftr_work_stts_cd" character varying(20) NOT NULL,
    "bfr_work_stts_cd" character varying(20) NOT NULL,
    "prfmr_id" bigint NOT NULL,
    "sub_work_id" bigint NOT NULL,
    CONSTRAINT "sub_work_stts_hstry_aftr_work_stts_cd_check" CHECK ((("aftr_work_stts_cd")::"text" = ANY ((ARRAY['PLANNING'::character varying, 'IN_PROGRESS'::character varying, 'REVIEW'::character varying, 'DONE'::character varying])::"text"[]))),
    CONSTRAINT "sub_work_stts_hstry_bfr_work_stts_cd_check" CHECK ((("bfr_work_stts_cd")::"text" = ANY ((ARRAY['PLANNING'::character varying, 'IN_PROGRESS'::character varying, 'REVIEW'::character varying, 'DONE'::character varying])::"text"[])))
);

CREATE TABLE IF NOT EXISTS "public"."sub_work_type" (
    "sub_work_type_id" bigint NOT NULL,
    "use_yn" boolean NOT NULL,
    "aprv_need_yn" boolean NOT NULL,
    "cmptn_chck_artcl_cn" "text",
    "crt_dt" timestamp(6) with time zone NOT NULL,
    "crtr_amt" numeric(15,0),
    "expnd_yn" boolean NOT NULL,
    "min_need_agre_cnt" integer,
    "min_need_agre_cnt_yn" boolean NOT NULL,
    "type_nm" character varying(100) NOT NULL,
    "mdfcn_dt" timestamp(6) with time zone NOT NULL,
    "autzr_authrt_cd" character varying(50)
);

CREATE TABLE IF NOT EXISTS "public"."work" (
    "work_id" bigint NOT NULL,
    "grvw_cn" "text",
    "work_prgrs_rt" numeric(5,2) NOT NULL,
    "work_stts_cd" character varying(20) NOT NULL,
    "work_type_cd" character varying(20) NOT NULL,
    "oper_id" bigint NOT NULL,
    CONSTRAINT "work_work_stts_cd_check" CHECK ((("work_stts_cd")::"text" = ANY ((ARRAY['PLANNING'::character varying, 'IN_PROGRESS'::character varying, 'REVIEW'::character varying, 'DONE'::character varying])::"text"[]))),
    CONSTRAINT "work_work_type_cd_check" CHECK ((("work_type_cd")::"text" = ANY ((ARRAY['EVENT'::character varying, 'REGULAR'::character varying, 'ROUTINE'::character varying])::"text"[])))
);

ALTER TABLE "public"."acdm_actv" ALTER COLUMN "acdm_actv_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."acdm_actv_acdm_actv_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."acdm_actv_aprv" ALTER COLUMN "acdm_actv_aprv_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."acdm_actv_aprv_acdm_actv_aprv_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."atndc" ALTER COLUMN "atndc_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."atndc_atndc_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."crclm_artcl" ALTER COLUMN "crclm_artcl_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."crclm_artcl_crclm_artcl_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."event" ALTER COLUMN "event_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."event_event_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."event_ptcp" ALTER COLUMN "event_ptcp_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."event_ptcp_event_ptcp_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."example_entity" ALTER COLUMN "id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."example_entity_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."file_rfrnc" ALTER COLUMN "file_rfrnc_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."file_rfrnc_file_rfrnc_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."form" ALTER COLUMN "form_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."form_form_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."form_lbl" ALTER COLUMN "form_lbl_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."form_lbl_form_lbl_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."form_lbl_rel" ALTER COLUMN "form_lbl_rel_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."form_lbl_rel_form_lbl_rel_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."form_qitem_hstry" ALTER COLUMN "form_qitem_hstry_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."form_qitem_hstry_form_qitem_hstry_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."form_rspns_hstry" ALTER COLUMN "form_rspns_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."form_rspns_hstry_form_rspns_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."form_rspns_rvw_hstry" ALTER COLUMN "form_rspns_rvw_hstry_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."form_rspns_rvw_hstry_form_rspns_rvw_hstry_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."form_tmpl" ALTER COLUMN "form_tmpl_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."form_tmpl_form_tmpl_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."mbr_chg_hstry" ALTER COLUMN "mbr_chg_hstry_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."mbr_chg_hstry_mbr_chg_hstry_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."mbr_grd_hstry" ALTER COLUMN "mbr_grd_hstry_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."mbr_grd_hstry_mbr_grd_hstry_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."mbr" ALTER COLUMN "mbr_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."mbr_mbr_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."mbr_role_rel" ALTER COLUMN "mbr_role_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."mbr_role_rel_mbr_role_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."mbr_stts_hstry" ALTER COLUMN "mbr_stts_hstry_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."mbr_stts_hstry_mbr_stts_hstry_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."mtg_dtl" ALTER COLUMN "mtg_dtl_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."mtg_dtl_mtg_dtl_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."mtg" ALTER COLUMN "mtg_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."mtg_mtg_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."oper" ALTER COLUMN "oper_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."oper_oper_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."role_authrt_rel" ALTER COLUMN "role_authrt_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."role_authrt_rel_role_authrt_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."role" ALTER COLUMN "role_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."role_role_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sesn" ALTER COLUMN "sesn_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sesn_sesn_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sub_work_aprv" ALTER COLUMN "sub_work_aprv_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sub_work_aprv_sub_work_aprv_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sub_work_aprv_vote" ALTER COLUMN "aprv_vote_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sub_work_aprv_vote_aprv_vote_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sub_work_chck_list" ALTER COLUMN "sub_work_chck_list_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sub_work_chck_list_sub_work_chck_list_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sub_work_rjct" ALTER COLUMN "sub_work_rjct_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sub_work_rjct_sub_work_rjct_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sub_work_stts_hstry" ALTER COLUMN "sub_work_stts_hstry_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sub_work_stts_hstry_sub_work_stts_hstry_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sub_work" ALTER COLUMN "sub_work_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sub_work_sub_work_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."sub_work_type" ALTER COLUMN "sub_work_type_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."sub_work_type_sub_work_type_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE "public"."work" ALTER COLUMN "work_id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."work_work_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY "public"."acdm_actv_aprv"
    ADD CONSTRAINT "acdm_actv_aprv_pkey" PRIMARY KEY ("acdm_actv_aprv_id");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "acdm_actv_pkey" PRIMARY KEY ("acdm_actv_id");

ALTER TABLE ONLY "public"."acdm_actv_type"
    ADD CONSTRAINT "acdm_actv_type_pkey" PRIMARY KEY ("acdm_actv_type_cd");

ALTER TABLE ONLY "public"."atndc"
    ADD CONSTRAINT "atndc_pkey" PRIMARY KEY ("atndc_id");

ALTER TABLE ONLY "public"."authrt"
    ADD CONSTRAINT "authrt_pkey" PRIMARY KEY ("authrt_cd");

ALTER TABLE ONLY "public"."crclm_artcl"
    ADD CONSTRAINT "crclm_artcl_pkey" PRIMARY KEY ("crclm_artcl_id");

ALTER TABLE ONLY "public"."event_clsf"
    ADD CONSTRAINT "event_clsf_pkey" PRIMARY KEY ("event_clsf_cd");

ALTER TABLE ONLY "public"."event"
    ADD CONSTRAINT "event_pkey" PRIMARY KEY ("event_id");

ALTER TABLE ONLY "public"."event_ptcp"
    ADD CONSTRAINT "event_ptcp_pkey" PRIMARY KEY ("event_ptcp_id");

ALTER TABLE ONLY "public"."example_entity"
    ADD CONSTRAINT "example_entity_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."file_rfrnc"
    ADD CONSTRAINT "file_rfrnc_pkey" PRIMARY KEY ("file_rfrnc_id");

ALTER TABLE ONLY "public"."form_lbl"
    ADD CONSTRAINT "form_lbl_pkey" PRIMARY KEY ("form_lbl_id");

ALTER TABLE ONLY "public"."form_lbl_rel"
    ADD CONSTRAINT "form_lbl_rel_pkey" PRIMARY KEY ("form_lbl_rel_id");

ALTER TABLE ONLY "public"."form"
    ADD CONSTRAINT "form_pkey" PRIMARY KEY ("form_id");

ALTER TABLE ONLY "public"."form_qitem_hstry"
    ADD CONSTRAINT "form_qitem_hstry_pkey" PRIMARY KEY ("form_qitem_hstry_id");

ALTER TABLE ONLY "public"."form_rspns_hstry"
    ADD CONSTRAINT "form_rspns_hstry_pkey" PRIMARY KEY ("form_rspns_id");

ALTER TABLE ONLY "public"."form_rspns_rvw_hstry"
    ADD CONSTRAINT "form_rspns_rvw_hstry_pkey" PRIMARY KEY ("form_rspns_rvw_hstry_id");

ALTER TABLE ONLY "public"."form_tmpl"
    ADD CONSTRAINT "form_tmpl_pkey" PRIMARY KEY ("form_tmpl_id");

ALTER TABLE ONLY "public"."mbr_chg_hstry"
    ADD CONSTRAINT "mbr_chg_hstry_pkey" PRIMARY KEY ("mbr_chg_hstry_id");

ALTER TABLE ONLY "public"."mbr_grd_hstry"
    ADD CONSTRAINT "mbr_grd_hstry_pkey" PRIMARY KEY ("mbr_grd_hstry_id");

ALTER TABLE ONLY "public"."mbr_grd"
    ADD CONSTRAINT "mbr_grd_pkey" PRIMARY KEY ("mbr_grd_cd");

ALTER TABLE ONLY "public"."mbr"
    ADD CONSTRAINT "mbr_pkey" PRIMARY KEY ("mbr_id");

ALTER TABLE ONLY "public"."mbr_role_rel"
    ADD CONSTRAINT "mbr_role_rel_pkey" PRIMARY KEY ("mbr_role_id");

ALTER TABLE ONLY "public"."mbr_stts_hstry"
    ADD CONSTRAINT "mbr_stts_hstry_pkey" PRIMARY KEY ("mbr_stts_hstry_id");

ALTER TABLE ONLY "public"."mbr_stts"
    ADD CONSTRAINT "mbr_stts_pkey" PRIMARY KEY ("mbr_stts_cd");

ALTER TABLE ONLY "public"."mtg_dtl"
    ADD CONSTRAINT "mtg_dtl_pkey" PRIMARY KEY ("mtg_dtl_id");

ALTER TABLE ONLY "public"."mtg"
    ADD CONSTRAINT "mtg_pkey" PRIMARY KEY ("mtg_id");

ALTER TABLE ONLY "public"."oper"
    ADD CONSTRAINT "oper_pkey" PRIMARY KEY ("oper_id");

ALTER TABLE ONLY "public"."role_authrt_rel"
    ADD CONSTRAINT "role_authrt_rel_pkey" PRIMARY KEY ("role_authrt_id");

ALTER TABLE ONLY "public"."role_clsf"
    ADD CONSTRAINT "role_clsf_pkey" PRIMARY KEY ("role_clsf_cd");

ALTER TABLE ONLY "public"."role"
    ADD CONSTRAINT "role_pkey" PRIMARY KEY ("role_id");

ALTER TABLE ONLY "public"."sesn"
    ADD CONSTRAINT "sesn_pkey" PRIMARY KEY ("sesn_id");

ALTER TABLE ONLY "public"."sub_work_aprv"
    ADD CONSTRAINT "sub_work_aprv_pkey" PRIMARY KEY ("sub_work_aprv_id");

ALTER TABLE ONLY "public"."sub_work_aprv_vote"
    ADD CONSTRAINT "sub_work_aprv_vote_pkey" PRIMARY KEY ("aprv_vote_id");

ALTER TABLE ONLY "public"."sub_work_chck_list"
    ADD CONSTRAINT "sub_work_chck_list_pkey" PRIMARY KEY ("sub_work_chck_list_id");

ALTER TABLE ONLY "public"."sub_work"
    ADD CONSTRAINT "sub_work_pkey" PRIMARY KEY ("sub_work_id");

ALTER TABLE ONLY "public"."sub_work_rjct"
    ADD CONSTRAINT "sub_work_rjct_pkey" PRIMARY KEY ("sub_work_rjct_id");

ALTER TABLE ONLY "public"."sub_work_stts_hstry"
    ADD CONSTRAINT "sub_work_stts_hstry_pkey" PRIMARY KEY ("sub_work_stts_hstry_id");

ALTER TABLE ONLY "public"."sub_work_type"
    ADD CONSTRAINT "sub_work_type_pkey" PRIMARY KEY ("sub_work_type_id");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "uk_acdm_actv_event" UNIQUE ("event_id");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "uk_acdm_actv_form_rspns" UNIQUE ("form_rspns_id");

ALTER TABLE ONLY "public"."atndc"
    ADD CONSTRAINT "uk_atndc_sesn_ptcp" UNIQUE ("sesn_id", "event_ptcp_id");

ALTER TABLE ONLY "public"."event"
    ADD CONSTRAINT "uk_event_form" UNIQUE ("form_id");

ALTER TABLE ONLY "public"."event_ptcp"
    ADD CONSTRAINT "uk_event_ptcp_event_member" UNIQUE ("event_id", "mbr_id");

ALTER TABLE ONLY "public"."form_lbl"
    ADD CONSTRAINT "uk_form_lbl_name" UNIQUE ("lbl_nm");

ALTER TABLE ONLY "public"."form_lbl_rel"
    ADD CONSTRAINT "uk_form_lbl_rel_form_label" UNIQUE ("form_id", "form_lbl_id");

ALTER TABLE ONLY "public"."form_qitem_hstry"
    ADD CONSTRAINT "uk_form_qitem_hstry_form_version" UNIQUE ("form_id", "qitem_ver");

ALTER TABLE ONLY "public"."form_rspns_hstry"
    ADD CONSTRAINT "uk_form_rspns_hstry_form_member_seq" UNIQUE ("form_id", "mbr_id", "rspns_seq");

ALTER TABLE ONLY "public"."form"
    ADD CONSTRAINT "uk_form_sys_form_cd" UNIQUE ("sys_form_cd");

ALTER TABLE ONLY "public"."mbr"
    ADD CONSTRAINT "uk_mbr_auth_user_id" UNIQUE ("auth_user_id");

ALTER TABLE ONLY "public"."mbr"
    ADD CONSTRAINT "uk_mbr_student_number" UNIQUE ("stdnt_no");

ALTER TABLE ONLY "public"."mtg"
    ADD CONSTRAINT "uk_mtg_oper_id" UNIQUE ("oper_id");

ALTER TABLE ONLY "public"."role_authrt_rel"
    ADD CONSTRAINT "uk_role_authrt_rel_role_authority" UNIQUE ("role_id", "authrt_cd");

ALTER TABLE ONLY "public"."sesn"
    ADD CONSTRAINT "uk_sesn_crclm_artcl" UNIQUE ("crclm_artcl_id");

ALTER TABLE ONLY "public"."sub_work_aprv_vote"
    ADD CONSTRAINT "uk_sub_work_aprv_vote" UNIQUE ("sub_work_id", "aprv_seqno", "mbr_id");

ALTER TABLE ONLY "public"."sub_work_type"
    ADD CONSTRAINT "uk_sub_work_type_name" UNIQUE ("type_nm");

ALTER TABLE ONLY "public"."work"
    ADD CONSTRAINT "work_pkey" PRIMARY KEY ("work_id");

ALTER TABLE ONLY "public"."sub_work_aprv"
    ADD CONSTRAINT "fk27ofrsdwuss5ecdkl8uwwgvdb" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."sub_work_stts_hstry"
    ADD CONSTRAINT "fk2sofgwcglk06ivbgcg7ejpx9g" FOREIGN KEY ("prfmr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."event_ptcp"
    ADD CONSTRAINT "fk2yfmj6dd4h4l2phh0smwi0kb9" FOREIGN KEY ("form_rspns_id") REFERENCES "public"."form_rspns_hstry"("form_rspns_id");

ALTER TABLE ONLY "public"."acdm_actv_aprv"
    ADD CONSTRAINT "fk3gvikxf1jwnqrmclbhijmrgov" FOREIGN KEY ("acdm_actv_id") REFERENCES "public"."acdm_actv"("acdm_actv_id");

ALTER TABLE ONLY "public"."sub_work_rjct"
    ADD CONSTRAINT "fk4b4e430r244228fjq9chsout8" FOREIGN KEY ("sub_work_stts_hstry_id") REFERENCES "public"."sub_work_stts_hstry"("sub_work_stts_hstry_id");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "fk4qc86y2528bo70i44yye2ufld" FOREIGN KEY ("leadr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."form_rspns_rvw_hstry"
    ADD CONSTRAINT "fk51eb6sl115o38xmcux4l66ne9" FOREIGN KEY ("form_rspns_id") REFERENCES "public"."form_rspns_hstry"("form_rspns_id");

ALTER TABLE ONLY "public"."crclm_artcl"
    ADD CONSTRAINT "fk5hr5c40fpnkwpsqh6jixexf1o" FOREIGN KEY ("acdm_actv_id") REFERENCES "public"."acdm_actv"("acdm_actv_id");

ALTER TABLE ONLY "public"."sub_work"
    ADD CONSTRAINT "fk5l6hyc2tt1pwfeexkdrq99vgt" FOREIGN KEY ("work_id") REFERENCES "public"."work"("work_id");

ALTER TABLE ONLY "public"."acdm_actv_aprv"
    ADD CONSTRAINT "fk5ow6bmkqtrxb5ugl9ppkl96i8" FOREIGN KEY ("autzr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "fk63ea2pc4ndj60x7eudd79qhat" FOREIGN KEY ("event_id") REFERENCES "public"."event"("event_id");

ALTER TABLE ONLY "public"."mbr"
    ADD CONSTRAINT "fk7snsn4rllyhotwld5bli8o0ko" FOREIGN KEY ("mbr_grd_cd") REFERENCES "public"."mbr_grd"("mbr_grd_cd");

ALTER TABLE ONLY "public"."event"
    ADD CONSTRAINT "fk84eqosmd2683rwqk9nte94p4g" FOREIGN KEY ("form_id") REFERENCES "public"."form"("form_id");

ALTER TABLE ONLY "public"."mbr"
    ADD CONSTRAINT "fk9y4us2lqfqwh996o6gktnxsif" FOREIGN KEY ("mbr_stts_cd") REFERENCES "public"."mbr_stts"("mbr_stts_cd");

ALTER TABLE ONLY "public"."mbr_stts_hstry"
    ADD CONSTRAINT "fka1aso9jn3i6nhqoh3mg1hiyip" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mbr_role_rel"
    ADD CONSTRAINT "fka229oo73t8twd2by22omue4jt" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mbr_grd_hstry"
    ADD CONSTRAINT "fkb6qwbj5hq3najqatd14rb4kdd" FOREIGN KEY ("chnrg_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mbr_grd_hstry"
    ADD CONSTRAINT "fkba1nfwkuugge8t3qm741t9jpi" FOREIGN KEY ("aftr_mbr_grd_cd") REFERENCES "public"."mbr_grd"("mbr_grd_cd");

ALTER TABLE ONLY "public"."event_ptcp"
    ADD CONSTRAINT "fkbmu3kxygb9o8un4b8nuwi7n03" FOREIGN KEY ("event_id") REFERENCES "public"."event"("event_id");

ALTER TABLE ONLY "public"."sub_work_chck_list"
    ADD CONSTRAINT "fkbo95hvdkg5rtxogkixhq4anwd" FOREIGN KEY ("sub_work_id") REFERENCES "public"."sub_work"("sub_work_id");

ALTER TABLE ONLY "public"."oper"
    ADD CONSTRAINT "fkbomrgnpyh5747r94hsrahg6rb" FOREIGN KEY ("oper_rgtr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."form_rspns_hstry"
    ADD CONSTRAINT "fkbtp6dhj8bntedf10yc81a0620" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mbr_grd_hstry"
    ADD CONSTRAINT "fkbvc1xewypay4fu9uka42ikhg2" FOREIGN KEY ("bfr_mbr_grd_cd") REFERENCES "public"."mbr_grd"("mbr_grd_cd");

ALTER TABLE ONLY "public"."mbr_chg_hstry"
    ADD CONSTRAINT "fkdq0mb3w1ylvpmkrl8noiid64f" FOREIGN KEY ("chnrg_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."acdm_actv_aprv"
    ADD CONSTRAINT "fkdv47rh9apvrktr3i2s6o6jmge" FOREIGN KEY ("sesn_id") REFERENCES "public"."sesn"("sesn_id");

ALTER TABLE ONLY "public"."form_rspns_rvw_hstry"
    ADD CONSTRAINT "fke2id6suu5ht5m6h9hdmnlyitr" FOREIGN KEY ("prcs_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mbr_role_rel"
    ADD CONSTRAINT "fke4231l7j1ov6uptipg5rfp2p2" FOREIGN KEY ("role_id") REFERENCES "public"."role"("role_id");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "fke98fewlujgtdj4ae54s31hksj" FOREIGN KEY ("prpsr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."atndc"
    ADD CONSTRAINT "fkecu74o17ywmod26pgxp8l42ld" FOREIGN KEY ("sesn_id") REFERENCES "public"."sesn"("sesn_id");

ALTER TABLE ONLY "public"."mtg"
    ADD CONSTRAINT "fker65s5itbngodwxdhxgwiowl0" FOREIGN KEY ("mtg_rbprsn_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."sub_work_rjct"
    ADD CONSTRAINT "fkfo0xp7208swp62tbiw1pl4nmy" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."sesn"
    ADD CONSTRAINT "fkh5voxfyy1gft8laf20domb0yr" FOREIGN KEY ("rgtr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "fkhccb9i83a9b1htshmre1j8tu5" FOREIGN KEY ("acdm_actv_type_cd") REFERENCES "public"."acdm_actv_type"("acdm_actv_type_cd");

ALTER TABLE ONLY "public"."acdm_actv"
    ADD CONSTRAINT "fkhiuwj452c00txin1gj6d07es" FOREIGN KEY ("form_rspns_id") REFERENCES "public"."form_rspns_hstry"("form_rspns_id");

ALTER TABLE ONLY "public"."mtg_dtl"
    ADD CONSTRAINT "fki74qk9nkp7vhvm5whl0gepb0t" FOREIGN KEY ("mtg_id") REFERENCES "public"."mtg"("mtg_id");

ALTER TABLE ONLY "public"."oper"
    ADD CONSTRAINT "fki8cidpgn4oxv9d58bjea0rd0h" FOREIGN KEY ("pic_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."role_authrt_rel"
    ADD CONSTRAINT "fkipf37iagpuig5sghh746wmk6v" FOREIGN KEY ("authrt_cd") REFERENCES "public"."authrt"("authrt_cd");

ALTER TABLE ONLY "public"."role"
    ADD CONSTRAINT "fkis13j270npskln3wy006nd78u" FOREIGN KEY ("role_clsf_cd") REFERENCES "public"."role_clsf"("role_clsf_cd");

ALTER TABLE ONLY "public"."form_rspns_hstry"
    ADD CONSTRAINT "fkjdph0cosdigjs38tbbpxt7410" FOREIGN KEY ("form_id") REFERENCES "public"."form"("form_id");

ALTER TABLE ONLY "public"."form_lbl_rel"
    ADD CONSTRAINT "fkjh8wp4746dvo22irr3w931ifq" FOREIGN KEY ("form_id") REFERENCES "public"."form"("form_id");

ALTER TABLE ONLY "public"."form_qitem_hstry"
    ADD CONSTRAINT "fkjyc96q6ounlrfv8869l4dhnt6" FOREIGN KEY ("form_id") REFERENCES "public"."form"("form_id");

ALTER TABLE ONLY "public"."event_ptcp"
    ADD CONSTRAINT "fkk6jvxf63jtaal1hlnabs4jwov" FOREIGN KEY ("rgtr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."sub_work_stts_hstry"
    ADD CONSTRAINT "fkkulldw86g72bgrfjrklaeyqgw" FOREIGN KEY ("sub_work_id") REFERENCES "public"."sub_work"("sub_work_id");

ALTER TABLE ONLY "public"."mbr_grd_hstry"
    ADD CONSTRAINT "fkldq8y4cffwc5bkk42lq0fmhvd" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."authrt"
    ADD CONSTRAINT "fknb8tr4dtes70rpq8ts6340h14" FOREIGN KEY ("up_authrt_cd") REFERENCES "public"."authrt"("authrt_cd");

ALTER TABLE ONLY "public"."sub_work_rjct"
    ADD CONSTRAINT "fknf42kl72njk7hx4tkco39dg3n" FOREIGN KEY ("sub_work_id") REFERENCES "public"."sub_work"("sub_work_id");

ALTER TABLE ONLY "public"."form"
    ADD CONSTRAINT "fknn5rhjtcayw32pk718rr8hugc" FOREIGN KEY ("creatr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."event"
    ADD CONSTRAINT "fko56e92mgivl8nw1a8ldt57lap" FOREIGN KEY ("event_clsf_cd") REFERENCES "public"."event_clsf"("event_clsf_cd");

ALTER TABLE ONLY "public"."role_authrt_rel"
    ADD CONSTRAINT "fkoptkm3dclreina7elponx8hm7" FOREIGN KEY ("role_id") REFERENCES "public"."role"("role_id");

ALTER TABLE ONLY "public"."sesn"
    ADD CONSTRAINT "fkovl82kpdbswljxeyn5apale7b" FOREIGN KEY ("crclm_artcl_id") REFERENCES "public"."crclm_artcl"("crclm_artcl_id");

ALTER TABLE ONLY "public"."mbr_stts_hstry"
    ADD CONSTRAINT "fkp1ojiaq3u4nn8qihy3ho2abab" FOREIGN KEY ("chnrg_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mbr_stts_hstry"
    ADD CONSTRAINT "fkpaqynex6891h05rpp3olsnusy" FOREIGN KEY ("aftr_mbr_stts_cd") REFERENCES "public"."mbr_stts"("mbr_stts_cd");

ALTER TABLE ONLY "public"."sub_work_aprv_vote"
    ADD CONSTRAINT "fkpejqbv1u3b1utku2f1k7xvche" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mtg_dtl"
    ADD CONSTRAINT "fkpetc32mu03nw1fv2ofbyeaogh" FOREIGN KEY ("prsnr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."sub_work"
    ADD CONSTRAINT "fkpkc9jvctc4afdqk7orir7jho6" FOREIGN KEY ("oper_id") REFERENCES "public"."oper"("oper_id");

ALTER TABLE ONLY "public"."event"
    ADD CONSTRAINT "fkq5cq3ckdjlss8d3t1scvdc0mq" FOREIGN KEY ("creatr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."form_qitem_hstry"
    ADD CONSTRAINT "fkq7thgsl59m5n6q4jq5w0sy4eh" FOREIGN KEY ("chnrg_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mbr_stts_hstry"
    ADD CONSTRAINT "fkqbmp8q2cvnur62ih3vhx06ex1" FOREIGN KEY ("bfr_mbr_stts_cd") REFERENCES "public"."mbr_stts"("mbr_stts_cd");

ALTER TABLE ONLY "public"."form_lbl_rel"
    ADD CONSTRAINT "fkqer280akekqkr2mnixpinb25l" FOREIGN KEY ("form_lbl_id") REFERENCES "public"."form_lbl"("form_lbl_id");

ALTER TABLE ONLY "public"."atndc"
    ADD CONSTRAINT "fkqka61prj9r2o70ii0o0u0xgbv" FOREIGN KEY ("event_ptcp_id") REFERENCES "public"."event_ptcp"("event_ptcp_id");

ALTER TABLE ONLY "public"."sub_work_aprv_vote"
    ADD CONSTRAINT "fkrau3lflxhrbesypv0q1clrki8" FOREIGN KEY ("sub_work_id") REFERENCES "public"."sub_work"("sub_work_id");

ALTER TABLE ONLY "public"."work"
    ADD CONSTRAINT "fkrgp9dycsvkytj96c9xeyi3yg7" FOREIGN KEY ("oper_id") REFERENCES "public"."oper"("oper_id");

ALTER TABLE ONLY "public"."sub_work_aprv"
    ADD CONSTRAINT "fks5nw8jn7uw11ws3rxntlyymtl" FOREIGN KEY ("sub_work_id") REFERENCES "public"."sub_work"("sub_work_id");

ALTER TABLE ONLY "public"."sub_work_aprv"
    ADD CONSTRAINT "fksrdrg3mf7my815li34hnxjk7f" FOREIGN KEY ("sub_work_stts_hstry_id") REFERENCES "public"."sub_work_stts_hstry"("sub_work_stts_hstry_id");

ALTER TABLE ONLY "public"."mbr_chg_hstry"
    ADD CONSTRAINT "fkssqlpq3chlo7d1rciu5wguvt1" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."form_tmpl"
    ADD CONSTRAINT "fkt4wy1omf82jvgb8uioe9t3br8" FOREIGN KEY ("creatr_mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."sub_work"
    ADD CONSTRAINT "fkt79md8tilpe950l3htv4wpooc" FOREIGN KEY ("sub_work_type_id") REFERENCES "public"."sub_work_type"("sub_work_type_id");

ALTER TABLE ONLY "public"."mtg"
    ADD CONSTRAINT "fktb76xdi1cetp1je6if5vsh809" FOREIGN KEY ("oper_id") REFERENCES "public"."oper"("oper_id");

ALTER TABLE ONLY "public"."event_ptcp"
    ADD CONSTRAINT "fktd4dqicsmwfngwhocoak92b6n" FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id");

ALTER TABLE ONLY "public"."mtg_dtl"
    ADD CONSTRAINT "fktk2gcxj17b2um5c3m3onjv2ol" FOREIGN KEY ("oper_id") REFERENCES "public"."oper"("oper_id");

CREATE INDEX "idx_acdm_actv_leadr_mbr_id" ON "public"."acdm_actv" USING "btree" ("leadr_mbr_id");

CREATE INDEX "idx_acdm_actv_prpsr_mbr_id" ON "public"."acdm_actv" USING "btree" ("prpsr_mbr_id");

CREATE INDEX "idx_acdm_actv_stts_cd" ON "public"."acdm_actv" USING "btree" ("acdm_actv_stts_cd");

CREATE INDEX "idx_mtg_dtl_mtg_id" ON "public"."mtg_dtl" USING "btree" ("mtg_id");

CREATE INDEX "idx_oper_bgng_dt" ON "public"."oper" USING "btree" ("bgng_dt");

CREATE INDEX "idx_oper_crt_dt" ON "public"."oper" USING "btree" ("crt_dt");

CREATE INDEX "idx_sesn_actl_ymd" ON "public"."sesn" USING "btree" ("actl_ymd");

CREATE INDEX "idx_sesn_stts_cd" ON "public"."sesn" USING "btree" ("sesn_stts_cd");

CREATE INDEX "idx_sub_work_aprv_stts_cd" ON "public"."sub_work" USING "btree" ("aprv_stts_cd");

CREATE INDEX "idx_sub_work_aprv_vote_sub_work" ON "public"."sub_work_aprv_vote" USING "btree" ("sub_work_id", "aprv_seqno");

CREATE INDEX "idx_sub_work_ddln_dt" ON "public"."sub_work" USING "btree" ("ddln_dt");

CREATE INDEX "idx_sub_work_work_id" ON "public"."sub_work" USING "btree" ("work_id");

CREATE INDEX "idx_sub_work_work_stts_cd" ON "public"."sub_work" USING "btree" ("work_stts_cd");

CREATE INDEX "idx_work_work_stts_cd" ON "public"."work" USING "btree" ("work_stts_cd");

CREATE INDEX "idx_work_work_type_cd" ON "public"."work" USING "btree" ("work_type_cd");

CREATE INDEX "ix_form_rspns_rvw_hstry_response" ON "public"."form_rspns_rvw_hstry" USING "btree" ("form_rspns_id", "prcs_dt");

CREATE UNIQUE INDEX "uk_form_rspns_hstry_one_draft" ON "public"."form_rspns_hstry" USING "btree" ("form_id", "mbr_id") WHERE (("rspns_stts_cd")::"text" = 'DRAFT'::"text");
