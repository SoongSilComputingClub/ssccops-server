package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/*
 * 운영진의 회원 정보 수정 요청 (PATCH /v1/members/{mbrId}, #77).
 *
 * ── 없는 필드가 곧 계약이다 ────────────────────────────────────
 * 여기 없는 컬럼은 이 API로 바꿀 수 없다. 필드를 두지 않는 것이 유일하게 확실한 차단이며,
 * 받아 두고 무시하는 것은 "왜 안 바뀌지"를 남긴다.
 *
 * - mbr_grd_cd · mbr_stts_cd — 변경 이력(mbr_grd_hstry · mbr_stts_hstry)을 함께 남겨야 해
 *   전용 API가 따로 있다(#78). 여기서 열면 이력 없는 변경 경로가 생겨 감사가 끊긴다.
 * - stdnt_no — MemberEntity가 updatable = false로 잠가 두었고, 데이터사전도 '가입 후 변경
 *   불가'로 확정했다(ssccops#74). uk_mbr_student_number가 걸려 있어 조용히 바꿀 수도 없다.
 * - auth_user_id — 인증 주체가 정하는 값이다. 본문으로 받으면 남의 계정을 가져올 수 있다.
 * - join_ymd — 가입 시점의 사실이다. 이관 데이터 정정은 CSV 이관 기능과 함께 다룬다.
 * - mdfcn_dt — JPA Auditing이 채운다.
 *
 * eml은 **운영진 경로에만** 있다. 이관 회원의 잘못 적힌 이메일을 고칠 창구가 필요해서이며,
 * 본인 경로(MemberSelfUpdateRequest)에는 두지 않는다 — 그쪽 값은 Supabase 인증 계정에서
 * 오므로 본인이 바꾸면 로그인 계정과 갈린다.
 *
 * ── PATCH이지만 부분 갱신이 아니라 전체 교체(PUT 의미)다 ────────
 * 이슈가 제시한 세 갈래(Optional 래핑 · JsonNullable · 전체 교체) 중 전체 교체를 골랐고,
 * 근거는 셋이다.
 *
 * 1. record로는 '키 없음'과 '명시적 null'을 나눌 수 없다. Jackson이 빠진 생성자 인자에
 *    넣는 값(getAbsentValue)과 null에 넣는 값(getNullValue)이 Optional에서는 둘 다
 *    Optional.empty()라, Optional로 감싸도 구분은 생기지 않고 타입만 복잡해진다.
 * 2. JsonNullable은 별도 의존성(jackson-databind-nullable)을 들여야 한다. 이 프로젝트에
 *    그 의존성이 없고, 부분 갱신 하나를 위해 직렬화 스택에 모듈을 더할 만큼의 이득이 없다.
 * 3. 같은 판단의 선례가 이미 둘 있다 — PATCH /v1/authorities/{authrtCd}(AuthorityUpdateRequest)와
 *    PATCH /v1/sub-works/{subWorkId}(SubWorkUpdateRequest). 이 레포에서 PATCH는 "메서드만
 *    PATCH이고 본문은 한 벌 전체"로 굳어 있고, 회원만 다른 규칙을 쓰면 화면이 엔드포인트마다
 *    다르게 요청을 만들어야 한다.
 *
 * 그래서 **생략한 선택 필드는 '건드리지 마라'가 아니라 '지워라'로 읽힌다** — "학과를 지운다"는
 * departmentName을 빼거나 null로 보내면 표현된다. 화면은 수정 폼을 현재 값으로 채워 통째로
 * 되돌려 보내면 되고, 그것이 이 화면이 실제로 하는 일이다.
 *
 * generationNumber만 예외적으로 null이 '지움'이 아니라 **미배정(0)** 이다. gen_no가 NOT NULL
 * 이라 지울 자리가 없고, 0을 미배정 센티널로 쓰는 것은 가입 경로가 이미 하고 있는 일이다.
 *
 * 길이·범위는 데이터사전(SSoT)의 mbr 컬럼 그대로다.
 *
 * ── 재학 회원의 학과·학년 필수는 여기서 검사하지 않는다 ─────────
 * 그 규칙은 회원의 현재 상태(mbr_stts_cd)를 봐야 하는데, 상태는 이 API로 바꿀 수 없어
 * 요청 본문에 없다. 그래서 @AssertTrue로는 판단할 수 없고 회원을 읽은 뒤 서비스가
 * AcademicProfilePolicy(가입·CSV 이관과 같은 규칙)로 본다 —
 * 400 VALIDATION_FAILED(ACADEMIC_PROFILE_REQUIRED)다.
 */
public record MemberUpdateRequest(
        @PositiveOrZero Integer generationNumber,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 100) String departmentName,
        @Min(1) @Max(4) Integer academicYear,
        @Size(max = 20) String phoneNumber,
        @Size(max = 255) String email) {}
