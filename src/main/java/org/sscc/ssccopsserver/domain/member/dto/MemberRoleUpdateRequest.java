package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;

/*
 * 회원 역할 배정 수정 요청 (#81 PATCH /v1/members/{memberId}/roles/{mbrRoleId}).
 *
 * **부분 수정이다 — null인 필드는 건드리지 않는다** (RoleUpdateRequest와 같은 방식). 대표 여부만
 * 바꾸는 요청이 종료일을 함께 지우면, 사이드바 표시를 고치려던 조작 하나가 이미 끝난 임기를
 * 되살려 인가 범위를 넓히게 된다.
 *
 * 그 대가로 **종료를 되돌리는 길은 이 경로에 없다** (JSON은 '필드 없음'과 'null'을 record로
 * 구별하지 못한다, AuthorityUpdateRequest 주석과 같은 사정). 잘못 종료한 배정은 기간이 겹치지
 * 않게 다시 부여하는 것이 정상 경로이며, 그래도 부족하다면 '종료 취소'를 별도 조작으로 여는
 * 것이 맞다 — 여기에 null의 두 번째 뜻을 얹지 않는다.
 *
 * 시작일(roleBgngYmd)은 받지 않는다. 시작일을 미래로 밀면 지금 유효한 역할이 조용히 사라지고,
 * 과거로 당기면 없던 기간이 생긴다 — 어느 쪽이든 '종료는 삭제가 아니다'가 지키려는 이력이
 * 수정으로 다시 흔들린다.
 */
public record MemberRoleUpdateRequest(LocalDate roleEndYmd, Boolean rprsRoleYn) {}
