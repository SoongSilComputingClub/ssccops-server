package org.sscc.ssccopsserver.domain.example.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ExampleStatus {
    ACTIVE("활성"),
    DELETED("삭제됨");

    private final String description;
}
