package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회원 관리 목록(GET /v1/members)의 한 줄 (#76).
 *
 * **MemberProfileResponse를 재사용하지 않는다.** 그쪽은 본인용이고 이쪽은 타인 목록이라
 * 담기는 것이 다르다 (LY-03). capabilities는 어느 쪽에도 넣지 않는다 — 본인 세션에서만
 * 뜻이 있는 값이고, 남의 권한을 목록에 실으면 화면이 그것으로 버튼을 고르기 시작한다.
 *
 * 필드는 데이터사전(SSoT)의 mbr 컬럼을 그대로 따른다. 연락처·학번까지 담는 것은 이
 * 엔드포인트가 MEMBER_MANAGE를 요구하기 때문이며, 권한 없이 부르는 담당자 후보 목록
 * (AssignableMemberResponse)은 같은 회원을 다루면서도 그 셋을 빼고 내린다.
 *
 * linkedAccount는 auth_user_id가 채워져 있는지다 — 즉 이 회원이 실제 계정과 연결돼
 * 로그인할 수 있는 상태인가. CSV로 이관만 되고 아직 한 번도 로그인하지 않은 회원을
 * 명부에서 가려내야 하므로 목록·단건 양쪽에 싣는다(#85). UUID 자체는 내리지 않는다 —
 * 화면이 쓸 일이 없고 인증 벤더의 식별자라 밖으로 나갈 이유가 없다.
 *
 * roles는 현재 역할이며 판정 규칙은 BR-M25다(MemberSearchQuery·Service 주석 참고).
 * 표시용이므로 대표 역할 여부(rprs_role_yn)로 정렬하거나 걸러내지 않는다 (BR-M26).
 */
public record MemberSummaryResponse(
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
        Instant createdAt,
        Instant updatedAt) {

    /*
     * 등급·상태는 지연 로딩이라 이 변환은 조회 트랜잭션 안에서 호출해야 한다.
     * 목록 쿼리가 @EntityGraph·join fetch로 함께 끌어오므로 여기서 추가 쿼리는 나가지 않는다.
     */
    public static MemberSummaryResponse of(MemberEntity member, List<MemberRoleResponse> roles) {
        return new MemberSummaryResponse(
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
                member.getCreatedAt(),
                member.getUpdatedAt());
    }
}
