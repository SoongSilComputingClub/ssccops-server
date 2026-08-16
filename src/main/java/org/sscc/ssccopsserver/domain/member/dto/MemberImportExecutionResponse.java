package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.code.MemberImportExecutionStatus;

/*
 * CSV 이관 3단계 — 실행 결과 (POST /v1/members/imports, #85).
 *
 * rows에는 **모든 행**을 담는다(CREATED도 포함). 검증 응답과 같은 태도이며, 실패한 행만 내리면
 * 화면이 "13행은 들어갔는가 아니면 결과에서 빠졌는가"를 알 수 없다.
 */
public record MemberImportExecutionResponse(
        MemberImportExecutionSummary summary, List<MemberImportExecutionRow> rows) {

    /*
     * 요약. createdCount·skippedCount·failedCount는 서로 겹치지 않으며 합이 totalCount다.
     *
     * ── reimportDuplicatesCount는 그 관계 밖이다 ────────────────────────
     * **이 API는 멱등하지 않다.** 같은 파일을 두 번 실행하면 학번이 있는 행은 전부 SKIPPED가 되지만
     * (mbr에 이미 그 학번이 있으므로), **학번이 없는 졸업 회원 행은 중복이라고 판정할 근거가 아예
     * 없어 두 번 들어간다.** 이름이 같다고 같은 사람이라 볼 수 없고, 이관 배치를 기록할 테이블은
     * 데이터사전에 없다(등재가 선행이다).
     *
     * 그래서 이번 실행에서 **학번 없이 새로 들어간 행의 수**를 따로 센다 — 재실행 시 그대로 중복
     * 등록될 행의 수이며, 위저드가 "이 중 N건은 다시 실행하면 또 들어갑니다"로 경고하는 근거다.
     * createdCount에 이미 포함돼 있으므로 여기서 다시 빼면 세 버킷의 합이 무너진다
     * (검증 요약의 warningCount와 같은 자리).
     */
    public record MemberImportExecutionSummary(
            int totalCount,
            int createdCount,
            int skippedCount,
            int failedCount,
            int reimportDuplicatesCount) {

        public static MemberImportExecutionSummary of(
                List<MemberImportExecutionRow> rows, int reimportDuplicatesCount) {

            int created = 0;
            int skipped = 0;
            int failed = 0;

            for (MemberImportExecutionRow row : rows) {
                if (row.status() == MemberImportExecutionStatus.CREATED) {
                    created++;
                } else if (row.status() == MemberImportExecutionStatus.SKIPPED) {
                    skipped++;
                } else {
                    failed++;
                }
            }
            return new MemberImportExecutionSummary(
                    rows.size(), created, skipped, failed, reimportDuplicatesCount);
        }
    }
}
