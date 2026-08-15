package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

/*
 * 회원 등급 변경 응답 (#78).
 *
 * 회원 표현을 새로 만들지 않고 #76의 MemberDetailResponse를 그대로 싣는다 — 변경 직후 화면이
 * 다시 상세를 조회하지 않아도 되게 하려는 것이고, 여기에만 있는 축약형을 두면 같은 값의 경로가
 * 상세와 갈린다 (가입 응답이 MemberProfileResponse를 재사용하는 것과 같은 판단).
 * member.recentChanges의 맨 앞에 방금 남긴 이력이 들어 있으므로 변경 결과를 따로 싣지 않는다.
 *
 * ── warnings가 등급 변경에도 있는 이유 ─────────────────────────
 * 지금 이 목록은 **언제나 비어 있다.** 경고는 조직을 떠나는 전이(탈퇴·제명)에서만 생기고
 * 그것은 상태 변경의 일이다. 그런데도 필드를 두는 것은 웹의 등급·상태 변경 시트가 한 컴포넌트
 * (grade-status-sheet.tsx)여서 두 응답을 같은 처리로 받기 때문이다 — 한쪽에만 키가 없으면
 * 그 컴포넌트가 엔드포인트별 분기를 하나 더 갖게 된다.
 */
public record MemberGradeChangeResponse(
        MemberDetailResponse member, List<MemberChangeWarningResponse> warnings) {}
