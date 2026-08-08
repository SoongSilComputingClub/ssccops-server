package com.example.ssccopsserver.domain.applyform.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ssccopsserver.domain.applyform.entity.ApplyFormEntity;
import com.example.ssccopsserver.domain.applyform.entity.ApplyFormInterviewTimeEntity;

// 면접 희망 시간용으로, 수정 시 덮어쓰기 식으로 진행

public interface ApplyFormInterviewTimeRepository
        extends JpaRepository<ApplyFormInterviewTimeEntity, Long> {

    List<ApplyFormInterviewTimeEntity> findAllByApplyFormOrderByInterviewDateAscStartTimeAsc(
            ApplyFormEntity applyForm);

    void deleteAllByApplyForm(ApplyFormEntity applyForm);
}
