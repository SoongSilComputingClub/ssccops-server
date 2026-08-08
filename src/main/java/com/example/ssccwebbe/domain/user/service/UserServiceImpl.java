package com.example.ssccwebbe.domain.user.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssccwebbe.domain.user.code.UserErrorCode;
import com.example.ssccwebbe.domain.user.dto.CustomOAuth2User;
import com.example.ssccwebbe.domain.user.dto.UserRequestDto;
import com.example.ssccwebbe.domain.user.dto.UserResponseDto;
import com.example.ssccwebbe.domain.user.entity.SocialProviderType;
import com.example.ssccwebbe.domain.user.entity.UserEntity;
import com.example.ssccwebbe.domain.user.repository.UserRepository;
import com.example.ssccwebbe.global.apipayload.exception.GeneralException;
import com.example.ssccwebbe.global.security.UserRoleType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends DefaultOAuth2UserService implements UserService {

    private final UserRepository userRepository;

    // 소셜 로그인 ( 로그인시 : 신규는 가입 처리, 기존 회원은 회원정보 업데이트)
    // Oauth2 관련 빈이 유저 정보를 받을 경우, 유저 정보를 OAuth2UserRequest 객체를 파라미터로 넘기며 loadUser 를 호출함
    @lombok.Generated // JaCoCo/SonarQube 커버리지 체크에서 제외 (OAuth2 통합 테스트 복잡도로 인한 제외)
    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {

        // 부모 메소드 호출(DefaultOAuth2UserService 의 loadUser 호출) => OAuth2User 객체 받아내기
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 데이터
        Map<String, Object> attributes;
        List<GrantedAuthority> authorities;

        String username;
        String role;
        String email;
        String nickname;

        // provider 제공자별 데이터 획득 (구글인지)
        String registrationId =
                userRequest.getClientRegistration().getRegistrationId().toUpperCase();

        // 구글 인 경우
        if (registrationId.equals(SocialProviderType.GOOGLE.name())) {

            attributes = (Map<String, Object>) oAuth2User.getAttributes();
            username = registrationId + "_" + attributes.get("sub");
            email = attributes.get("email").toString();
            nickname = attributes.get("name").toString();

            // 구글이 아닌 경우
        } else {
            OAuth2Error error = new OAuth2Error("unsupported_provider", "지원하지 않는 소셜 로그인입니다.", null);
            throw new OAuth2AuthenticationException(error);
        }

        // 데이터베이스 조회 -> 존재하면 업데이트, 없으면 신규 가입
        Optional<UserEntity> entity = userRepository.findByUsernameAndIsSocial(username, true);

        // 기존 회원인 경우
        if (entity.isPresent()) {
            // role 조회
            role = entity.get().getRoleType().name();

            // 기존 유저 업데이트
            UserRequestDto dto = new UserRequestDto();
            dto.setNickname(nickname);
            dto.setEmail(email);
            entity.get().updateUser(dto);

            userRepository.save(entity.get());

            // 신규 회원인 경우
        } else {
            // 신규 유저 추가
            UserEntity newUserEntity =
                    UserEntity.builder()
                            .username(username)
                            .isLock(false)
                            .isSocial(true)
                            .socialProviderType(SocialProviderType.valueOf(registrationId))
                            .roleType(UserRoleType.PREUSER)
                            .nickname(nickname)
                            .email(email)
                            .build();

            userRepository.save(newUserEntity);
            role = UserRoleType.PREUSER.name(); // 신규 유저의 role 변수 업데이트
        }

        authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

        /*
            SocialSuccessHandler 에서 authentication.getName()을 호출했을 때
            (id가 아닌 ex. 12345) 우리가 원하는 형식 (social_id ex. NAVER_12345) 를 얻기 위해 CustomOAuth2User 를 리턴함
        */
        return new CustomOAuth2User(attributes, authorities, username);
    }

    // 자체/소셜 유저 정보 조회
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
