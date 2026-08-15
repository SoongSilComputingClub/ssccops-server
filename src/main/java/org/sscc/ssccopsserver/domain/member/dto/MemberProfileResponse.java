package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 로그인한 본인의 회원 정보. 세션 조회 응답과 회원가입 응답이 같은 모양을 쓴다 —
 * 가입 직후 프론트가 세션을 다시 조회하지 않아도 되게 하기 위함이다.
 *
 * 등급·상태는 코드와 명칭을 함께 내린다. 프론트는 코드로 분기하고 명칭으로 표시하는데,
 * 명칭을 프론트에 하드코딩하면 기준정보 화면에서 이름을 바꿔도 반영되지 않는다.
 *
 * 타인에게 보이는 회원 요약(MemberSummaryResponse)과 달리 연락처·학번까지 담는 것은
 * 본인 정보만 내려가기 때문이다 (LY-03).
 *
 * capabilities는 이 회원이 실제로 할 수 있는 일의 코드 목록이다 (#9, ssccops-web#29가 소비).
 * 역할이나 권한 트리를 그대로 내리지 않고 **펼친 결과**만 내리는 것은, 웹이 트리 펼침 규칙을
 * 다시 구현하면 서버와 갈리기 때문이다 (BR-M28 — 폼 도메인에서 라벨 규칙이 두 경로로 갈려
 * 실제 버그가 났던 전례가 있다). 화면은 이 목록에 코드가 있는지만 보고 버튼을 감추거나
 * 비활성화하며, 계산은 서버의 AuthorityPolicy 한 곳에만 남는다.
 */
public record MemberProfileResponse(
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
        List<MemberRoleResponse> roles,
        List<String> capabilities) {

    /*
     * 등급·상태는 지연 로딩이라 이 변환은 트랜잭션 안에서 호출해야 한다.
     * 인증 주체에 실린 MemberEntity는 이미 준영속이므로 그대로 넘기면 안 된다.
     */
    public static MemberProfileResponse of(
            MemberEntity member, List<MemberRoleResponse> roles, List<String> capabilities) {
        return new MemberProfileResponse(
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
                roles,
                capabilities);
    }
}
