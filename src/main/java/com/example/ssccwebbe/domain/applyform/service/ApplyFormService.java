package com.example.ssccwebbe.domain.applyform.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssccwebbe.domain.applyform.code.ApplyFormErrorCode;
import com.example.ssccwebbe.domain.applyform.dto.ApplyFormCreateOrUpdateRequest;
import com.example.ssccwebbe.domain.applyform.dto.ApplyFormReadResponse;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormEntity;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormInterviewTimeEntity;
import com.example.ssccwebbe.domain.applyform.repository.ApplyFormInterviewTimeRepository;
import com.example.ssccwebbe.domain.applyform.repository.ApplyFormRepository;
import com.example.ssccwebbe.domain.user.entity.UserEntity;
import com.example.ssccwebbe.domain.user.repository.UserRepository;
import com.example.ssccwebbe.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ApplyFormService {

    private final ApplyFormRepository applyFormRepository;
    private final ApplyFormInterviewTimeRepository interviewTimeRepository;
    private final UserRepository userRepository;

    // 지원서 조회용
    @Transactional(readOnly = true)
    public ApplyFormReadResponse read() {
        UserEntity user = currentUser();
        ApplyFormEntity form =
                applyFormRepository
                        .findByUser(user)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                ApplyFormErrorCode.APPLY_FORM_NOT_FOUND));

        requireActive(form);
        return toResponse(user, form);
    }

    // 지원서 처음 생성 시
    @Transactional
    public ApplyFormReadResponse create(ApplyFormCreateOrUpdateRequest req) {
        validate(req);

        UserEntity user = currentUser();

        return applyFormRepository
                .findByUser(user)
                .map(
                        existing -> {
                            // 이미 활성 지원서가 있으면 생성 불가
                            if (!existing.isDeleted()) {
                                throw new GeneralException(
                                        ApplyFormErrorCode.APPLY_FORM_ALREADY_EXISTS);
                            }

                            // soft delete 상태면 복구 + 내용 업데이트 + 면접시간 덮어쓰기 (재작성)
                            existing.restoreSubmitted();
                            existing.update(req);
                            overwriteInterviewTimes(existing, req);

                            return toResponse(user, existing);
                        })
                .orElseGet(
                        () -> {
                            // 지원서 자체가 없으면 신규 생성
                            ApplyFormEntity saved =
                                    applyFormRepository.save(ApplyFormEntity.create(user, req));
                            overwriteInterviewTimes(saved, req);
                            return toResponse(user, saved);
                        });
    }

    // 지원서 수정
    @Transactional
    public ApplyFormReadResponse update(ApplyFormCreateOrUpdateRequest req) {
        validate(req);

        UserEntity user = currentUser();
        ApplyFormEntity form =
                applyFormRepository
                        .findByUser(user)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                ApplyFormErrorCode.APPLY_FORM_NOT_FOUND));

        requireActive(form);

        form.update(req);
        overwriteInterviewTimes(form, req);

        return toResponse(user, form);
    }

    // 지원서 삭제
    @Transactional
    public void deleteSoft() {
        UserEntity user = currentUser();
        ApplyFormEntity form =
                applyFormRepository
                        .findByUser(user)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                ApplyFormErrorCode.APPLY_FORM_NOT_FOUND));

        // 1. 면접 희망 시간 먼저 제거
        interviewTimeRepository.deleteAllByApplyForm(form);

        // 2. 지원서 상태만 DELETED로 변경
        form.softDelete();
    }

    // ------------------ private ------------------

    // 현재 로그인한 사용자를 user_entity에서 조회
    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        return userRepository
                .findByUsernameAndIsLock(username, false)
                .orElseThrow(() -> new GeneralException(ApplyFormErrorCode.USER_NOT_FOUND));
    }

    private void validate(ApplyFormCreateOrUpdateRequest req) {
        if (req.interviewTimes().isEmpty()) {
            throw new GeneralException(ApplyFormErrorCode.INVALID_INTERVIEW_TIMES);
        }
        // 중복 시간 방지까지 하고 싶으면 여기서 Set 체크 추가 가능
    }

    // 면접 희망 시간 덮어쓰기 (기존 삭제 후 새로 저장)
    private void overwriteInterviewTimes(ApplyFormEntity form, ApplyFormCreateOrUpdateRequest req) {
        interviewTimeRepository.deleteAllByApplyForm(form);

        List<ApplyFormInterviewTimeEntity> entities =
                req.interviewTimes().stream()
                        .sorted(
                                Comparator.comparing(
                                                ApplyFormCreateOrUpdateRequest.InterviewTime::date)
                                        .thenComparing(
                                                ApplyFormCreateOrUpdateRequest.InterviewTime
                                                        ::startTime))
                        .map(t -> ApplyFormInterviewTimeEntity.from(form, t))
                        .toList();

        interviewTimeRepository.saveAll(entities);
    }

    private ApplyFormReadResponse toResponse(UserEntity user, ApplyFormEntity form) {
        List<ApplyFormReadResponse.InterviewTime> times =
                interviewTimeRepository
                        .findAllByApplyFormOrderByInterviewDateAscStartTimeAsc(form)
                        .stream()
                        .map(
                                t ->
                                        new ApplyFormReadResponse.InterviewTime(
                                                t.getInterviewDate(),
                                                t.getStartTime(),
                                                t.getEndTime()))
                        .toList();

        return new ApplyFormReadResponse(
                form.getId(),
                user.getUsername(),
                form.getApplicantName(),
                form.getDepartment(),
                form.getStudentNo(),
                form.getGrade(),
                form.getPhone(),
                form.getGender(),
                form.getIntroduce(),
                form.getCodingExp(),
                form.getTechStackText(),
                form.getWantedValue(),
                form.getAspiration(),
                form.getStatus(),
                times);
    }

    // soft 삭제된 지원서는 read/update에서 접근 불가
    private void requireActive(ApplyFormEntity form) {
        if (form.isDeleted()) {
            throw new GeneralException(ApplyFormErrorCode.APPLY_FORM_NOT_FOUND);
        }
    }
}
