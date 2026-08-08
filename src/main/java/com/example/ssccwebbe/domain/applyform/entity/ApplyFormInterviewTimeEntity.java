package com.example.ssccwebbe.domain.applyform.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.example.ssccwebbe.domain.applyform.dto.ApplyFormCreateOrUpdateRequest;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 지원서 내 면접 희망시간을 위한 엔티티

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "apply_form_interview_time_entity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApplyFormInterviewTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 지원서 1개당 N개
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "apply_form_id", nullable = false)
    private ApplyFormEntity applyForm;

    @Column(name = "interview_date", nullable = false)
    private LocalDate interviewDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @CreatedDate
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdDate;

    public static ApplyFormInterviewTimeEntity from(
            ApplyFormEntity form, ApplyFormCreateOrUpdateRequest.InterviewTime interviewTime) {

        return new ApplyFormInterviewTimeEntity(
                null,
                form,
                interviewTime.date(),
                interviewTime.startTime(),
                interviewTime.endTime(),
                null);
    }
}
