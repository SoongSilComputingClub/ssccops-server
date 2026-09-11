package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.code.MemberBulkChangeStatus;

/*
 * 회원 등급·상태 일괄 변경의 응답 (#338).
 *
 * 등급과 상태가 **같은 record**를 쓴다. 한 명짜리 응답이 MemberGradeChangeResponse와
 * MemberStatusChangeResponse로 나뉘어 있는 것과 갈리는데, 그쪽이 나뉜 이유는 "상태에는 종료
 * 예정일처럼 등급에 없는 개념이 있어 이쪽만 늘어날 여지가 있다"였다. 그 여지는 **요청**에만
 * 있다 — 결과 표는 어느 쪽이든 "누가 · 어떻게 됐고 · 왜"뿐이고, 웹의 결과 시트도 하나다.
 *
 * rows에는 **요청한 모든 회원**을 담는다(CHANGED도 포함). CSV 이관 실행 응답과 같은 태도이며,
 * 바뀌지 않은 회원만 내리면 화면이 "이 사람은 바뀐 건가 결과에서 빠진 건가"를 알 수 없다.
 * 순서는 요청에 실려 온 순서 그대로다 — 화면이 자기가 보낸 목록과 나란히 놓고 읽는다.
 */
public record MemberBulkChangeResponse(
        MemberBulkChangeSummary summary, List<MemberBulkChangeRow> rows) {

    /*
     * 요약. 세 버킷은 서로 겹치지 않으며 합이 totalCount다.
     *
     * ── totalCount는 요청한 id 수가 아니라 **접은 뒤의 인원**이다 ──────
     * 같은 회원이 목록에 두 번 실려 오면 앞의 것만 남기므로(MemberBulkChangeServiceImpl)
     * 요청의 mbrIds보다 작을 수 있다. 요청 크기를 그대로 세면 "30명 중 30건 처리"라고 해 놓고
     * 실제로는 29명만 손댄 응답이 되어, 화면이 세는 인원과 이력에 남은 인원이 갈린다.
     *
     * changedCount만 이력이 남은 건수다. skippedCount는 **이미 그 값이었던 회원**이라 아무것도
     * 남지 않았고(MemberBulkChangeStatus 주석), failedCount는 손대지 못한 회원이다.
     * 화면이 "30명 중 22명 변경 · 8명 이미 해당 등급"으로 그릴 수 있게 셋을 따로 센다.
     */
    public record MemberBulkChangeSummary(
            int totalCount, int changedCount, int skippedCount, int failedCount) {

        public static MemberBulkChangeSummary of(List<MemberBulkChangeRow> rows) {
            int changed = 0;
            int skipped = 0;
            int failed = 0;

            for (MemberBulkChangeRow row : rows) {
                if (row.status() == MemberBulkChangeStatus.CHANGED) {
                    changed++;
                } else if (row.status() == MemberBulkChangeStatus.SKIPPED) {
                    skipped++;
                } else {
                    failed++;
                }
            }
            return new MemberBulkChangeSummary(rows.size(), changed, skipped, failed);
        }
    }

    public static MemberBulkChangeResponse of(List<MemberBulkChangeRow> rows) {
        return new MemberBulkChangeResponse(MemberBulkChangeSummary.of(rows), List.copyOf(rows));
    }
}
