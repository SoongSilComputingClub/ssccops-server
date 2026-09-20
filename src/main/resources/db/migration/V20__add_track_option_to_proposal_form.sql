-- 라이브 기획안 폼의 유형 선택지에 «트랙»을 더한다 (#512).
--
-- #510이 «트랙»을 학술 활동 유형으로 세웠지만(V19 · acdm_actv_type · 리더 역할 · 이관 매핑)
-- **신청자는 여전히 트랙을 고를 수 없다.** 선택지는 form.qitem_cpst_cn(JSONB) 안에 있는
-- 값이고, 그 값에 닿는 길이 셋 다 막혀 있어서다:
--
--   1. 배포 — ProposalFormSeeder의 멱등 판정이 sys_form_cd 하나라, 폼이 이미 있으면 아무것도
--      하지 않는다. #510이 고친 ProposalFormSeed.PROGRAM_TYPE_OPTIONS는 폼을 **처음 세우는**
--      환경에만 닿는다.
--   2. API — FormEntity.requireSystemQuestionItemsUnchanged(#498 · #505)가 시스템 폼의 문항
--      구조가 바뀌는 저장을 409로 거절하고, 그 비교 대상에 optionList가 들어 있다.
--   3. 어드민 화면 — 같은 이유로 편집기가 선택지 입력을 잠갔다(ssccops-web#554).
--
-- **2·3번 잠금은 옳다** — 유형 선택지는 acdm_actv_type.type_nm과 글자까지 같아야 하고, 하나만
-- 어긋나면 «접수는 되는데 승인이 안 되는» 기획안이 쌓인다(#510이 고친 그 상태다). 그래서 이
-- 파일은 잠금을 건드리지 않는다 — 이미 서 있는 폼을 한 번 고치고 끝낸다.
--
-- ── 이것은 시드가 아니라 «이미 선 DB 수리»다 ──────────────────────────────────
-- 그래서 application-test.yaml의 data-locations와 SeedScript.LOCATIONS에 **넣지 않는다**.
-- 선례는 V4__drop_orphan_columns.sql이다 — 새로 만드는 환경에는 해당 사항이 없고(시더가 이미
-- 선택지 셋으로 폼을 세운다) 고칠 대상은 몇 달 자란 dev·prod의 폼뿐이다. 덤으로 test 프로필의
-- H2가 이 JSONB 문법을 읽지 못하는 문제도 함께 사라진다.
--
-- ── 문항 버전을 올리고 이력을 남긴다 ──────────────────────────────────────────
-- form_rspns_hstry.qitem_ver가 «이 응답이 몇 번 문항 구성에 답한 것인가»를 기록한다. 버전을
-- 올리지 않으면 트랙으로 낸 응답이 «v1에 답했다»고 적히는데, v1의 이력에는 선택지가 둘뿐이라
-- 그 답이 어느 선택지였는지 이력으로 되짚을 수 없다.
--
-- 이력의 변경자(chnrg_mbr_id)는 NULL이다. 그 컬럼이 nullable인 이유를
-- FormQuestionHistoryEntity 주석이 «사람이 아닌 경로(시드·이관 스크립트)가 구성을 세우는 경우가
-- 있기 때문»이라고 적어 뒀고, 이 파일이 정확히 그 경우다 — 아무 회원의 이름으로도 적지 않는다.
--
-- ── 선택지 문자열을 손으로 적지 않는다 ────────────────────────────────────────
-- acdm_actv_type에서 읽어 온다. 여기에 '트랙'을 리터럴로 박으면 기준정보와 갈릴 여지가 생기는데,
-- 그 갈림이 곧 이 이슈의 원인이다. V19가 먼저 돌므로 그 행은 반드시 있다.
DO $$
DECLARE
    v_track_name text;
    v_form_id    bigint;
    v_new_cpst   jsonb;
    v_new_ver    integer;
BEGIN
    SELECT type_nm INTO v_track_name
      FROM acdm_actv_type
     WHERE acdm_actv_type_cd = 'TRACK';

    -- V19가 넣으므로 도달하지 않는다. 조용히 넘기지 않는 것은, 없는 채로 선택지만 더하면
    -- «접수는 되는데 승인이 안 되는» 상태를 이 파일이 손수 만들게 되기 때문이다.
    IF v_track_name IS NULL THEN
        RAISE EXCEPTION 'acdm_actv_type에 TRACK이 없습니다 — V19가 돌지 않았습니다';
    END IF;

    -- 고칠 폼이 있는가. «폼이 없다»(새 환경)와 «이미 들어 있다»(운영진이 먼저 더해 둔 환경)가
    -- 모두 여기서 걸러진다 — 둘 다 아무것도 하지 않는 것이 맞다.
    SELECT f.form_id INTO v_form_id
      FROM "form" f
     WHERE f.sys_form_cd = 'PROPOSAL'
       AND EXISTS (
           SELECT 1
             FROM jsonb_array_elements(f.qitem_cpst_cn -> 'qitems') AS q
            WHERE q ->> 'qitemId' = 'programType'
              AND NOT (q -> 'optionList' @> to_jsonb(v_track_name))
       );

    IF v_form_id IS NULL THEN
        RETURN;
    END IF;

    -- qitems 배열을 순서 그대로 다시 쌓으면서 programType 문항의 optionList에만 한 칸 더한다.
    --
    -- WITH ORDINALITY + ORDER BY는 **방어**다. jsonb_agg의 결과 순서는 입력 순서로 보장되지
    -- 않는다(집계 함수의 순서는 실행 계획에 달려 있다) — 문항 순서가 뒤집히면 화면의 문항이
    -- 통째로 다른 차례로 그려진다. 다만 이 모양·이 크기에서는 PostgreSQL이 실제로 순서를
    -- 지키므로 **이 줄을 지워도 테스트는 통과한다**(재 봤다). 그래서 테스트가 아니라 이 주석이
    -- 근거다 — 계획이 바뀌어도 성립해야 하는 값이라 명시한다.
    UPDATE "form" f
       SET qitem_cpst_cn = jsonb_set(
               f.qitem_cpst_cn,
               '{qitems}',
               (
                   SELECT jsonb_agg(
                              CASE
                                  WHEN e.q ->> 'qitemId' = 'programType'
                                      THEN jsonb_set(
                                               e.q,
                                               '{optionList}',
                                               (e.q -> 'optionList') || to_jsonb(v_track_name))
                                  ELSE e.q
                              END
                              ORDER BY e.ord)
                     FROM jsonb_array_elements(f.qitem_cpst_cn -> 'qitems')
                          WITH ORDINALITY AS e(q, ord)
               )
           ),
           qitem_ver = f.qitem_ver + 1,
           mdfcn_dt  = CURRENT_TIMESTAMP
     WHERE f.form_id = v_form_id
    RETURNING f.qitem_cpst_cn, f.qitem_ver INTO v_new_cpst, v_new_ver;

    INSERT INTO form_qitem_hstry (form_id, qitem_ver, qitem_cpst_cn, chnrg_mbr_id, crt_dt)
    VALUES (v_form_id, v_new_ver, v_new_cpst, NULL, CURRENT_TIMESTAMP);
END $$;
