-- ============================================================================
-- V9 — 회원 본인 데이터 FK에 ON DELETE CASCADE (임시 회원 하드 삭제 · ADR-0021 · #361)
-- ============================================================================
-- 연동 실패로 생긴 중복 계정(명부 행 R + 새 가입 N)을 정리하려고 회원 하드 삭제를 **임시로**
-- 연다. 지우는 코드는 `DELETE FROM mbr WHERE mbr_id = ?` 한 줄이고, **무엇이 함께 지워지고
-- 무엇이 삭제를 막는지는 이 파일이 정한다** — 코드로 20개 도메인을 조회해 막으면 도메인 순환이
-- 걸리고 하나 빠뜨리면 500이다. FK에 못 박으면 빠뜨릴 자리가 없다.
--
-- mbr을 가리키는 FK는 29개이고 두 종류다.
--
--   본인 데이터(9)  — 이 회원의 기록. 등급·상태·변경 이력, 역할 배정, 폼 응답, 행사 참가,
--                    하위 업무 승인·투표·반려. 회원이 없어지면 뜻을 잃는 행들이라 **함께 지운다.**
--   행위자 참조(20) — 이 회원이 **남의 것**에 한 일. 폼·행사·업무의 작성자, 회의 주재자·발표자,
--                    학술 리더·제안자·승인자, 응답 검토자, 이력 변경자, 담당자, 공유 링크 발급자.
--                    지우면 남의 기록에서 "누가"가 사라지므로 **손대지 않는다** — V1 그대로
--                    NO ACTION이라 행이 하나라도 있으면 DELETE가 실패하고 서비스가 409로 옮긴다.
--
-- 본인 데이터 9개 아래에 딸린 행도 함께 간다(2차 · 3개). 응답을 지우면 그 응답의 검토 이력과
-- 응답으로 등록된 참가가, 참가를 지우면 그 참가의 출석이 함께 지워져야 한다 — 남기면 고아이고
-- FK가 삭제 자체를 막는다. **딸린 행이 다른 회원을 행위자로 가리키는 것은 괜찮다**
-- (form_rspns_rvw_hstry.prcs_mbr_id 등): 지워지는 것은 행이고 참조된 회원은 남는다. V1의
-- 행위자 참조가 RESTRICT가 아니라 NO ACTION(기본값)인 것이 여기서 중요하다 — NO ACTION은
-- 문장 끝에 검사하므로 cascade가 먼저 딸린 행을 치운 뒤에 판정한다. 검토 이력의 제출 행은
-- 처리자가 응답자 본인이라(FormResponseReviewHistoryEntity) RESTRICT였다면 응답이 있는
-- 회원은 아무도 못 지웠다.
--
-- **acdm_actv.form_rspns_id는 cascade에 넣지 않는다.** 승인된 기획안 응답에서 이관된 학술
-- 활동(ADR-0002)은 응답에 딸린 행이 아니라 동아리의 활동이다 — 응답을 따라 지우면 세션·출석·
-- 승인 이력이 통째로 사라진다. 그대로 NO ACTION이며, 제안자(prpsr_mbr_id)와 함께 삭제를 막는
-- 쪽에 선다.
--
-- 제약 이름은 V1의 것을 그대로 쓴다(Hibernate가 만든 해시 이름). 엔티티에도 같은 자리에
-- @OnDelete(CASCADE)를 붙여 H2(테스트 · ddl-auto: create)가 같은 제약을 만들게 했다 — 그래야
-- "응답 있는 회원을 지우면 응답이 사라진다"를 테스트가 DB로 확인한다. `local`(ddl-auto: update)은
-- 이미 있는 FK를 다시 만들지 않으므로 이 파일이 돌아야 cascade가 붙는다 — Flyway가 Hibernate보다
-- 먼저 돌아 문제없다.
--
-- DROP은 IF EXISTS다. dev·prod·local 모두 V1 이름으로 있지만, 손으로 만진 DB에서 이름이
-- 다르면 ADD가 중복으로 실패해 그 자리에서 드러난다 — 조용히 넘어가는 것보다 낫다.
--
-- **이 마이그레이션은 되돌리지 않는다**(ADR-0021 폐기 조건). 엔드포인트가 플래그로 닫히면
-- cascade는 아무 일도 하지 않는다 — mbr 행을 지우는 경로가 그 엔드포인트뿐이다.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1차 — mbr을 직접 가리키는 본인 데이터 9개
-- ----------------------------------------------------------------------------

-- 등급 이력 (mbr_grd_hstry.mbr_id). chnrg_mbr_id(변경자)는 행위자 참조라 그대로다.
ALTER TABLE "public"."mbr_grd_hstry"
    DROP CONSTRAINT IF EXISTS "fkldq8y4cffwc5bkk42lq0fmhvd";
ALTER TABLE "public"."mbr_grd_hstry"
    ADD CONSTRAINT "fkldq8y4cffwc5bkk42lq0fmhvd"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 상태 이력 (mbr_stts_hstry.mbr_id)
ALTER TABLE "public"."mbr_stts_hstry"
    DROP CONSTRAINT IF EXISTS "fka1aso9jn3i6nhqoh3mg1hiyip";
ALTER TABLE "public"."mbr_stts_hstry"
    ADD CONSTRAINT "fka1aso9jn3i6nhqoh3mg1hiyip"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 정보 변경 이력 (mbr_chg_hstry.mbr_id)
ALTER TABLE "public"."mbr_chg_hstry"
    DROP CONSTRAINT IF EXISTS "fkssqlpq3chlo7d1rciu5wguvt1";
ALTER TABLE "public"."mbr_chg_hstry"
    ADD CONSTRAINT "fkssqlpq3chlo7d1rciu5wguvt1"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 역할 배정 (mbr_role_rel.mbr_id). 지우려는 대상은 기수·등급·역할이 없는 새 계정이라 보통 비어
-- 있지만, 있어도 "그 사람이 언제 국장이었는지"는 명부 행 R 쪽에 남는다.
ALTER TABLE "public"."mbr_role_rel"
    DROP CONSTRAINT IF EXISTS "fka229oo73t8twd2by22omue4jt";
ALTER TABLE "public"."mbr_role_rel"
    ADD CONSTRAINT "fka229oo73t8twd2by22omue4jt"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 폼 응답 (form_rspns_hstry.mbr_id). 중복 계정이 가입 직후 낸 응답 한두 건이 여기다.
ALTER TABLE "public"."form_rspns_hstry"
    DROP CONSTRAINT IF EXISTS "fkbtp6dhj8bntedf10yc81a0620";
ALTER TABLE "public"."form_rspns_hstry"
    ADD CONSTRAINT "fkbtp6dhj8bntedf10yc81a0620"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 행사 참가 (event_ptcp.mbr_id). rgtr_mbr_id(등록 처리자)는 행위자 참조라 그대로다.
ALTER TABLE "public"."event_ptcp"
    DROP CONSTRAINT IF EXISTS "fktd4dqicsmwfngwhocoak92b6n";
ALTER TABLE "public"."event_ptcp"
    ADD CONSTRAINT "fktd4dqicsmwfngwhocoak92b6n"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 하위 업무 승인 (sub_work_aprv.mbr_id)
ALTER TABLE "public"."sub_work_aprv"
    DROP CONSTRAINT IF EXISTS "fk27ofrsdwuss5ecdkl8uwwgvdb";
ALTER TABLE "public"."sub_work_aprv"
    ADD CONSTRAINT "fk27ofrsdwuss5ecdkl8uwwgvdb"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 하위 업무 승인 투표 (sub_work_aprv_vote.mbr_id)
ALTER TABLE "public"."sub_work_aprv_vote"
    DROP CONSTRAINT IF EXISTS "fkpejqbv1u3b1utku2f1k7xvche";
ALTER TABLE "public"."sub_work_aprv_vote"
    ADD CONSTRAINT "fkpejqbv1u3b1utku2f1k7xvche"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- 하위 업무 반려 (sub_work_rjct.mbr_id)
ALTER TABLE "public"."sub_work_rjct"
    DROP CONSTRAINT IF EXISTS "fkfo0xp7208swp62tbiw1pl4nmy";
ALTER TABLE "public"."sub_work_rjct"
    ADD CONSTRAINT "fkfo0xp7208swp62tbiw1pl4nmy"
        FOREIGN KEY ("mbr_id") REFERENCES "public"."mbr"("mbr_id") ON DELETE CASCADE;

-- ----------------------------------------------------------------------------
-- 2차 — 본인 데이터에 딸린 행 3개
-- ----------------------------------------------------------------------------

-- 응답의 검토 이력 (form_rspns_rvw_hstry.form_rspns_id → form_rspns_hstry). 응답이 사라지면
-- 검토 이력만 남을 이유가 없다. 검토자(prcs_mbr_id)는 남는다 — 행이 지워질 뿐이다.
ALTER TABLE "public"."form_rspns_rvw_hstry"
    DROP CONSTRAINT IF EXISTS "fk51eb6sl115o38xmcux4l66ne9";
ALTER TABLE "public"."form_rspns_rvw_hstry"
    ADD CONSTRAINT "fk51eb6sl115o38xmcux4l66ne9"
        FOREIGN KEY ("form_rspns_id") REFERENCES "public"."form_rspns_hstry"("form_rspns_id")
        ON DELETE CASCADE;

-- 응답으로 등록된 참가 (event_ptcp.form_rspns_id → form_rspns_hstry). 참가는 본인 데이터라
-- mbr_id 쪽 cascade로도 지워지지만, 두 경로가 같은 행을 가리키므로 어느 쪽이 먼저든 결과는
-- 같다. 이 제약을 두는 것은 응답 삭제가 참가 삭제를 끌고 가야 한다는 뜻을 스키마에 남기기 위해서다.
ALTER TABLE "public"."event_ptcp"
    DROP CONSTRAINT IF EXISTS "fk2yfmj6dd4h4l2phh0smwi0kb9";
ALTER TABLE "public"."event_ptcp"
    ADD CONSTRAINT "fk2yfmj6dd4h4l2phh0smwi0kb9"
        FOREIGN KEY ("form_rspns_id") REFERENCES "public"."form_rspns_hstry"("form_rspns_id")
        ON DELETE CASCADE;

-- 참가의 출석 (atndc.event_ptcp_id → event_ptcp). 출석은 참가에 딸린 행이고 mbr을 직접
-- 가리키지 않는다 — 참가가 사라지면 함께 간다.
ALTER TABLE "public"."atndc"
    DROP CONSTRAINT IF EXISTS "fkqka61prj9r2o70ii0o0u0xgbv";
ALTER TABLE "public"."atndc"
    ADD CONSTRAINT "fkqka61prj9r2o70ii0o0u0xgbv"
        FOREIGN KEY ("event_ptcp_id") REFERENCES "public"."event_ptcp"("event_ptcp_id")
        ON DELETE CASCADE;

-- 행위자 참조 20개는 손대지 않는다. 목록은 MemberReferenceConstraints(서비스가 409 메시지와
-- 삭제 미리보기의 blockedBy에 쓰는 표)에 있고, 그 표와 이 파일이 갈리면 이름을 번역 못 한
-- 409가 나간다 — 코드가 아니라 문구가 빠지는 실패라 삭제 자체는 여전히 막힌다.
