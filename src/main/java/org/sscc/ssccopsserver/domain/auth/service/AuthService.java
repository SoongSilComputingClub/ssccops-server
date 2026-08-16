package org.sscc.ssccopsserver.domain.auth.service;

import org.sscc.ssccopsserver.domain.auth.dto.AuthSessionResponse;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

public interface AuthService {

    // 현재 토큰이 가리키는 로그인 세션. 아직 가입하지 않은 사용자도 정상 응답 대상이다.
    AuthSessionResponse getSession(AuthenticatedUser user);
}
