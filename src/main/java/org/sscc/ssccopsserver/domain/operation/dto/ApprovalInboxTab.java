package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.Arrays;
import java.util.Set;

import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 승인함 화면의 탭 (OPS-017 · #47). `대기` · `승인` · `반려` 세 개다.
 *
 * 탭 이름과 승인 상태가 1:1이 아니라서 enum으로 못 박는다.
 *  - 대기: 대기(PENDING)와 재승인필요(REAPPROVAL_REQUIRED)를 함께 담는다. 반려 후 다시
 *    올라온 건도 승인자 입장에서는 처리해야 할 건이다(OPS-008의 '승인대기' 칩과 같은 규칙).
 *    **업무 상태까지 함께 건다** — 승인이 필요한 하위 업무는 등록 시점부터 PENDING이라
 *    승인 상태만 보면 아직 검토요청도 하지 않은 건이 승인함에 뜬다.
 *  - 승인·반려: 이미 처리가 끝난 건이라 업무 상태를 걸지 않는다. 반려된 건은 진행으로
 *    되돌아가 있고 다시 올라오면 승인 상태가 재승인필요로 바뀌어 대기 탭으로 옮겨간다.
 *
 * 승인 불필요(NOT_REQUIRED)는 어느 탭에도 없다 — 승인 절차를 아예 타지 않는다.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum ApprovalInboxTab {
    PENDING(WorkStatus.REVIEW, Set.of(ApprovalStatus.PENDING, ApprovalStatus.REAPPROVAL_REQUIRED)),
    APPROVED(null, Set.of(ApprovalStatus.APPROVED)),
    REJECTED(null, Set.of(ApprovalStatus.REJECTED));

    public static final ApprovalInboxTab DEFAULT = PENDING;

    private final WorkStatus workStatus;
    private final Set<ApprovalStatus> approvalStatuses;

    /*
     * 값을 생략하면 대기 탭이다 — 화면이 처음 열릴 때 보는 탭이 그것이다.
     * 목록에 없는 값은 조용히 기본값으로 떨어뜨리지 않고 INVALID_CODE_VALUE(400)로 돌려준다
     * (SubWorkSortOrder.from과 같은 이유 — 오타 난 필터로 받은 목록을 화면이 그대로 그린다).
     */
    public static ApprovalInboxTab from(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(tab -> tab.name().equals(value.strip()))
                .findFirst()
                .orElseThrow(() -> new GeneralException(CommonErrorCode.INVALID_CODE_VALUE));
    }
}
