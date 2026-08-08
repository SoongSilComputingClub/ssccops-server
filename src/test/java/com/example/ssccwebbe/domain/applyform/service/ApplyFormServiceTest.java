package com.example.ssccwebbe.domain.applyform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.ssccwebbe.domain.applyform.code.ApplyFormErrorCode;
import com.example.ssccwebbe.domain.applyform.dto.ApplyFormCreateOrUpdateRequest;
import com.example.ssccwebbe.domain.applyform.dto.ApplyFormReadResponse;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormEntity;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormInterviewTimeEntity;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormStatus;
import com.example.ssccwebbe.domain.applyform.entity.CodingExp;
import com.example.ssccwebbe.domain.applyform.entity.Gender;
import com.example.ssccwebbe.domain.applyform.repository.ApplyFormInterviewTimeRepository;
import com.example.ssccwebbe.domain.applyform.repository.ApplyFormRepository;
import com.example.ssccwebbe.domain.user.entity.UserEntity;
import com.example.ssccwebbe.domain.user.repository.UserRepository;
import com.example.ssccwebbe.global.apipayload.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class ApplyFormServiceTest {

    @Mock private ApplyFormRepository applyFormRepository;
    @Mock private ApplyFormInterviewTimeRepository interviewTimeRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityContext securityContext;
    @Mock private Authentication authentication;

    @InjectMocks private ApplyFormService applyFormService;

    private String username = "testuser";
    private UserEntity user;
    private ApplyFormCreateOrUpdateRequest request;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().username(username).isLock(false).build();

        request =
                new ApplyFormCreateOrUpdateRequest(
                        "홍길동",
                        "컴퓨터학부",
                        "20211234",
                        3,
                        "010-1234-5678",
                        Gender.MALE,
                        "자기소개",
                        CodingExp.C,
                        "Java, Spring",
                        "SSCC에서 다양한 사람들을 만나고 싶습니다.",
                        "열심히 하겠습니다!",
                        List.of(
                                new ApplyFormCreateOrUpdateRequest.InterviewTime(
                                        LocalDate.of(2026, 3, 10),
                                        LocalTime.of(10, 0),
                                        LocalTime.of(11, 0))));

        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        lenient().when(authentication.getName()).thenReturn(username);
    }

    @Test
    @DisplayName("read - 지원서 정상 조회")
    void read_Success() {
        // given
        ApplyFormEntity form = ApplyFormEntity.create(user, request);
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.of(user));
        when(applyFormRepository.findByUser(user)).thenReturn(Optional.of(form));
        when(interviewTimeRepository.findAllByApplyFormOrderByInterviewDateAscStartTimeAsc(form))
                .thenReturn(
                        List.of(
                                ApplyFormInterviewTimeEntity.from(
                                        form, request.interviewTimes().get(0))));

        // when
        ApplyFormReadResponse response = applyFormService.read();

        // then
        assertThat(response.applicantName()).isEqualTo(request.applicantName());
        assertThat(response.status()).isEqualTo(ApplyFormStatus.SUBMITTED);
        assertThat(response.interviewTimes()).hasSize(1);
    }

    @Test
    @DisplayName("read - 지원서 없음 예외 발생")
    void read_NotFound() {
        // given
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.of(user));
        when(applyFormRepository.findByUser(user)).thenReturn(Optional.empty());

        // when & then
        GeneralException exception =
                assertThrows(GeneralException.class, () -> applyFormService.read());
        assertThat(exception.getErrorCode()).isEqualTo(ApplyFormErrorCode.APPLY_FORM_NOT_FOUND);
    }

    @Test
    @DisplayName("create - 신규 지원서 작성 성공")
    void create_Success() {
        // given
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.of(user));
        when(applyFormRepository.findByUser(user)).thenReturn(Optional.empty());

        ApplyFormEntity savedForm = ApplyFormEntity.create(user, request);
        when(applyFormRepository.save(any(ApplyFormEntity.class))).thenReturn(savedForm);

        // when
        ApplyFormReadResponse response = applyFormService.create(request);

        // then
        assertThat(response.applicantName()).isEqualTo(request.applicantName());
        verify(applyFormRepository, times(1)).save(any(ApplyFormEntity.class));
        verify(interviewTimeRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("create - 이미 제출된 지원서가 있는 경우 예외 발생")
    void create_AlreadyExists() {
        // given
        ApplyFormEntity existingForm = ApplyFormEntity.create(user, request);
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.of(user));
        when(applyFormRepository.findByUser(user)).thenReturn(Optional.of(existingForm));

        // when & then
        GeneralException exception =
                assertThrows(GeneralException.class, () -> applyFormService.create(request));
        assertThat(exception.getErrorCode())
                .isEqualTo(ApplyFormErrorCode.APPLY_FORM_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("create - 삭제된 지원서 복구하며 작성 성공")
    void create_RestoreDeleted() {
        // given
        ApplyFormEntity deletedForm = ApplyFormEntity.create(user, request);
        deletedForm.softDelete();

        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.of(user));
        when(applyFormRepository.findByUser(user)).thenReturn(Optional.of(deletedForm));

        // when
        ApplyFormReadResponse response = applyFormService.create(request);

        // then
        assertThat(response.applicantName()).isEqualTo(request.applicantName());
        assertThat(deletedForm.isDeleted()).isFalse();
        verify(interviewTimeRepository, times(1)).deleteAllByApplyForm(deletedForm);
    }

    @Test
    @DisplayName("update - 지원서 수정 성공")
    void update_Success() {
        // given
        ApplyFormEntity form = ApplyFormEntity.create(user, request);
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.of(user));
        when(applyFormRepository.findByUser(user)).thenReturn(Optional.of(form));

        ApplyFormCreateOrUpdateRequest updateReq =
                new ApplyFormCreateOrUpdateRequest(
                        "수정된이름",
                        "SW",
                        "20211234",
                        3,
                        "010-0000-0000",
                        Gender.FEMALE,
                        "수정소개",
                        CodingExp.D,
                        "stack",
                        "updatedValue",
                        "updatedAspiration",
                        request.interviewTimes());

        // when
        ApplyFormReadResponse response = applyFormService.update(updateReq);

        // then
        assertThat(response.applicantName()).isEqualTo("수정된이름");
        assertThat(form.getApplicantName()).isEqualTo("수정된이름");
    }

    @Test
    @DisplayName("deleteSoft - 삭제 성공")
    void deleteSoft_Success() {
        // given
        ApplyFormEntity form = ApplyFormEntity.create(user, request);
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.of(user));
        when(applyFormRepository.findByUser(user)).thenReturn(Optional.of(form));

        // when
        applyFormService.deleteSoft();

        // then
        assertThat(form.isDeleted()).isTrue();
        verify(interviewTimeRepository, times(1)).deleteAllByApplyForm(form);
    }

    @Test
    @DisplayName("validate - 면접 시간 미선택 시 예외 발생")
    void validate_EmptyInterviewTimes_ThrowsException() {
        // given
        ApplyFormCreateOrUpdateRequest emptyTimesReq =
                new ApplyFormCreateOrUpdateRequest(
                        "이름",
                        "학과",
                        "학번",
                        1,
                        "010",
                        Gender.MALE,
                        "I",
                        CodingExp.A,
                        "S",
                        "V",
                        "A",
                        List.of());
        // when & then
        GeneralException exception =
                assertThrows(GeneralException.class, () -> applyFormService.create(emptyTimesReq));
        assertThat(exception.getErrorCode()).isEqualTo(ApplyFormErrorCode.INVALID_INTERVIEW_TIMES);
    }

    @Test
    @DisplayName("currentUser - 유저를 찾을 수 없는 경우 예외 발생")
    void currentUser_UserNotFound() {
        // given
        when(userRepository.findByUsernameAndIsLock(username, false)).thenReturn(Optional.empty());

        // when & then
        GeneralException exception =
                assertThrows(GeneralException.class, () -> applyFormService.read());
        assertThat(exception.getErrorCode()).isEqualTo(ApplyFormErrorCode.USER_NOT_FOUND);
    }
}
