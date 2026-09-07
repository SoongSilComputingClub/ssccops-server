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
 *
 * **그리고 상세에서도 MEMBER_MANAGE가 있을 때만 담는다** (#277). 그전에는 권한과 무관하게 언제나
 * 실려 나갔는데, 이 엔드포인트를 지키는 것은 RESPONSE_REVIEW 하나라 **연락처를 볼 자격이 없는
 * 심사자의 브라우저에도 값이 도착해 있었다.** 웹이 화면에서 감추고 CSV에서 열을 빼도(ssccops#223)
 * 그것은 표시를 고른 것이지 값을 막은 것이 아니다 — 개발자 도구를 열면 그대로 보인다.
 *
 * 연락처가 특별한 값인 이유는 계정 연결 판정의 재료이기 때문이다 — 학번·회원명·연락처 3종 일치로
 * 이관 회원이 계정을 붙인다(MemberLinkPolicy · ssccops#78). 학번과 이름은 명부를 본 사람이면 알
 * 수 있지만 연락처는 MEMBER_MANAGE 없이는 어느 화면에서도 보이지 않는다.
 *
 * 기수(genNo)·학년(scyrNo)은 함께 가리지 않는다. 그 둘은 심사에 쓰이는 값이고 회원 관리 권한
 * 없이도 다른 화면에서 보인다 — 연락처와 성격이 다르다.
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

    /**
     * @param canSeeContact 요청자가 MEMBER_MANAGE를 가졌는가. false면 연락처 자리가 null이다. 빈 문자열이 아니라 null인 것은 "값이
     *     없는 회원"과 "볼 수 없는 요청자"를 화면이 같은 방식으로 다뤄도 되기 때문이다 — 어느 쪽이든 그릴 것이 없다.
     */
    public static ResponseMemberDetail from(MemberEntity member, boolean canSeeContact) {
        return new ResponseMemberDetail(
                member.getId(),
                member.getName(),
                member.getStudentNumber(),
                member.getDepartmentName(),
                member.getMembershipGrade().getCode(),
                member.getMembershipStatus().getCode(),
                member.getGenerationNumber(),
                member.getAcademicYear(),
                canSeeContact ? member.getPhoneNumber() : null);
    }
}
