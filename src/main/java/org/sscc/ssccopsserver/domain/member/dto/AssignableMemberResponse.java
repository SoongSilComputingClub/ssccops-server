package org.sscc.ssccopsserver.domain.member.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 담당자·회의 책임자로 지정할 수 있는 회원 요약 (GET /v1/members/assignable, #76).
 *
 * **연락처·이메일·학번을 담지 않는다.** 이 엔드포인트는 MEMBER_MANAGE 없이 인증만으로
 * 부를 수 있다 — 업무 등록 화면의 담당자 선택 칩이 쓰는 목록이라 회원 관리 권한을 요구할 수
 * 없기 때문이다(ssccops-web#53). 권한 없이 부르는 목록에 개인정보를 실으면 회원 명부가
 * 그대로 새어 나가므로, 사람을 **고르는 데 필요한 최소한**만 남긴다:
 * 누구인지(이름·기수)와 어떤 사람인지(등급·대표 역할).
 *
 * 필드를 늘려야 한다고 느낀다면 그것은 회원 관리 목록(MemberSummaryResponse)이 필요한
 * 화면이라는 뜻이다 — 여기에 더하지 말고 그쪽을 쓸 것.
 *
 * representativeRoleName은 현재 역할 중 rprs_role_yn = true인 것의 이름이다. 대표 역할은
 * **표시용**이라 여기서만 쓰고 정렬·판정에는 쓰지 않는다 (BR-M26). 대표로 지정된 역할이
 * 없으면 null이며, 화면은 그 자리를 비워 둔다.
 */
public record AssignableMemberResponse(
        Long memberId,
        String name,
        Integer generationNumber,
        String membershipGradeCode,
        String membershipGradeName,
        String representativeRoleName) {

    public static AssignableMemberResponse of(MemberEntity member, String representativeRoleName) {
        return new AssignableMemberResponse(
                member.getId(),
                member.getName(),
                member.getGenerationNumber(),
                member.getMembershipGrade().getCode(),
                member.getMembershipGrade().getName(),
                representativeRoleName);
    }
}
