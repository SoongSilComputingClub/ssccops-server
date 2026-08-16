package org.sscc.ssccopsserver.domain.form.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 응답 상세에 실리는 응답자 정보 (#37 · GET /v1/forms/{formId}/responses/{formRspnsId}).
 *
 * 목록(ResponseMemberSummary)에 기수·학년·연락처 세 값을 더한 것이다. record는 상속이 없어
 * 필드를 다시 적었지만 JSON 모양은 웹 타입(ResponseMemberDetailResponse extends
 * ResponseMemberResponse)과 같은 평탄한 한 겹이다 — 중첩시키면 목록과 상세가 같은 회원을
 * 다른 경로로 읽게 된다.
 *
 * 연락처(telno)를 목록에 싣지 않고 상세에만 두는 것은 의도된 것이다. 목록은 수백 건이 한 번에
 * 나가는 화면이라, 심사에 필요하지 않은 개인정보가 그 규모로 오갈 이유가 없다.
 */
public record ResponseMemberDetail(
        Long mbrId,
        String mbrNm,
        String stdntNo,
        String scsbjtNm,
        String mbrGrdCd,
        String mbrSttsCd,
        Integer genNo,
        Integer scyrNo,
        String telno) {

    public static ResponseMemberDetail from(MemberEntity member) {
        return new ResponseMemberDetail(
                member.getId(),
                member.getName(),
                member.getStudentNumber(),
                member.getDepartmentName(),
                member.getMembershipGrade().getCode(),
                member.getMembershipStatus().getCode(),
                member.getGenerationNumber(),
                member.getAcademicYear(),
                member.getPhoneNumber());
    }
}
