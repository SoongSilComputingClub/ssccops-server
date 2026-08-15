package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationUpdateRequest;

/*
 * 역할 분류(role_clsf) 관리 (#80 · ssccops#23).
 *
 * 등급(mbr_grd)·상태(mbr_stts)와 성격이 다르다. 저쪽은 서버 코드가 enum으로 굳혀 쓰는 고정
 * 어휘라 화면에서 추가할 수 없지만, 역할 분류는 화면(/members/role-labels)에서 만들고 지우는
 * 사용자 관리 코드테이블이며 data.sql이 넣는 6종은 고정 어휘가 아니라 초기값이다.
 * 폼 라벨(FormLabelService)이 같은 성격의 선례다.
 *
 * 다만 폼 라벨과 갈리는 지점이 둘 있다. 하나는 use_yn이 없어 비활성화로 삭제를 대신할 수
 * 없다는 것이고(데이터사전), 다른 하나는 role.role_clsf_cd가 NOT NULL FK라 소속 역할이 있으면
 * 아예 지울 수 없다는 것이다.
 */
public interface RoleClassificationService {

    /** 분류 목록. indct_seqno 순이며 각 분류의 소속 역할 수를 함께 내려준다 */
    List<RoleClassificationResponse> getClassifications();

    /** 분류 생성. 코드는 요청이 정하고 서버는 형식·중복만 본다 */
    RoleClassificationResponse createClassification(RoleClassificationCreateRequest request);

    /** 이름·표시 순번 변경. 코드(PK)는 바뀌지 않으며 SYSTEM은 이름도 바꿀 수 없다 */
    RoleClassificationResponse updateClassification(
            String roleClsfCd, RoleClassificationUpdateRequest request);

    /** 삭제. SYSTEM이 아니고 소속 역할이 하나도 없을 때만 */
    void deleteClassification(String roleClsfCd);
}
