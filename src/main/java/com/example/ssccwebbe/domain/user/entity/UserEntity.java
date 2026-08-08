package com.example.ssccwebbe.domain.user.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.example.ssccwebbe.domain.user.dto.UserRequestDto;
import com.example.ssccwebbe.global.security.UserRoleType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class) // 생성일, 수정일 자동 변경
@Table(name = "user_entity")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", unique = true, nullable = false, updatable = false)
    private String username;

    // 계정 잠김 여부
    @Column(name = "is_lock", nullable = false)
    private Boolean isLock;

    // 소셜 로그인 계정 여부
    @Column(name = "is_social", nullable = false)
    private Boolean isSocial;

    // 합격 정보
    @Column(name = "is_accepted", nullable = true)
    private Boolean isAccepted;

    // 자체 로그인일 경우 null 값이 들어갈 예정
    // 소셜 로그인일 경우 어떤 소셜인지 구분자가 들어갈 예 (naver, google 등등)
    @Enumerated(EnumType.STRING) // Enum이 텍스트로 들어가도록 설정
    @Column(name = "social_provider_type")
    private SocialProviderType socialProviderType;

    // 유저의 자격/역할 (preuser/user/admin 등등)
    @Enumerated(EnumType.STRING) // Enum이 텍스트로 들어가도록 설정
    @Column(name = "role_type", nullable = false)
    private UserRoleType roleType;

    // 닉네임
    @Column(name = "nickname")
    private String nickname;

    // 이메일
    @Column(name = "email")
    private String email;

    // 튜플의 생성 시각
    @CreatedDate
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdDate;

    // 튜플의 수정 시각
    @LastModifiedDate
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    public void updateUser(UserRequestDto dto) {
        this.email = dto.getEmail(); // 이메일 수정 허용
        this.nickname = dto.getNickname(); // 닉네임 수정 허용
    }
}
