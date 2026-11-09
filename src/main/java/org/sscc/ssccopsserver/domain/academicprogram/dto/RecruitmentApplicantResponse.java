package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.form.dto.ResponseMemberDetail;

/*
 * 리더가 읽는 지원서의 지원자 (ssccops#625 · GET .../recruitment/applications/{formRspnsId}).
 *
 * 운영자용 상세의 응답자(ResponseMemberDetail)에서 **연락처(telno)를 뺀** 모양이다. 필드를 null로
 * 두는 것이 아니라 아예 없다 — 그 record를 그대로 쓰면 telno가 계약에 남고, 리더가 MEMBER_MANAGE를 함께
 * 가진 회원이면(회장이 스터디장을 겸하는 경우) 실제 값이 실려 나간다. 연락처는 계정 연결 판정의 재료라
 * 회원 관리 권한 밖으로 내보내지 않는다(#277 · ResponseMemberDetail 주석). 리더는 운영진이 아니다
 * (ADR-0067) — 사용자 결정(2026-11-09): 연락처 제외.
 *
 * 기수(genNo)·학년(scyrNo)은 싣는다. 운영자용 상세가 그 둘을 가리지 않는 이유(심사에 쓰는 값이고 회원
 * 관리 권한 없이도 다른 화면에서 보인다)가 리더에게도 같다 — 누가 어떤 학년으로 지원했는지는 스터디를
 * 꾸리는 사람이 볼 값이다.
 *
 * 등급·상태는 목록(ResponseMemberSummary)과 같이 코드 문자열이다 — 화면이 배지를 코드로 고른다.
 */
public record RecruitmentApplicantResponse(
        Long mbrId,
        String mbrNm,
        String stdntNo,
        String scsbjtNm,
        String mbrGrdCd,
        String mbrSttsCd,
        Integer genNo,
        Integer scyrNo) {

    /* telno는 옮기지 않는다 — 위 주석. 받는 쪽에 자리가 없으므로 실수로 옮길 수도 없다 */
    public static RecruitmentApplicantResponse from(ResponseMemberDetail member) {
        return new RecruitmentApplicantResponse(
                member.mbrId(),
                member.mbrNm(),
                member.stdntNo(),
                member.scsbjtNm(),
                member.mbrGrdCd(),
                member.mbrSttsCd(),
                member.genNo(),
                member.scyrNo());
    }
}
