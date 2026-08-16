package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.code.MemberImportRowStatus;

/*
 * CSV 이관 2단계 — 전량 검증 결과 (POST /v1/members/imports/validation, #84).
 *
 * fileToken은 파일 내용의 SHA-256("sha256:...")이다. 실행 API(#85)가 이 값을 되돌려 주게 해서,
 * 화면에서 확인한 파일과 실제로 넣는 파일이 같은 것임을 서버가 확인할 수 있게 한다 —
 * 검증과 실행 사이에 파일을 바꿔치기하면 운영자가 본 적 없는 128건이 들어간다.
 * 파일 이름이 아니라 내용의 해시인 것은 같은 이름의 다른 파일이 흔하기 때문이다.
 *
 * rows에는 **모든 행**을 담는다(OK도 포함). 오류 행만 내리면 화면이 "13행은 통과했는가 아니면
 * 결과에서 빠졌는가"를 알 수 없다.
 */
public record MemberImportValidationResponse(
        String fileToken, MemberImportSummary summary, List<MemberImportRowResult> rows) {

    /*
     * 요약. okCount·errorCount·duplicateCount는 서로 겹치지 않으며 합이 totalCount다
     * (MemberImportRowStatus의 세 값에 그대로 대응한다).
     *
     * warningCount만 그 관계 밖이다 — 경고가 있는 행도 status는 OK라 okCount에 이미 들어 있고,
     * 여기서 다시 빼면 합이 무너진다. "119건 중 7건은 연락처가 없다"를 화면에 보이기 위한 값이지
     * 네 번째 버킷이 아니다.
     *
     * 그래서 **warningCount ⊆ okCount**이며 이 포함 관계가 이 값을 읽을 수 있게 하는 전부다.
     * 경고를 OK인 행에만 싣는 것(MemberImportValidator)이 그 관계를 지킨다 — 이관되지 않는 행까지
     * 세면 warningCount는 어느 집합의 부분집합인지 말할 수 없는 숫자가 된다 (#109).
     */
    public record MemberImportSummary(
            int totalCount, int okCount, int errorCount, int duplicateCount, int warningCount) {

        public static MemberImportSummary of(List<MemberImportRowResult> rows) {
            int ok = 0;
            int error = 0;
            int duplicate = 0;
            int warning = 0;

            for (MemberImportRowResult row : rows) {
                if (row.status() == MemberImportRowStatus.ERROR) {
                    error++;
                } else if (row.status() == MemberImportRowStatus.DUPLICATE) {
                    duplicate++;
                } else {
                    ok++;
                }
                if (!row.warnings().isEmpty()) {
                    warning++;
                }
            }
            return new MemberImportSummary(rows.size(), ok, error, duplicate, warning);
        }
    }
}
