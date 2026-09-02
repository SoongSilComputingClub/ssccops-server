package org.sscc.ssccopsserver.domain.form.code;

import java.util.EnumSet;

/*
 * form_rspns_hstry.rspns_stts_cd — 폼 응답 상태 기준 코드.
 *
 * DRAFT가 들어 있는 것은 ssccops #64에서 확정된 결과다. 응답 자동 저장(#36)이 제출 전
 * 내용을 어딘가에 담아야 하는데, 임시저장 전용 테이블을 따로 두면 제출 시점에 행을 옮겨야
 * 하고 "한 회원이 한 폼에 하나"라는 UNIQUE 제약을 두 테이블에 걸쳐 지켜야 한다.
 * 같은 행의 상태만 바꾸는 편이 단순해서 상태 어휘에 DRAFT를 넣었다.
 *
 * 그 결정의 직접적인 귀결이 sbmsn_dt(제출 일시) nullable이다 — DRAFT인 응답은 아직
 * 제출되지 않았으므로 제출 일시가 존재할 수 없다. 두 사실은 같이 움직인다.
 *
 * ACCEPTED·REJECTED로의 전이 규칙은 FormResponseHistoryEntity.changeStatus가 갖는다 (#37) —
 * 어떤 어휘가 있는지는 여기가, 그 사이를 어떻게 오갈 수 있는지는 엔티티가 정한다.
 *
 * CHANGES_REQUESTED(수정요청)는 #141에서 더했다. 그전까지 심사 결과는 승인 아니면 반려뿐이라
 * "고쳐서 다시 내라"를 표현할 어휘가 없었고, 반려된 응답을 응답자가 고쳐 낼 길도 없었다 —
 * DRAFT로는 되돌릴 수 없고(전이 금지) 응답은 회원당 폼당 1건(UNIQUE)이라 새로 만들 수도 없다.
 * 이 상태가 그 되돌림 자리다: 응답자에게 편집권이 돌아가고, 재제출하면 SUBMITTED로 복귀하며
 * sbmsn_seq가 1 늘어난다. ACCEPTED·REJECTED와 갈리는 지점이 여기다 — 그 둘은 **결론이라 종결**
 * 이고(응답자도 검토자도 되돌릴 수 없다), 심사가 계속 열려 있는 상태는 SUBMITTED와 이것뿐이다.
 */
public enum ResponseStatus {

    /** 임시저장. 아직 제출되지 않았고 sbmsn_dt가 NULL인 유일한 상태 (#36) */
    DRAFT,

    /** 응답자가 제출을 마친 상태. 심사 전 기본값 */
    SUBMITTED,

    /*
     * 운영진이 수정요청 (#141). 응답자가 고쳐 다시 낼 수 있는 유일한 심사 결과다.
     *
     * 제출 이상(submittedOrLater)에 든다 — 응답자가 실제로 낸 응답이고, 심사가 끝나지 않았을
     * 뿐이라 목록·집계에서 빠지면 운영자에게는 사라진 것처럼 보인다.
     */
    CHANGES_REQUESTED,

    /** 운영진이 승인. 종결이며, 이 뒤로 활동 개설·역할 부여 같은 후속 처리가 시작된다 */
    ACCEPTED,

    /** 운영진이 반려. 종결이라 재제출도 번복도 막힌다 (#141) */
    REJECTED;

    /*
     * 제출 이상 — 응답자가 실제로 낸 응답의 상태들. 임시저장(DRAFT)만 빠진다.
     *
     * 폼 목록·상세의 응답 집계(#32)와 운영자용 응답 목록·심사(#37)가 같은 기준을 써야 해서 이
     * 어휘를 코드 enum이 갖는다. 원래는 FormServiceImpl의 private 상수였는데, #37이 같은 집합을
     * 필요로 하면서 서비스 두 곳에 같은 EnumSet이 놓일 참이었다 — 두 벌이 되면 갈리고, 갈리면
     * "응답 3건"인데 목록에는 1건만 보이는 상태가 된다.
     *
     * 반대로 문항 식별자 보호(existsByForm)는 DRAFT를 **포함한다**. 기준이 다르므로 그쪽은 이
     * 집합을 쓰지 않는다.
     *
     * 매번 새로 만드는 것은 EnumSet이 가변이기 때문이다. 상수로 두면 호출부가 add/remove로
     * 전역 기준을 조용히 바꿀 수 있다.
     *
     * #141에서 CHANGES_REQUESTED가 들어왔다. 심사가 끝나지 않은 상태지만 응답자는 이미 냈고,
     * 빼면 수정요청을 누르는 순간 그 응답이 목록에서도 요약 숫자에서도 사라진다 — 운영자가
     * 자기 조작으로 응답을 잃어버리는 셈이다. 기준은 여전히 "응답자가 실제로 냈는가" 하나다.
     */
    public static EnumSet<ResponseStatus> submittedOrLater() {
        return EnumSet.of(SUBMITTED, CHANGES_REQUESTED, ACCEPTED, REJECTED);
    }

    /*
     * 낸 응답 중 **새로 내는 것을 막는** 상태들 (#192). 제출 이상에서 반려(REJECTED)만 빠진다.
     *
     * 단일 응답 폼(mltpl_rspns_yn = false)에서만 뜻이 있는 집합이다 — 다중 응답 폼은 어떤 응답이
     * 남아 있든 새로 낼 수 있으므로 이 판정을 부르지 않는다.
     *
     * **반려를 빼는 근거는 #141이 정한 반려의 뜻 그 자체다**: 반려는 그 응답에 대한 종결이지 그
     * 폼에 대한 종결이 아니고, 되돌릴 길은 번복이 아니라 새 응답이다. 그 탈출구가 그동안
     * 다중 응답 폼에서만 열려 있어(제출 경로가 "다중 응답 폼은 이 분기를 지나 새 응답이 된다"로만
     * 적혀 있었다) 단일 응답 폼에서 반려된 응답자는 새 초안도, 새 제출도, 재제출도 못 하는 상태로
     * 영구히 잠겼다 — 모집 폼은 전부 createEmptyDraft로 만들어져 단일 응답 폼이므로 반려된
     * 신청자에게 재신청 경로가 아예 없었다.
     *
     * submittedOrLater를 좁히지 않고 집합을 하나 더 두는 것은 두 집합이 묻는 것이 다르기
     * 때문이다 — 저쪽은 "응답자가 실제로 냈는가"(목록·집계·내 신청)이고 반려된 응답도 낸 것이
     * 맞다. 이쪽은 "지금 또 낼 수 있는가"다. 저쪽에서 반려를 빼면 반려된 신청이 운영자 목록과
     * 내 신청 목록에서 통째로 사라진다.
     *
     * 임시저장(DRAFT)이 없는 것은 이 판정을 부르는 자리들이 초안을 먼저 처리하기 때문이다 —
     * 초안은 막는 것이 아니라 이어 쓰는 것이라 "새로 낼 수 있는가"의 답이 아니다(새 초안 판정은
     * 초안이 없음을 확인한 뒤에 부르고, 제출은 초안이 있으면 그 행을 낸다).
     *
     * submittedOrLater와 같은 이유로 호출마다 새 EnumSet을 만든다.
     */
    public static EnumSet<ResponseStatus> blockingNewResponse() {
        return EnumSet.of(SUBMITTED, CHANGES_REQUESTED, ACCEPTED);
    }

    /*
     * 이 상태의 응답이 남아 있으면 (단일 응답 폼에서) 새 응답을 낼 수 없는가 (#192).
     *
     * 집합과 술어를 함께 두는 것은 쓰임이 둘이라서다 — 질의는 상태 집합을 넘겨야 하고
     * (existsByFormAndMemberAndStatusIn), 손에 든 응답을 거르는 쪽은 행마다 물어야 한다.
     * 판정 자체는 위 집합 하나이며 여기서 다시 나열하지 않는다.
     */
    public boolean blocksNewResponse() {
        return blockingNewResponse().contains(this);
    }

    public String code() {
        return name();
    }
}
