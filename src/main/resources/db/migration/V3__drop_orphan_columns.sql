-- ============================================================================
-- V3 — `ddl-auto: update`가 남긴 고아 컬럼을 지운다
-- ============================================================================
-- ⚠ **아직 돌지 않는다** (`spring.flyway.enabled`가 전부 false다).
--
-- baseline(V1)이 prod 덤프이므로 아래 컬럼들이 V1에 **포함된 채로** 들어온다. `ddl-auto: update`는
-- 컬럼 삭제·이름 변경을 반영하지 않아 지금까지 지울 방법이 수동 ALTER뿐이었다 — 그 관행을
-- 대체하는 것이 이 이슈(ssccops#213)이고, 첫 대상이 이 둘이다.
--
-- 전부 `IF EXISTS`인 것은 **이미 수동으로 적용됐을 수 있기 때문이다.** 특히 shr_lnk 쪽은
-- ssccops#212가 개명하면서 수동 DDL을 안내했는데 적용 여부가 확인되지 않았다. 멱등하게 두면
-- 적용된 환경과 안 된 환경에서 같은 결과가 된다.
-- ============================================================================

-- ① sub_work_type.autzr_role_cd — 직위 코드 시절의 승인자 컬럼.
--
-- ssccops-server#123이 승인 자격을 직위 코드에서 권한으로 옮기며 autzr_authrt_cd를 새로 만들었는데,
-- Hibernate가 리네임을 '새 컬럼 추가'로 처리해 **값이 든 옛 컬럼 옆에 빈 새 컬럼**이 생겼다.
-- 앱은 새 컬럼만 읽으므로 prod에서 승인 필요 하위 업무를 아무도 승인·반려할 수 없었다(ssccops#209).
-- 백필(#241)로 값은 옮겼고, 옛 컬럼만 남았다.
ALTER TABLE sub_work_type DROP COLUMN IF EXISTS autzr_role_cd;

-- ② shr_lnk.trgt_se_cd — 공유 링크의 대상 구분 컬럼(개명 전 이름).
--
-- 데이터사전 등재(ssccops#212)에서 shr_trgt_se_cd로 갈랐다. 기존 trgt_se_cd 그룹은 파일 참조가
-- 쓰는 값이고 "R2 오브젝트 키 접두사를 이 값이 갖는다"는 뜻이 이미 박혀 있어, 공유 대상과
-- 한 그룹에 두면 한 이름이 두 어휘를 담는다(prcs_se_cd가 같은 자리에서 문제가 됐다).
--
-- **값 이전이 필요할 수 있다.** 개명 뒤에 발급된 링크는 새 컬럼에 들어가지만, 개명 전에 발급된
-- 행이 있으면 옛 컬럼에만 값이 있다. 지우기 전에 옮긴다 — 두 컬럼이 다 있을 때만 도는 방어적 UPDATE다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'shr_lnk' AND column_name = 'trgt_se_cd')
       AND EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'shr_lnk' AND column_name = 'shr_trgt_se_cd') THEN
        UPDATE shr_lnk SET shr_trgt_se_cd = trgt_se_cd WHERE shr_trgt_se_cd IS NULL;
    END IF;
END $$;

ALTER TABLE shr_lnk DROP COLUMN IF EXISTS trgt_se_cd;
