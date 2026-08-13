package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;

public interface SubWorkService {

    /*
     * 하위 업무를 등록한다 (OPS-007). oper·sub_work·완료 체크리스트가 한 트랜잭션에서
     * 생성되고, 상위 업무의 진행률도 같은 트랜잭션에서 다시 집계된다.
     * registrant는 인증 주체(등록자)이며 요청 본문이 아니라 토큰에서 온다 (LY-05).
     */
    SubWorkCreateResponse createSubWork(SubWorkCreateRequest request, MemberEntity registrant);
}
