package org.sscc.ssccopsserver.domain.user.service;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.user.code.UserErrorCode;
import org.sscc.ssccopsserver.domain.user.dto.UserResponseDto;
import org.sscc.ssccopsserver.domain.user.entity.UserEntity;
import org.sscc.ssccopsserver.domain.user.repository.UserRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 구글 OAuth2 로그인 제거에 따라 loadUser(DefaultOAuth2UserService)가 삭제됐다.
 * 신규 유저 생성(프로비저닝)은 Supabase Auth 도입 시 JWT 검증 단계에서 처리한다.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    // 유저 정보 조회
    @Transactional(readOnly = true)
    public UserResponseDto readUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity entity =
                userRepository
                        .findByUsernameAndIsLock(username, false)
                        .orElseThrow(() -> new GeneralException(UserErrorCode.USER_NOT_FOUND));

        return new UserResponseDto(
                username, entity.getIsSocial(), entity.getNickname(), entity.getEmail());
    }
}
