package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/*
 * 회원명부 내보내기 조건 (#674 · ssccops#598). 쿼리 문자열이라 @ModelAttribute로 받는다.
 *
 * - year · semester — 제목(«2026년도 2학기 …»)과 파일 이름에만 쓴다. 서버에는 학기 개념이 없어
 *   (기수도 연 단위다 · GenerationPolicy) 요청이 정한다. 상한·하한은 오타(20266)를 막는 정도다.
 * - mbrSttsCd — **포함할** 회원 상태 코드. 이름은 회원 목록의 상태 필터(MemberSearchCondition)와
 *   같다. 비어 있으면 재학 하나다. 기준 코드 검사는 서비스가 mbr_stts를 보고 한다 — 화면의
 *   선택지가 GET /v1/member-statuses(테이블)에서 오므로 enum으로 검사하면 테이블에 더한 상태가
 *   화면에는 보이는데 내려받기는 400이 된다.
 * - positionNotation — FEDERATION(기본) | SSCC. 해석은 RosterPositionNotation.from이 한다.
 */
public record MemberRosterExportCondition(
        @NotNull(message = "year는 필수입니다.")
                @Min(value = 2000, message = "year는 2000 이상이어야 합니다.")
                @Max(value = 2999, message = "year는 2999 이하여야 합니다.")
                Integer year,
        @NotNull(message = "semester는 필수입니다.")
                @Min(value = 1, message = "semester는 1 또는 2여야 합니다.")
                @Max(value = 2, message = "semester는 1 또는 2여야 합니다.")
                Integer semester,
        List<String> mbrSttsCd,
        String positionNotation) {}
