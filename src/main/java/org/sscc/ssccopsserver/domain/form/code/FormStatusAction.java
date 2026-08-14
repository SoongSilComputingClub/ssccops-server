package org.sscc.ssccopsserver.domain.form.code;

import java.util.EnumSet;
import java.util.Set;

/*
 * 폼 접수 상태 전이 액션 (#33 · POST /v1/forms/{formId}/status).
 *
 * 클라이언트가 보내는 것은 "어떤 상태로 바꿔라"가 아니라 "무엇을 하겠다"다. 다음 상태를 직접
 * 받으면 전이표에 없는 상태로 보내는 요청을 매번 걸러야 하고, 전이표가 바뀔 때 계약도 함께
 * 바뀐다. 다음 상태는 여기 적힌 표가 정한다 (SubWorkController의 TransitionAction 선례).
 *
 * 전이표는 이슈 #33의 표 그대로다.
 *
 *   현재 \ 액션 | OPEN            | CLOSE
 *   DRAFT       | → OPEN          | ✕
 *   OPEN        | ✕ (이미 열림)    | → CLOSED
 *   CLOSED      | → OPEN (마감 철회) | ✕ (이미 마감)
 *
 * CLOSED → OPEN을 허용하는 것은 마감 버튼을 잘못 누른 운영자가 되돌릴 방법이 있어야 하기
 * 때문이다. 마감은 응답을 지우지 않으므로 다시 열어도 잃는 것이 없다 — 되돌릴 수 없게 만들면
 * 폼을 복제해 새로 여는 우회가 생기고, 그러면 응답이 두 폼으로 갈린다.
 *
 * 표를 서비스의 if 분기가 아니라 enum에 두는 것은 후속 이슈(#35·#36)가 상태를 읽을 때
 * 같은 표를 다시 옮겨 적지 않게 하기 위해서다. 기준 코드 밖의 값은 enum 역직렬화 단계에서
 * 걸러져 INVALID_CODE_VALUE(400)가 된다 (VL-09).
 */
public enum FormStatusAction {

    /** 접수 시작 — 작성 중이거나 마감된 폼을 연다 */
    OPEN(FormStatus.OPEN, EnumSet.of(FormStatus.DRAFT, FormStatus.CLOSED)),

    /** 마감 — 접수 중인 폼을 닫는다 */
    CLOSE(FormStatus.CLOSED, EnumSet.of(FormStatus.OPEN));

    private final FormStatus targetStatus;
    private final Set<FormStatus> allowedFromStatuses;

    FormStatusAction(FormStatus targetStatus, Set<FormStatus> allowedFromStatuses) {
        this.targetStatus = targetStatus;
        this.allowedFromStatuses = allowedFromStatuses;
    }

    /** 이 액션이 현재 상태에서 허용되는가. 판단만 하고 오류는 던지지 않는다 — 던지는 자리는 FormEntity다 */
    public boolean isAllowedFrom(FormStatus currentStatus) {
        return allowedFromStatuses.contains(currentStatus);
    }

    public FormStatus targetStatus() {
        return targetStatus;
    }

    /*
     * 접수를 여는 액션인가. 문항 0개 금지·접수 기간 정합성 같은 사전 검증은 여는 쪽에만
     * 걸린다 — 마감은 폼의 내용과 무관하게 언제나 안전한 방향이다.
     */
    public boolean opensReceipt() {
        return this == OPEN;
    }
}
