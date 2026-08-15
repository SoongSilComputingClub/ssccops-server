package org.sscc.ssccopsserver.domain.member.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;

/*
 * 회원 상태 기준 코드 한 건 (GET /v1/member-statuses, #76). 규칙은 MemberGradeResponse와 같다.
 *
 * 이 목록에는 가입 시 고를 수 없는 상태(탈퇴·제명 등)도 들어 있다 — 기준 코드 전체를 내리는
 * 엔드포인트이고, 가입 화면에서 고를 수 있는지는 MemberStatusCode.isSignupSelectable()이
 * 정하는 별개의 규칙이기 때문이다.
 */
public record MemberStatusResponse(String code, String name, Integer displayOrder) {

    public static MemberStatusResponse from(MemberStatusEntity status) {
        return new MemberStatusResponse(
                status.getCode(), status.getName(), status.getDisplayOrder());
    }
}
