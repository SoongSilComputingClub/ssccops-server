package com.example.ssccwebbe.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.ssccwebbe.domain.user.code.UserErrorCode;
import com.example.ssccwebbe.domain.user.dto.UserResponseDto;
import com.example.ssccwebbe.domain.user.entity.SocialProviderType;
import com.example.ssccwebbe.domain.user.entity.UserEntity;
import com.example.ssccwebbe.domain.user.repository.UserRepository;
import com.example.ssccwebbe.global.apipayload.exception.GeneralException;
import com.example.ssccwebbe.global.security.UserRoleType;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepository;

    @Mock private SecurityContext securityContext;

    @Mock private Authentication authentication;

    @InjectMocks private UserServiceImpl userService;

    @Test
    @DisplayName("readUser - 일반 사용자 정보를 정상적으로 조회한다")
    void readUser_RegularUser_Success() {
        // given
        String username = "testuser@test.com";
        String email = "testuser@test.com";
        String nickname = "Test User";

        UserEntity userEntity =
                UserEntity.builder()
                        .username(username)
                        .email(email)
                        .nickname(nickname)
                        .isSocial(false)
                        .isLock(false)
                        .roleType(UserRoleType.USER)
                        .build();

        // SecurityContext 모킹
        when(authentication.getName()).thenReturn(username);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        when(userRepository.findByUsernameAndIsLock(username, false))
                .thenReturn(Optional.of(userEntity));

        // when
        UserResponseDto result = userService.readUser();

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo(username);
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(result.getNickname()).isEqualTo(nickname);
        assertThat(result.getSocial()).isFalse();

        verify(userRepository, times(1)).findByUsernameAndIsLock(username, false);
    }

    @Test
    @DisplayName("readUser - 소셜 로그인 사용자 정보를 정상적으로 조회한다")
    void readUser_SocialUser_Success() {
        // given
        String username = "GOOGLE_123456789";
        String email = "socialuser@gmail.com";
        String nickname = "Social User";

        UserEntity userEntity =
                UserEntity.builder()
                        .username(username)
                        .email(email)
                        .nickname(nickname)
                        .isSocial(true)
                        .isLock(false)
                        .socialProviderType(SocialProviderType.GOOGLE)
                        .roleType(UserRoleType.USER)
                        .build();

        // SecurityContext 모킹
        when(authentication.getName()).thenReturn(username);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        when(userRepository.findByUsernameAndIsLock(username, false))
                .thenReturn(Optional.of(userEntity));

        // when
        UserResponseDto result = userService.readUser();

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo(username);
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(result.getNickname()).isEqualTo(nickname);
        assertThat(result.getSocial()).isTrue();

        verify(userRepository, times(1)).findByUsernameAndIsLock(username, false);
    }

    @Test
    @DisplayName("readUser - 존재하지 않는 사용자 조회 시 GeneralException 발생")
    void readUser_UserNotFound_ThrowsException() {
        // given
        String username = "nonexistent@test.com";

        // SecurityContext 모킹
        when(authentication.getName()).thenReturn(username);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.empty());

        // when & then
        GeneralException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        GeneralException.class, () -> userService.readUser());

        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
        verify(userRepository, times(1)).findByUsernameAndIsLock(username, false);
    }

    @Test
    @DisplayName("readUser - 계정이 잠긴 사용자는 조회되지 않는다")
    void readUser_LockedUser_NotFound() {
        // given
        String username = "lockeduser@test.com";

        // SecurityContext 모킹
        when(authentication.getName()).thenReturn(username);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        // isLock=true인 사용자는 조회되지 않음
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.empty());

        // when & then
        GeneralException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        GeneralException.class, () -> userService.readUser());

        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
        verify(userRepository, times(1)).findByUsernameAndIsLock(username, false);
    }

    @Test
    @DisplayName("readUser - PREUSER 권한 사용자도 정상적으로 조회된다")
    void readUser_UserRole_Success() {
        // given
        String username = "preuser@test.com";
        String email = "preuser@test.com";
        String nickname = "Pre User";

        UserEntity userEntity =
                UserEntity.builder()
                        .username(username)
                        .email(email)
                        .nickname(nickname)
                        .isSocial(false)
                        .isLock(false)
                        .roleType(UserRoleType.PREUSER)
                        .build();

        // SecurityContext 모킹
        when(authentication.getName()).thenReturn(username);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        when(userRepository.findByUsernameAndIsLock(username, false))
                .thenReturn(Optional.of(userEntity));

        // when
        UserResponseDto result = userService.readUser();

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo(username);
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(result.getNickname()).isEqualTo(nickname);
        assertThat(result.getSocial()).isFalse();
    }

    @Test
    @DisplayName("readUser - ADMIN 권한 사용자도 정상적으로 조회된다")
    void readUser_AdminRole_Success() {
        // given
        String username = "admin@test.com";
        String email = "admin@test.com";
        String nickname = "Admin User";

        UserEntity userEntity =
                UserEntity.builder()
                        .username(username)
                        .email(email)
                        .nickname(nickname)
                        .isSocial(false)
                        .isLock(false)
                        .roleType(UserRoleType.ADMIN)
                        .build();

        // SecurityContext 모킹
        when(authentication.getName()).thenReturn(username);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        when(userRepository.findByUsernameAndIsLock(username, false))
                .thenReturn(Optional.of(userEntity));

        // when
        UserResponseDto result = userService.readUser();

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo(username);
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(result.getNickname()).isEqualTo(nickname);
        assertThat(result.getSocial()).isFalse();
    }
}
