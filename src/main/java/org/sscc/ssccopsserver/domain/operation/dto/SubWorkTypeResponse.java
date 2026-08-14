package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;

/*
 * 하위 업무 유형 응답 (OPS-018 · OPS-019).
 *
 * 목록·등록·수정·사용 전환이 모두 이 한 모양을 쓴다. 저장 응답만 따로 두면 한쪽에 필드가
 * 늘었을 때 두 응답이 조용히 어긋난다 (회원가입·세션 조회가 MemberProfileResponse를 함께
 * 쓰는 것과 같은 이유다).
 *
 * crtr_amt·expnd_yn은 싣지 않는다. 화면이 쓰지 않는 값이고, 이 API가 쓰지도 않는 값을
 * 응답에만 노출하면 프론트가 그 값을 근거로 무언가를 만들게 된다.
 *
 * 완료 점검 항목은 저장 형태(개행 구분 TEXT)가 아니라 배열로 내보낸다. 구분자를 계약에
 * 노출하면 화면과 서버가 서로 다른 구분자를 가정하게 된다 — 목록의 '·' 연결은 표시 규칙이다.
 */
public record SubWorkTypeResponse(
        Long subWorkTypeId,
        String typeName,
        boolean approvalNeeded,
        String authorizerRoleCode,
        boolean minAgreeCountNeeded,
        Integer minAgreeCount,
        List<String> completionCheckArticles,
        boolean useYn) {

    public static SubWorkTypeResponse from(SubWorkTypeEntity subWorkType) {
        return new SubWorkTypeResponse(
                subWorkType.getId(),
                subWorkType.getTypeName(),
                subWorkType.isApprovalNeeded(),
                subWorkType.getAuthorizerRoleCode(),
                subWorkType.isMinAgreeCountNeeded(),
                subWorkType.getMinAgreeCount(),
                subWorkType.completionCheckArticles(),
                subWorkType.isActive());
    }
}
