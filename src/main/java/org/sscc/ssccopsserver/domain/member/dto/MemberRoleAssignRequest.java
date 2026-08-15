package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/*
 * 회원 역할 부여 요청 (#81 POST /v1/members/{memberId}/roles).
 *
 * **종료일을 받지 않는다.** 부여는 언제나 무기한으로 시작하고, 임기가 끝나면 PATCH로 종료일을
 * 채운다. 부여 시점에 끝날 날을 함께 정하는 화면이 없기도 하지만, 더 큰 이유는 그 값을 받으면
 * "이미 끝난 역할을 만드는" 요청이 정상 경로가 되기 때문이다 — 겹침 판정이 새 배정을 항상
 * [시작일, ∞)로 볼 수 있는 것도 여기서 나온다 (MemberRoleAssignmentRepository 주석 참고).
 *
 * roleBgngYmd를 생략하면 오늘이다(주입된 Clock 기준). 과거 날짜는 막지 않는다 — 이미 맡고 있던
 * 역할을 뒤늦게 시스템에 반영하는 것이 이관 초기의 정상적인 조작이다.
 *
 * rprsRoleYn을 생략하면 false다. true로 주면 그 회원의 기존 대표 역할이 같은 트랜잭션에서
 * 내려간다 — 대표는 회원당 유효한 것 중 최대 1건이기 때문이다. 이 값은 사이드바 표시용이며
 * 인가 판정에 쓰이지 않는다 (BR-M26).
 */
public record MemberRoleAssignRequest(
        @NotNull Long roleId, LocalDate roleBgngYmd, Boolean rprsRoleYn) {}
