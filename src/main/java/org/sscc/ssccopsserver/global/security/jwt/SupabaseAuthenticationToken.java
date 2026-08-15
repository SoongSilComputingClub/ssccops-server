package org.sscc.ssccopsserver.global.security.jwt;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

/*
 * Supabase JWT 인증 결과. principal은 원본 Jwt가 아니라 AuthenticatedUser다 —
 * 이후 계층이 SecurityContext에서 바로 인증 사용자와 연결된 회원을 꺼내 쓸 수 있게 하기 위함이다.
 *
 * 회원이 필요한 엔드포인트는 principal을 직접 캐스팅하지 말고 @CurrentMember로 받는다.
 * 미가입 사용자를 SIGNUP_REQUIRED로 걸러내는 책임이 그 리졸버 한 곳에 모여 있다.
 *
 * 권한(GrantedAuthority)은 비워둔다. 인가는 요청 시점에 @RequireAuthority + AOP가 DB를 보고
 * 판정하므로(#9) 인증 시점에 굳힐 것이 없다 — 굳히면 권한이 필요 없는 요청에도 조회가 붙고,
 * 화면에서 역할·권한이 바뀌어도 이미 발급된 토큰의 판정이 따라오지 않는다.
 */
public class SupabaseAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;
    private final Jwt jwt;

    public SupabaseAuthenticationToken(AuthenticatedUser principal, Jwt jwt) {
        super(List.of());
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    /*
     * 미가입 사용자는 mbr_id가 없으므로 회원 식별자가 아니라 인증 사용자 식별자를 이름으로 쓴다.
     * 가입 여부와 무관하게 항상 값이 있어야 로그·감사 추적이 끊기지 않는다.
     */
    @Override
    public String getName() {
        return principal.authUserId().toString();
    }
}
