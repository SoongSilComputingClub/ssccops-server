package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;

/*
 * 회원명부 미리보기 (GET /v1/members/roster-export/preview · #676).
 *
 * 내려받기와 **같은 조건**으로 «이 파일에 무엇이 들어가는가»를 숫자로 먼저 보여 준다. 회원 16명에
 * 명부 8줄이 나와 «DB와 숫자가 안 맞는다»는 질문이 나왔는데(2026-10-09), 원인은 임시회원 제외라는
 * 고정 규칙이었다 — 그 차이를 화면이 내려받기 전에 말하게 하려는 것이다.
 *
 * - baseDate — 명단의 기준일. 서버의 오늘(주입된 Clock)이며 회장·부회장 판정도 이 날을 본다. 연도·학기를
 *   무엇으로 골라도 이 값은 같다 — 화면이 «명단은 오늘 기준»을 날짜로 보여 줄 재료다. 화면이 제 시계로
 *   세지 않는 것은 자정 무렵 두 시계가 하루 갈릴 수 있어서다.
 * - title · fileName — 내려받을 파일의 제목(A1)과 이름. 서버가 정한 그대로라 화면이 흉내 내지 않는다.
 *   연도·학기가 명단이 아니라 여기에만 쓰인다는 것을 화면이 보여 줄 재료이기도 하다.
 * - rowCount — 명부에 오를 줄 수. officerCount(회장·부회장)를 포함한다.
 * - excludedTemporaryCount — 임시회원(TEMP)이라 빠지는 회원. 회장·부회장은 임시회원이어도 들어가므로
 *   세지 않는다. 고른 상태와 무관하게 센다 — 상태를 넓혀도 들어오지 않는 사람이 이 숫자다.
 * - excludedByStatusCount — 고르지 않은 상태라 빠지는 회원(임시회원은 위에서 셌다).
 * - totalMemberCount — 전체 회원. 언제나 rowCount + excludedTemporaryCount + excludedByStatusCount다.
 * - presidentMissing — 오늘 유효한 회장이 없다. 내려받기는 409 ROSTER_PRESIDENT_MISSING으로 거절되지만
 *   미리보기는 거절하지 않고 이 값으로 알린다 — 숫자는 그래도 보여 줄 수 있고, 거절은 누르는 순간
 *   내려받기가 다시 판정한다.
 *
 * 미리보기는 약속이 아니다 — 이 응답과 내려받기 사이에 회원이 바뀔 수 있다
 * (MemberDeletionPreviewResponse와 같다).
 */
public record MemberRosterPreviewResponse(
        LocalDate baseDate,
        String title,
        String fileName,
        long rowCount,
        long officerCount,
        long excludedTemporaryCount,
        long excludedByStatusCount,
        long totalMemberCount,
        boolean presidentMissing) {}
