package org.sscc.ssccopsserver.domain.event.dto;

/*
 * 참가자 등록·전이 응답에 함께 실리는 경고 한 줄 (ssccops#146 · §8-5).
 *
 * **경고는 요청을 막지 않는다.** 오류가 아니라 200/201 응답에 실리는 사실이며, 회원 등급·상태
 * 변경(#78)의 MemberChangeWarningResponse와 같은 패턴이다.
 *
 * 탈퇴·제명 회원의 등록을 거절하지 않는 이유는 그 판단이 운영 규칙이기 때문이다 — 졸업생
 * 홈커밍처럼 조직을 떠난 사람이 참가자인 것이 정상인 행사가 있고, 반대로 모집 행사라면
 * 잘못 고른 것이다. 서버가 어느 쪽인지 알 수 없으므로 막는 대신 사람이 보게 한다.
 *
 * 정원 초과는 여기 담지 않는다(D5). 그쪽은 숫자로 답해야 하는 사실이라 응답 본문에
 * confirmedCount·ptcpLmtCnt·capacityExceeded로 따로 실린다 — 화면이 "12/10"을 그리려면
 * 문구가 아니라 값이 필요하다.
 */
public record EventParticipantWarningResponse(String code, String message) {

    /** 탈퇴한 회원을 명단에 올렸다 */
    public static final String MEMBER_WITHDRAWN = "MEMBER_WITHDRAWN";

    /** 제명된 회원을 명단에 올렸다 */
    public static final String MEMBER_EXPELLED = "MEMBER_EXPELLED";

    public static EventParticipantWarningResponse memberWithdrawn() {
        return new EventParticipantWarningResponse(MEMBER_WITHDRAWN, "탈퇴한 회원입니다.");
    }

    public static EventParticipantWarningResponse memberExpelled() {
        return new EventParticipantWarningResponse(MEMBER_EXPELLED, "제명된 회원입니다.");
    }
}
