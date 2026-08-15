package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 본인의 회원 정보 수정 요청 (PATCH /v1/members/me, #77).
 *
 * 운영진 경로(MemberUpdateRequest)와 **DTO를 나눈 것 자체가 권한 차이의 표현이다.** 한 record를
 * 두 경로가 나눠 쓰면서 서비스에서 "본인이면 이 필드는 무시"로 거르면, 그 분기 하나가 무너지는
 * 날 본인이 자기 기수를 올리거나 이메일을 갈아 끼운다. 필드를 아예 두지 않으면 그 경로가 없다.
 *
 * 운영진 경로에 있고 여기 없는 것:
 * - gen_no — 기수는 운영진이 배정하는 값이다. 본인이 정하면 배정 자체가 뜻을 잃는다.
 * - eml — Supabase 인증 계정에서 오는 값이라 본인이 바꾸면 로그인 계정과 갈린다.
 *   (화면도 이 값을 읽기 전용으로 표시한다 — 가입 화면과 같다.)
 *
 * 두 경로 모두에 없는 것(등급·상태·학번·auth_user_id·join_ymd)의 근거는 MemberUpdateRequest
 * 주석에 적어 두었다.
 *
 * ── 대상은 경로로 정해진다 ─────────────────────────────────────
 * memberId를 본문에도 경로에도 두지 않는다. 대상은 언제나 인증 주체 본인(@CurrentMember)이며,
 * **넣을 자리를 만들지 않는 것이 남의 행에 닿는 경로를 막는 방법이다** (응답 자동 저장 #36의
 * /responses/draft가 mbrId를 두지 않는 것과 같은 판단).
 *
 * 부분 갱신 의미(PATCH이지만 전체 교체)와 학적 검증 위치는 MemberUpdateRequest와 같다 —
 * 근거는 그쪽 주석에 한 번만 적는다.
 */
public record MemberSelfUpdateRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 100) String departmentName,
        @Min(1) @Max(4) Integer academicYear,
        @Size(max = 20) String phoneNumber) {}
