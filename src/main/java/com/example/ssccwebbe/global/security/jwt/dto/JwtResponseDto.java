package com.example.ssccwebbe.global.security.jwt.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class JwtResponseDto {
    private final String accessToken;
    private final String refreshToken;
}
