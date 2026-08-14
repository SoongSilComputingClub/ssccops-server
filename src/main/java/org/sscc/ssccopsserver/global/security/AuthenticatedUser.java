package org.sscc.ssccopsserver.global.security;

import java.util.UUID;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * Supabase Auth로 인증된 사용자. SupabaseAuthenticationToken의 principal이다.
 *
 * 연결된 회원(member)은 있을 수도, 없을 수도 있다 — 로그인만 하고 아직 회원가입을 하지 않은
 * 사용자를 표현해야 하기 때문이다. 인증 시점에는 mbr을 만들지 않으며, mbr 행이 생기는 유일한
 * 경로는 회원가입 API다. 따라서 "가입 전 상태"는 별도 등급 코드가 아니라 member == null 로 표현된다.
 *
 * authUserId/email/name/provider는 JWT에서 온 값이라 mbr 컬럼과 다를 수 있다 — 가입 화면
 * 프리필과 소셜 계정 표시에만 쓰고, 회원 정보가 필요하면 member를 봐야 한다.
 */
public record AuthenticatedUser(
        UUID authUserId, String email, String name, String provider, MemberEntity member) {

    public boolean isSignedUp() {
        return member != null;
    }
}
