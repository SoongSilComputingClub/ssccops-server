package com.example.ssccwebbe.domain.applyform.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.example.ssccwebbe.domain.applyform.entity.ApplyFormStatus;
import com.example.ssccwebbe.domain.applyform.entity.CodingExp;
import com.example.ssccwebbe.domain.applyform.entity.Gender;

// 지원서 조회 응답을 위한 DTO
public record ApplyFormReadResponse(
        Long applyFormId,
        String username,
        String applicantName,
        String department,
        String studentNo,
        Integer grade,
        String phone,
        Gender gender,
        String introduce,
        CodingExp codingExp,
        String techStackText,
        String wantedValue,
        String aspiration,
        ApplyFormStatus status,
        List<InterviewTime> interviewTimes) {
    public record InterviewTime(LocalDate date, LocalTime startTime, LocalTime endTime) {}
}
