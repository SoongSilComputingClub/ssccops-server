package com.example.ssccwebbe.domain.user.entity;

import lombok.Getter;

@Getter
public enum SocialProviderType {
    GOOGLE("구글");
    private final String description;

    SocialProviderType(String description) {
        this.description = description;
    }
}
