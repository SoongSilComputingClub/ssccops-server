package com.example.ssccwebbe.domain.applyform.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ApplyFormStatus {
    SUBMITTED("제출됨"),
    DELETED("삭제됨");

    private final String description;
}
