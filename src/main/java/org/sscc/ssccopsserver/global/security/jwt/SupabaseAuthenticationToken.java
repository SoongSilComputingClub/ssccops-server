package org.sscc.ssccopsserver.global.security.jwt;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * Supabase JWT 인증 결과. principal은 원본 Jwt가 아니라 매핑된 MemberEntity로 둔다 —
 * 이후 계층(향후 AOP 기반 role 인가 등)이 SecurityContext에서 바로 MemberEntity를 꺼내 쓸 수 있게 하기 위함.
 * 권한(GrantedAuthority)은 비워둔다 — role 인가는 별도 AOP에서 처리할 예정이다.
 */
public class SupabaseAuthenticationToken extends AbstractAuthenticationToken {

    private final MemberEntity member;
    private final Jwt jwt;

    public SupabaseAuthenticationToken(MemberEntity member, Jwt jwt) {
        super(List.of());
        this.member = member;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return member;
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public String getName() {
        return String.valueOf(member.getId());
    }
}
