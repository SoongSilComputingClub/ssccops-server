package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 회원 상태 변경 요청 (POST /v1/members/{memberId}/status-changes, #78).
 *
 * 변경자를 받지 않는 이유와 상태 코드를 문자열로 받는 이유는 MemberGradeChangeRequest와 같다.
 *
 * ── sttsEndPrnmntYmd(상태_종료_예정_일자)를 조용히 버리지 않는 이유 ──────────
 * 종료 예정일은 휴학·군휴학처럼 **끝이 정해진 상태**에만 뜻이 있다
 * (MemberStatusCode.allowsExpectedEndDate). 재학·졸업·탈퇴·제명에 실려 오면 400으로 거절한다.
 *
 * 조용히 버리는 쪽을 고르지 않은 것은 이력 행이 updatable = false로 잠겨 있기 때문이다 —
 * 버리면 운영자는 "2026-03-01 복학 예정"을 적어 넣었다고 믿는데 어디에도 남지 않고, 뒤늦게
 * 발견해도 그 행에 값을 채워 넣을 경로가 없다. 폼 도메인이 유형과 무관한 잔여 속성을
 * 거절하지 않고 정리(QuestionCompositionValidator)하는 것과 갈리는데, 그쪽은 화면이 남긴
 * 찌꺼기라 사람이 입력한 적이 없고 이쪽은 사람이 직접 친 날짜다.
 */
public record MemberStatusChangeRequest(
        @NotBlank(message = "변경할 상태 코드는 필수입니다.") String aftrMbrSttsCd,
        LocalDate sttsAplcnYmd,
        LocalDate sttsEndPrnmntYmd,
        @Size(max = 500, message = "상태 변경 사유는 500자 이하여야 합니다.") String sttsChgRsnCn) {}
