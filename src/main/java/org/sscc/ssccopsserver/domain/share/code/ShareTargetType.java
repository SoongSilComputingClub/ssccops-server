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
    WORK,

    /*
     * 학술 프로그램 — 모집 단위 (ssccops#311 · 미리보기 제공자는
     * AcademicProgramSharePreviewProvider).
     *
     * **세션과 묶지 않는다.** 프로그램은 모집을 뿌리는 단위이고 세션은 회차를 알리는 단위라
     * 뿌리는 시점도 받는 사람도 다르다 — 한 값으로 묶으면 대상 ID가 무엇을 가리키는지가
     * 다시 갈려, 구분 코드를 둔 이유가 그 안에서 되풀이된다.
     */
    ACADEMIC_PROGRAM,

    /*
     * 학술 세션 — 회차 공지 단위 (ssccops#311 · 미리보기 제공자는 SessionSharePreviewProvider).
     *
     * 대상 ID는 `sesn_id`이지 (활동 ID, 회차 번호) 쌍이 아니다 — 이 enum이 대상을 두 값
     * (구분 코드 + 대상 ID)으로 두기로 했으므로 세 번째 값이 필요한 대상은 여기 들어올 수 없고,
     * 회차는 자기 PK가 있어 그럴 필요도 없다.
     */
    ACADEMIC_SESSION,

    /*
     * 행사 (ssccops#312 · 미리보기 제공자는 EventSharePreviewProvider).
     *
     * **여는 것은 게시 전(DRAFT) 행사뿐이다.** 게시된 행사는 이미 익명이 여는 주소가 있어
     * (`apps/www`의 `/events/{eventId}`) 토큰이 더하는 것이 폐기 기능뿐인데, 그 폐기가 원본
     * 공개 URL을 막지 못한다 — 지키지 못하는 것을 지킨다고 말하는 버튼이 된다. 그 판정은
     * `EventService.requireShareableDraft`가 한다.
     *
     * 착지 앱은 `apps/www`다(ssccops#254 착지 결정) — 뿌리는 대상이 동아리 밖이라 로그인 벽
     * 뒤의 `apps/admin`이 받으면 링크를 받은 사람이 아무것도 볼 수 없다.
     */
    EVENT;

    public String code() {
        return name();
    }
}
