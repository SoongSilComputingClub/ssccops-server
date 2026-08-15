package org.sscc.ssccopsserver.domain.member.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;

/*
 * 역할 분류 한 건 (#80 목록·생성·수정 응답).
 *
 * 필드명은 컬럼명(roleClsfCd·roleClsfNm·indctSeqno)을 그대로 따른다 — 웹의 역할 분류 관리
 * 화면(ssccops-web#49)이 이 이름으로 소비한다.
 *
 * roleCount는 그 분류에 속한 역할 수이며 엔티티에 없는 값이라 팩토리가 따로 받는다. 분류마다
 * count 질의를 날리면 그대로 N+1이 되므로 집계는 서비스가 한 번에 해서 넘긴다 (DB-13,
 * FormLabelResponse.usageCount와 같은 방식). 화면은 이 값으로 삭제 버튼을 잠근다 — 0이 아니면
 * 삭제가 409 ROLE_CLASSIFICATION_IN_USE로 거절된다.
 *
 * **use_yn(비활성) 필드는 없다.** 데이터사전의 role_clsf에 그 컬럼이 없고, 없는 개념을 응답에
 * 만들면 화면이 서버에 저장되지 않는 상태를 그리게 된다 (폼 라벨과 갈리는 지점이다 — 그쪽은
 * 컬럼이 있어 비활성화가 삭제를 대신한다).
 *
 * crtDt·mdfcnDt도 없다. role_clsf에는 감사 컬럼 자체가 없다.
 */
public record RoleClassificationResponse(
        String roleClsfCd, String roleClsfNm, Integer indctSeqno, long roleCount) {

    public static RoleClassificationResponse of(
            MemberRoleClassificationEntity classification, long roleCount) {
        return new RoleClassificationResponse(
                classification.getCode(),
                classification.getName(),
                classification.getDisplayOrder(),
                roleCount);
    }
}
