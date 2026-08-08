package com.example.ssccwebbe.domain.user.service;

import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.example.ssccwebbe.domain.user.dto.UserResponseDto;

public interface UserService extends OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    UserResponseDto readUser();
}
