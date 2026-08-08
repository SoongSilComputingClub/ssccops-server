package com.example.ssccwebbe.global.security.jwt.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.ssccwebbe.global.apipayload.ApiResponse;
import com.example.ssccwebbe.global.security.jwt.dto.JwtResponseDto;
import com.example.ssccwebbe.global.security.jwt.dto.RefreshRequestDto;
import com.example.ssccwebbe.global.security.jwt.service.JwtService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "JWT 토큰 관리", description = "JWT Access Token과 Refresh Token의 발급 및 재발급을 관리하는 API")
@RestController
public class JwtController {

    private final JwtService jwtService;

    public JwtController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Operation(
            summary = "쿠키의 Refresh Token을 헤더로 변환",
            description =
                    """
                    소셜 로그인 후 쿠키로 받은 Refresh Token을 검증하고, 새로운 Access Token과 Refresh Token을 응답 본문으로 반환합니다.
                    기존 Refresh Token은 삭제되고 새 토큰으로 교체됩니다. (Rotation)

                    **요청:**
                    - 쿠키: refreshToken (HttpOnly)
                    - 요청 본문: 빈 JSON ({})
                    """)
    @PostMapping(value = "/jwt/exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JwtResponseDto> jwtExchangeApi(
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(jwtService.cookie2Header(request, response));
    }

    @Operation(
            summary = "Refresh Token으로 토큰 재발급",
            description =
                    """
                    유효한 Refresh Token을 받아 새로운 Access Token과 Refresh Token을 재발급합니다.
                    기존 Refresh Token은 삭제되고 새 토큰으로 교체됩니다. (Rotation)

                    **요청 본문:**
                    ```json
                    {
                      "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    }
                    ```
                    """)
    @PostMapping(value = "/jwt/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JwtResponseDto> jwtRefreshApi(
            @Validated @RequestBody RefreshRequestDto dto) {
        return ApiResponse.success(jwtService.refreshRotate(dto));
    }
}
