package com.example.ssccwebbe.domain.applyform.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.example.ssccwebbe.domain.applyform.entity.CodingExp;
import com.example.ssccwebbe.domain.applyform.entity.Gender;

// 지원서 생성/수정 요청 DTO.
// 면접 희망 시간은 N개 선택 가능하므로 List로 받음

public record ApplyFormCreateOrUpdateRequest(
        @NotBlank String applicantName,
        @NotBlank String department,
        @NotBlank String studentNo,
        @NotNull Integer grade,
        @NotBlank String phone,
        @NotNull Gender gender,
        @NotBlank String introduce,
        @NotNull CodingExp codingExp,
        String techStackText,
        @NotBlank String wantedValue,
        @NotBlank String aspiration,
        @NotNull List<InterviewTime> interviewTimes) {

    // date + startTime + endTime 형태로 그대로 저장

    public record InterviewTime(
            @NotNull LocalDate date, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}
}
