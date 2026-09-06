package org.sscc.ssccopsserver.domain.share.code;

/*
 * shr_lnk.trgt_se_cd — 공유 링크가 가리키는 대상의 종류 (ssccops#200).
 *
 * 지금 값은 하나지만 처음부터 구분 코드로 연 것은 `file_rfrnc`가 같은 자리에서 겪은 것 때문이다
 * (#220) — 대상이 늘 때마다 테이블을 새로 만들지 않으려면 소유자를 (구분 코드 + 대상 ID) 두
 * 값으로 두어야 하고, 나중에 여는 것은 스키마 변경이라 `ddl-auto: update`가 반영하지 못한다.
 *
 * **FK를 걸지 않는다.** 대상 테이블이 앞으로 여럿이라 걸 수 없고, 배타적 FK로 가면 대상이 하나
 * 늘 때마다 스키마와 데이터사전이 함께 바뀐다. 대가는 대상이 지워진 뒤 남는 행이며, 그때는
 * 미리보기 제공자가 대상을 찾지 못해 404가 나간다 — 폐기된 링크와 같은 답이라 문제가 되지 않는다.
 */
public enum ShareTargetType {

    /** 하위 업무 (ssccops#200 · 미리보기 제공자는 SubWorkSharePreviewProvider) */
    SUB_WORK;

    public String code() {
        return name();
    }
}
