package org.sscc.ssccopsserver.domain.member.code;

import java.util.Arrays;

/*
 * 학술 프로그램의 리더 역할 셋 — **운영진이 아니다** (ssccops#611 · #687).
 *
 * 기획안이 승인되면 제출자에게 이 역할이 종료일 없이 붙는다(학술 도메인의
 * AcademicProgramApprovalEffectsServiceImpl). 리더가 자기 프로그램을 다루는 자격은 역할이 아니라
 * `acdm_actv.leadr_mbr_id` 비교(AcademicProgramOwnershipPolicy)에서 나오므로 **이 역할에 줄 권한은
 * 없다.** 그런데 그 규칙이 주석(V3·V19·AuthorityCode)에만 있어 prod에서 스터디장 역할에
 * ACADEMIC_PROGRAM_MANAGE가 붙었고, 그 순간 승인 한 번이 ROLE_MANAGE를 거치지 않고 학술국장
 * 권한을 나눠 주는 길이 됐다. 그래서 규칙을 LeaderRoleGuard가 강제하고, 이름은 여기 한 곳에 둔다.
 *
 * **이름으로 가리킨다.** 역할의 정체를 코드로 가리킬 컬럼(역할 코드·시스템 여부)이 role에 없고
 * 그것을 더하는 것은 스키마 변경이라 데이터사전이 먼저다(ADR-0062) — 대신 이 셋은 이름을 바꾸거나
 * 지울 수 없게 막는다(LeaderRoleGuard). 승인이 역할을 이름으로 찾으므로 이름이 곧 열쇠다.
 *
 * 학술 도메인의 «유형 코드 → 리더 역할» 맵은 이 상수를 가리킨다 — 이름이 두 벌이면 한쪽만 고쳐진
 * 날 가드가 모르는 역할을 승인이 내준다. 유형이 늘면 여기에 한 줄을 더하고 V{n} 시드에 같은
 * 이름으로 역할을 넣는다(V19가 트랙장을 넣은 것과 같다).
 */
public enum LeaderRole {
    STUDY_LEADER("스터디장"),
    PROJECT_LEADER("프로젝트장"),
    TRACK_LEADER("트랙장");

    private final String roleName;

    LeaderRole(String roleName) {
        this.roleName = roleName;
    }

    public String roleName() {
        return roleName;
    }

    public static boolean isLeaderRoleName(String roleName) {
        return Arrays.stream(values()).anyMatch(role -> role.roleName.equals(roleName));
    }
}
