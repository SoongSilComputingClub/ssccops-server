package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 *
 * createdAt·updatedAt은 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다. 두 칸 모두 Instant를
 * 그대로 내리고 있었다(#318) — 문자열을 잘라 그리는 화면에서는 아홉 시간 이른 시각이 된다.
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
        LocalDate systemJoinDate,
        Integer clubJoinYear,
        Integer clubJoinMonth,
        boolean linkedAccount,
        List<MemberRoleResponse> roles,
        List<MemberChangeHistoryResponse> recentChanges,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

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
                member.getSystemJoinDate(),
                member.getClubJoinYear(),
                member.getClubJoinMonth(),
                member.getAuthUserId() != null,
                roles,
                recentChanges,
                toOffsetDateTime(member.getCreatedAt()),
                toOffsetDateTime(member.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
