package org.sscc.ssccopsserver.domain.form.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 응답 목록에 실리는 응답자 요약 (#37 · GET /v1/forms/{formId}/responses).
 *
 * **응답자 정보는 응답 행에 복사하지 않는다.** form_rspns_hstry에는 mbr_id만 있고 성명·학번·
 * 학과는 전부 mbr에서 조인해 온다 — 복사해 두면 회원이 학과를 바꾸거나 상태가 졸업으로 넘어간
 * 뒤에도 응답 목록만 옛 값을 보여주고, 어느 쪽이 맞는지 알 방법이 없다. 웹 응답 목록이 회원
 * 상세로 이동하는 링크를 제공하는 것도 이 구조 덕분이다.
 *
 * 운영 도메인의 MemberSummaryResponse(식별자·이름 두 값)를 재사용하지 않았다. 응답 목록 표의
 * 컬럼이 학번·학과·등급·상태까지라 그쪽에 필드를 더하면 담당자·등록자·협업자를 담던 응답에도
 * 연락처에 가까운 값이 함께 늘어난다 (LY-03).
 *
 * 등급·상태는 코드 문자열이다. 화면이 배지 색과 라벨을 코드로 고르고 있어 이름을 함께 내릴
 * 이유가 없고, 이름까지 실으면 기준 코드 표가 응답마다 반복된다.
 *
 * mbr_id는 NOT NULL이라(ssccops #61 · 비회원 응답 폐기) 이 값이 비는 경우는 없다.
 */
public record ResponseMemberSummary(
        Long mbrId,
        String mbrNm,
        String stdntNo,
        String scsbjtNm,
        String mbrGrdCd,
        String mbrSttsCd) {

    public static ResponseMemberSummary from(MemberEntity member) {
        return new ResponseMemberSummary(
                member.getId(),
                member.getName(),
                member.getStudentNumber(),
                member.getDepartmentName(),
                member.getMembershipGrade().getCode(),
                member.getMembershipStatus().getCode());
    }
}
