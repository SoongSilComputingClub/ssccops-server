package com.example.ssccwebbe.domain.applyform.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CodingExp {
    A("완전 처음이다."),
    B("학교 교과 과정만 따라갔다."),
    C("학교 교과목, 동아리에서 배운 내용을 활용해봤다."),
    D("토이 프로젝트를 진행해봤다."),
    E("개발 관련 공모전 및 대회에 참여해봤다.");

    private final String description;
}
