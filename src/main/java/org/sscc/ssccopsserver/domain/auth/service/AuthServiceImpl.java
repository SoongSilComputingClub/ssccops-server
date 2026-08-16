package org.sscc.ssccopsserver.domain.auth.service;

import org.springframework.stereotype.Service;
import org.sscc.ssccopsserver.domain.auth.dto.AuthSessionResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/*
 * 회원 정보는 회원 도메인 Service를 경유해 가져온다 (AR-07).
 * 인증 주체에 실린 MemberEntity는 준영속이라 식별자만 쓰고, 나머지는 회원 도메인이 조회한다.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final MemberService memberService;

    @Override
    public AuthSessionResponse getSession(AuthenticatedUser user) {
        MemberProfileResponse member =
                user.isSignedUp() ? memberService.getProfile(user.member().getId()) : null;

        return AuthSessionResponse.of(user, member);
    }
}
