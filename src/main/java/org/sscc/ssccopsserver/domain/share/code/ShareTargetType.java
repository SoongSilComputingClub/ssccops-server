package org.sscc.ssccopsserver.domain.share.code;

/*
 * shr_lnk.shr_trgt_se_cd — 공유 링크가 가리키는 대상의 종류 (ssccops#200).
 *
 * 처음에는 값이 하나(SUB_WORK)뿐이었는데도 구분 코드로 연 것은 `file_rfrnc`가 같은 자리에서
 * 겪은 것 때문이다(#220) — 대상이 늘 때마다 테이블을 새로 만들지 않으려면 소유자를
 * (구분 코드 + 대상 ID) 두 값으로 두어야 하고, 나중에 여는 것은 스키마 변경이라
 * `ddl-auto: update`가 반영하지 못한다. **ssccops#306이 WORK를 더할 때 값 하나를 적는 것으로
 * 끝났으므로 그 판단은 값을 치렀다** — 마이그레이션도, 테이블도 늘지 않았다.
 *
 * **컬럼명에 `shr_` 한정어가 붙은 이유는 `file_rfrnc.trgt_se_cd`와 코드그룹을 갈랐기 때문이다**
 * (ssccops#212). 코드그룹ID = 컬럼ID 규칙이라 이름이 같으면 그룹도 같아지는데, 그쪽 값은
 * R2 오브젝트 키 접두사까지 정하는 값이라 공유 대상과 값 집합이 섞인다 — 한 코드가 두 어휘를
 * 담아 실제로 터진 적이 있다(#224 `prcs_se_cd`).
 *
 * **FK를 걸지 않는다.** 대상 테이블이 앞으로 여럿이라 걸 수 없고, 배타적 FK로 가면 대상이 하나
 * 늘 때마다 스키마와 데이터사전이 함께 바뀐다. 대가는 대상이 지워진 뒤 남는 행이며, 그때는
 * 미리보기 제공자가 대상을 찾지 못해 404가 나간다 — 폐기된 링크와 같은 답이라 문제가 되지 않는다.
 *
 * **값을 더하는 쪽이 착지 앱도 함께 정한다**(ADR-0017). 하위 업무·업무·회의는 `apps/admin`이,
 * 학술·행사는 `apps/www`가 받는다. 그 표는 서버가 아니라 `@ssccops/share-meta`가 갖는다 —
 * 서버가 URL을 조립하지 않기 때문이다(`ShareLinkResponse` 주석).
 */
public enum ShareTargetType {

    /** 하위 업무 (ssccops#200 · 미리보기 제공자는 SubWorkSharePreviewProvider) */
    SUB_WORK,

    /** 업무 (ssccops#306 · 미리보기 제공자는 WorkSharePreviewProvider) */
    WORK;

    public String code() {
        return name();
    }
}
