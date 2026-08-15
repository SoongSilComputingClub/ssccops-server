package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회원 상세(GET /v1/members/{mbrId})의 응답 (#76).
 *
 * 목록(MemberSummaryResponse)과 같은 프로필 필드에 최근 변경 이력을 더한 모양이다.
 * 목록 DTO를 중첩해 담지 않고 **평평하게 펼치는** 것은, 상세 화면이 필드를 그대로 읽어
 * 카드에 뿌리는데 한 단계를 더 두면 목록과 상세에서 같은 값의 경로가 달라지기 때문이다.
 * 그 대신 두 record의 필드가 어긋나지 않도록 이 파일과 MemberSummaryResponse를 함께 고친다.
 *
 * recentChanges는 **최근 3건**뿐이다. 전체 변경 이력은 별도 이슈(회원 변경 이력 통합 조회)이며,
 * 상세 진입 한 번에 이력 전량을 실으면 오래된 회원일수록 응답이 무한정 커진다.
 *
 * capabilities는 담지 않는다 (MemberSummaryResponse 주석과 같은 이유).
 */
public record MemberDetailResponse(
        Long memberId,
        String studentNumber,
        Integer generationNumber,
        String name,
        String departmentName,
        Integer academicYear,
        String phoneNumber,
        String email,
        String membershipGradeCode,
        String membershipGradeName,
        String membershipStatusCode,
        String membershipStatusName,
        LocalDate joinDate,
        boolean linkedAccount,
        List<MemberRoleResponse> roles,
        List<MemberChangeHistoryResponse> recentChanges,
        Instant createdAt,
        Instant updatedAt) {

    public static MemberDetailResponse of(
            MemberEntity member,
            List<MemberRoleResponse> roles,
            List<MemberChangeHistoryResponse> recentChanges) {
        return new MemberDetailResponse(
                member.getId(),
                member.getStudentNumber(),
                member.getGenerationNumber(),
                member.getName(),
                member.getDepartmentName(),
                member.getAcademicYear(),
                member.getPhoneNumber(),
                member.getEmail(),
                member.getMembershipGrade().getCode(),
                member.getMembershipGrade().getName(),
                member.getMembershipStatus().getCode(),
                member.getMembershipStatus().getName(),
                member.getJoinDate(),
                member.getAuthUserId() != null,
                roles,
                recentChanges,
                member.getCreatedAt(),
                member.getUpdatedAt());
    }
}
