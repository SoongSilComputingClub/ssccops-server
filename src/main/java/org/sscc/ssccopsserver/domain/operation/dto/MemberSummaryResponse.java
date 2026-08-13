package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 운영 API 응답에 실리는 회원 요약. 담당자·등록자·협업자가 모두 이 형태를 쓴다.
 *
 * 상세 화면이 담당자를 이름으로 표시하므로 식별자만으로는 부족하고, 그렇다고 회원 엔티티를
 * 그대로 내보내면 연락처·학번까지 함께 노출된다 (LY-03). 화면이 쓰는 두 값만 담는다.
 *
 * 기수(mbr.gen_no)는 담지 않는다 — 상세 화면 시안에 기수 표기가 있었으나 프론트 디자인에서
 * 제외하기로 했고, 운영 건 자체의 기수 컬럼은 데이터사전 개정으로 삭제된 결번이다.
 */
public record MemberSummaryResponse(Long memberId, String name) {

    public static MemberSummaryResponse from(MemberEntity member) {
        return member == null ? null : new MemberSummaryResponse(member.getId(), member.getName());
    }
}
