package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 회원 등급 변경 요청 (POST /v1/members/{memberId}/grade-changes, #78).
 *
 * **변경자(chnrg_mbr_id)를 두지 않는다.** 요청 본문으로 받으면 "누가 바꿨는가"를 요청자가
 * 스스로 적어 넣을 수 있어 이력이 증거가 되지 못한다. 변경자는 @CurrentMember로 인증 주체에서
 * 가져온다 (LY-05 — 하위 업무 전이의 performer와 같은 자리).
 *
 * 등급 코드를 MemberGradeCode가 아니라 문자열로 받는 것은 MemberSearchCondition과 같은
 * 이유다. 바인딩 단계에서 enum 변환이 실패하면 스프링이 '형식 오류'로 묶어
 * VALIDATION_FAILED(400)를 내는데, 기준 코드 위반은 INVALID_CODE_VALUE(400)여야 프론트가
 * 둘을 나눠 안내할 수 있다.
 *
 * 필드명이 데이터사전의 컬럼명(aftr_mbr_grd_cd·grd_aplcn_ymd·grd_chg_rsn_cn)을 따르는 것은
 * 이슈 #78의 API 계약 그대로이며, 상태 변경 요청과 어휘를 맞추기 위해서다.
 *
 * grdAplcnYmd를 생략하면 서버의 오늘(주입된 Clock)이 된다 — 화면이 날짜를 고르지 않고
 * '지금 승급'만 누르는 경우가 대부분이라, 클라이언트가 자기 시각으로 오늘을 채워 보내면
 * 시간대가 다른 기기에서 하루 어긋난 이력이 남는다. 미래 일자는 400 VALIDATION_FAILED다
 * (아직 일어나지 않은 변경을 지금 적용해 버리면 mbr과 이력이 어긋난다).
 */
public record MemberGradeChangeRequest(
        @NotBlank(message = "변경할 등급 코드는 필수입니다.") String aftrMbrGrdCd,
        LocalDate grdAplcnYmd,
        @Size(max = 500, message = "등급 변경 사유는 500자 이하여야 합니다.") String grdChgRsnCn) {}
