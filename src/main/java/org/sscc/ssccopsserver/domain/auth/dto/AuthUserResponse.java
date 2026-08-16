package org.sscc.ssccopsserver.domain.auth.dto;

import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

/*
 * 소셜 인증 계정 정보. mbr이 아니라 JWT에서 온 값이다.
 *
 * 아직 가입하지 않은 사용자도 이 블록은 채워지며, 프론트는 이 값으로 가입 화면의 이름·이메일을
 * 미리 채운다. 가입 이후에는 회원 정보(member)가 정본이므로 표시에 쓰지 않는다 —
 * 소셜 계정 이메일과 회원 이메일이 달라질 수 있다.
 */
public record AuthUserResponse(String id, String email, String name, String provider) {

    public static AuthUserResponse from(AuthenticatedUser user) {
        return new AuthUserResponse(
                user.authUserId().toString(), user.email(), user.name(), user.provider());
    }
}
