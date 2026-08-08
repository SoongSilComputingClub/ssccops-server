package com.example.ssccwebbe.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ssccwebbe.domain.admin.dto.CodingExpDistributionResponse;
import com.example.ssccwebbe.domain.admin.dto.GenderDistributionResponse;
import com.example.ssccwebbe.domain.applyform.dto.ApplyFormReadResponse;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormEntity;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormStatus;
import com.example.ssccwebbe.domain.applyform.entity.CodingExp;
import com.example.ssccwebbe.domain.applyform.entity.Gender;
import com.example.ssccwebbe.domain.applyform.repository.ApplyFormInterviewTimeRepository;
import com.example.ssccwebbe.domain.applyform.repository.ApplyFormRepository;
import com.example.ssccwebbe.domain.user.entity.UserEntity;

@ExtendWith(MockitoExtension.class)
class ApplyFormAdminServiceTest {

    @Mock private ApplyFormRepository applyFormRepository;
    @Mock private ApplyFormInterviewTimeRepository interviewTimeRepository;

    @InjectMocks private ApplyFormAdminService applyFormAdminService;

    @Test
    @DisplayName("getGenderDistribution - 성별 분포 조회 성공")
    void getGenderDistribution_Success() {
        // given
        when(applyFormRepository.countByGenderAndStatusNot(Gender.MALE, ApplyFormStatus.DELETED))
                .thenReturn(3L);
        when(applyFormRepository.countByGenderAndStatusNot(Gender.FEMALE, ApplyFormStatus.DELETED))
                .thenReturn(1L);
        when(applyFormRepository.countByStatusNot(ApplyFormStatus.DELETED)).thenReturn(4L);

        // when
        GenderDistributionResponse response = applyFormAdminService.getGenderDistribution();

        // then
        assertThat(response.maleCount()).isEqualTo(3L);
        assertThat(response.femaleCount()).isEqualTo(1L);
        assertThat(response.malePercentage()).isEqualTo(75.0);
        assertThat(response.femalePercentage()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("getGenderDistribution - 지원자가 없을 때 0% 반환")
    void getGenderDistribution_NoApplicants() {
        // given
        when(applyFormRepository.countByStatusNot(ApplyFormStatus.DELETED)).thenReturn(0L);

        // when
        GenderDistributionResponse response = applyFormAdminService.getGenderDistribution();

        // then
        assertThat(response.malePercentage()).isZero();
        assertThat(response.femalePercentage()).isZero();
    }

    @Test
    @DisplayName("getCodingExpDistribution - 코딩 경험 분포 조회 성공")
    void getCodingExpDistribution_Success() {
        // given
        when(applyFormRepository.countByStatusNot(ApplyFormStatus.DELETED)).thenReturn(10L);
        // 모든 레벨에 2명씩 있다고 가상 설정
        for (CodingExp exp : CodingExp.values()) {
            when(applyFormRepository.countByCodingExpAndStatusNot(exp, ApplyFormStatus.DELETED))
                    .thenReturn(2L);
        }

        // when
        CodingExpDistributionResponse response = applyFormAdminService.getCodingExpDistribution();

        // then
        assertThat(response.totalCount()).isEqualTo(10L);
        assertThat(response.distributions()).hasSize(5); // A, B, C, D, E 5단계
        for (var dist : response.distributions()) {
            assertThat(dist.count()).isEqualTo(2L);
            assertThat(dist.percentage()).isEqualTo(20.0);
        }
    }

    @Test
    @DisplayName("getAllApplications - 전체 지원서 리스트 조회 성공")
    void getAllApplications_Success() {
        // given
        UserEntity user = UserEntity.builder().username("user1").build();
        ApplyFormEntity form = mock(ApplyFormEntity.class);
        when(form.getId()).thenReturn(1L);
        when(form.getUser()).thenReturn(user);
        when(form.getApplicantName()).thenReturn("홍길동");
        when(form.getGender()).thenReturn(Gender.MALE);
        when(form.getStatus()).thenReturn(ApplyFormStatus.SUBMITTED);
        when(form.getCodingExp()).thenReturn(CodingExp.C);

        when(applyFormRepository.findAllByStatusNot(ApplyFormStatus.DELETED))
                .thenReturn(List.of(form));
        when(interviewTimeRepository.findAllByApplyFormOrderByInterviewDateAscStartTimeAsc(form))
                .thenReturn(List.of());

        // when
        List<ApplyFormReadResponse> result = applyFormAdminService.getAllApplications();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).applicantName()).isEqualTo("홍길동");
        verify(applyFormRepository, times(1)).findAllByStatusNot(ApplyFormStatus.DELETED);
    }

    @Test
    @DisplayName("getAllApplications - 지원서가 없을 때 빈 리스트 반환")
    void getAllApplications_Empty() {
        // given
        when(applyFormRepository.findAllByStatusNot(ApplyFormStatus.DELETED)).thenReturn(List.of());

        // when
        List<ApplyFormReadResponse> result = applyFormAdminService.getAllApplications();

        // then
        assertThat(result).isEmpty();
    }
}
