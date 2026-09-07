-- ============================================================================
-- V4 — `ddl-auto: update`가 남긴 고아 컬럼을 지운다
-- ============================================================================
-- **dev·prod에서는 아무 일도 일어나지 않는다.** 이 넷은 baseline을 뜨기 전에 손으로 정리했고
-- (`document/cleanup-2026-09-07.sql`, 2026-09-07) V1은 그 뒤에 뜬 덤프다.
--
-- 그런데도 이 파일을 두는 이유는 **개발자의 로컬 DB** 때문이다. `local`은 `ddl-auto: update`를
-- 유지하기로 했고(ssccops#213) `update`는 컬럼을 지우지 않으므로, 몇 달 자란 로컬 DB에는 이 넷이
-- 그대로 남아 있다. Flyway를 켜면 그 DB는 `baseline-on-migrate`로 V1이 '적용됨' 표시만 되고
-- 실제 스키마는 손대지 않으므로, 이 마이그레이션이 없으면 로컬만 영영 다른 모양으로 남는다.
--
-- 그래서 이 파일은 **사람이 손으로 한 정리를 코드로 옮겨 적은 것**이다. 그게 이 이슈의 목적이기도
-- 하다 — 리네임을 지시하는 ALTER가 이슈 본문에만 있고 아무도 실행을 강제하지 않아 세 번 터졌다
-- (ssccops#209 승인 마비 · #212 공유 링크 · ssccops-server#224 회의 안건).
--
-- 전부 `IF EXISTS`이고 백필이 먼저다. **지우기 전에 값을 옮기는 순서를 지킬 것** — 셋 다
-- 리네임이었고, Hibernate가 그것을 '새 컬럼 추가'로 처리해 값이 옛 컬럼에만 남아 있었다.
-- ============================================================================

-- ① mtg_dtl.prcs_se_cd → agnd_prcs_se_cd  (ssccops-server#224)
--
-- 회의 안건과 폼 응답 검토가 `prcs_se_cd`라는 같은 이름을 다른 값 집합으로 쓰고 있었다
-- (안건은 PENDING·HOLD·CLOSED, 검토는 SUBMIT·ACCEPT·REQUEST_CHANGES·REJECT). 데이터사전의
-- 표준코드는 코드그룹ID = 컬럼ID로 묶이므로 한 그룹에 두 어휘가 섞였고, 그래서 양쪽 다
-- 한정어를 붙였다. 값 집합 자체는 바뀌지 않아 그대로 옮기면 된다.
--
-- 앱(`MeetingAgendaEntity.processStatus`)은 새 컬럼만 읽으므로, 백필 전에는 그 이전에 만들어진
-- 안건의 처리 상태가 **빈 채로 보인다** — nullable이라 터지지 않고 조용히 틀린다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'mtg_dtl'
                 AND column_name = 'prcs_se_cd') THEN
        UPDATE "public"."mtg_dtl"
           SET "agnd_prcs_se_cd" = "prcs_se_cd"
         WHERE "agnd_prcs_se_cd" IS NULL AND "prcs_se_cd" IS NOT NULL;
    END IF;
END $$;

ALTER TABLE "public"."mtg_dtl" DROP COLUMN IF EXISTS "prcs_se_cd";


-- ② sub_work_type.autzr_role_cd → autzr_authrt_cd  (ssccops#209 · ssccops-server#241)
--
-- ssccops-server#123이 승인 자격의 판정 재료를 직위 코드에서 권한으로 바꾸며 컬럼과 값 체계를
-- 함께 갈았는데(`TREASURER` → `SUB_WORK_APPROVE_TREASURER`) 백필 UPDATE가 함께 삭제됐다.
-- prod에서 승인 필요 하위 업무를 **아무도** 승인·반려할 수 없었다 — SUPER도 예외가 아니었다
-- (요구 권한이 NULL이면 대조가 성립하지 않아 전원 차단된다).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'sub_work_type'
                 AND column_name = 'autzr_role_cd') THEN
        UPDATE "public"."sub_work_type"
           SET "autzr_authrt_cd" = 'SUB_WORK_APPROVE_' || "autzr_role_cd",
               "mdfcn_dt" = now()
         WHERE "aprv_need_yn" = TRUE
           AND "autzr_authrt_cd" IS NULL
           AND "autzr_role_cd" IN ('PRESIDENT', 'VICE_PRESIDENT', 'TREASURER', 'DIRECTOR');
    END IF;
END $$;

ALTER TABLE "public"."sub_work_type" DROP COLUMN IF EXISTS "autzr_role_cd";


-- ③ role.role_pstn_cd  (ssccops-server#123)
--
-- 직위 코드(`RolePositionCode`·`AuthorizerRole`)가 권한 시스템으로 통합되며 enum과 함께
-- 삭제된 컬럼이다. 옮길 값이 없다 — ②의 백필이 그 역할을 이미 했다.
ALTER TABLE "public"."role" DROP COLUMN IF EXISTS "role_pstn_cd";


-- ④ shr_lnk.trgt_se_cd → shr_trgt_se_cd  (ssccops#212 · ssccops-server#260)
--
-- 데이터사전 등재에서 갈랐다. 기존 `trgt_se_cd` 그룹은 파일 참조가 쓰는 값이고 "R2 오브젝트 키
-- 접두사를 이 값이 갖는다"는 뜻이 이미 박혀 있어, 공유 대상과 한 그룹에 두면 한 이름이 두 어휘를
-- 담는다 — ①이 바로 그래서 갈린 자리다.
--
-- V2가 만든 새 테이블에는 애초에 옛 컬럼이 없다. 이 문장이 실제로 일하는 것은 개명 전에
-- Hibernate가 만들어 둔 테이블을 가진 DB에서뿐이다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'shr_lnk'
                 AND column_name = 'trgt_se_cd')
       AND EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'shr_lnk'
                     AND column_name = 'shr_trgt_se_cd') THEN
        UPDATE "public"."shr_lnk"
           SET "shr_trgt_se_cd" = "trgt_se_cd"
         WHERE "shr_trgt_se_cd" IS NULL;
    END IF;
END $$;

ALTER TABLE "public"."shr_lnk" DROP COLUMN IF EXISTS "trgt_se_cd";
