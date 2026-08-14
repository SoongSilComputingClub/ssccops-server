package org.sscc.ssccopsserver.domain.auth.dto;

import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

/*
 * 로그인 세션. 프론트가 관리자 화면 진입 시 가장 먼저 호출하는 응답이다.
 *
 * 미가입 사용자에게도 200으로 응답한다 — "가입이 필요하다"는 것도 정상적인 세션 상태이지
 * 오류가 아니기 때문이다. 프론트는 signedUp 하나로 대시보드와 가입 화면을 가른다.
 * 미가입이면 member는 null이고, 그때 쓸 값은 authUser에 들어 있다.
 */
public record AuthSessionResponse(
        boolean signedUp, AuthUserResponse authUser, MemberProfileResponse member) {

    public static AuthSessionResponse of(AuthenticatedUser user, MemberProfileResponse member) {
        return new AuthSessionResponse(member != null, AuthUserResponse.from(user), member);
    }
}
