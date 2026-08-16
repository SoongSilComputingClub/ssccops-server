package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

/*
 * 회원 상태 변경 응답 (#78).
 *
 * 모양은 MemberGradeChangeResponse와 같다(변경 후 회원 상세 + 경고). record를 하나로 합치지
 * 않은 것은 두 엔드포인트의 계약이 같은 값을 우연히 공유하고 있을 뿐이기 때문이다 — 상태
 * 변경에는 종료 예정일처럼 등급에 없는 개념이 있어 이쪽만 늘어날 여지가 있다.
 *
 * warnings는 탈퇴(WITHDRAWN)·제명(EXPELLED)으로 전이할 때만 채워지며, 남은 것이 없으면
 * 그때도 빈 목록이다. 자세한 배경은 MemberChangeWarningResponse 주석에 있다.
 */
public record MemberStatusChangeResponse(
        MemberDetailResponse member, List<MemberChangeWarningResponse> warnings) {}
