package org.sscc.ssccopsserver.domain.member.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;

/*
 * 회원 등급 기준 코드 한 건 (GET /v1/member-grades, #76).
 *
 * 화면의 등급 필터·등급 변경 셀렉트가 이 목록으로 채워진다. 코드와 명칭을 함께 내리는 것은
 * 회원 응답과 같은 규칙이며(MemberProfileResponse 주석), 명칭을 프론트에 하드코딩하면
 * 기준정보를 고쳐도 반영되지 않는다.
 *
 * displayOrder(indct_seqno)를 함께 내리는 것은 정렬 근거를 화면에 남기기 위해서다 — 서버가
 * 이미 그 순서로 내리지만, 클라이언트가 목록을 다시 조합할 때 순서를 잃지 않게 한다.
 */
public record MemberGradeResponse(String code, String name, Integer displayOrder) {

    public static MemberGradeResponse from(MemberGradeEntity grade) {
        return new MemberGradeResponse(grade.getCode(), grade.getName(), grade.getDisplayOrder());
    }
}
