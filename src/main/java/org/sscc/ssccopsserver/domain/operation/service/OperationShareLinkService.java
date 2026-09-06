package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.OperationShareLinkResponse;
import org.sscc.ssccopsserver.domain.operation.dto.PublicSharePreviewResponse;

public interface OperationShareLinkService {

    OperationShareLinkResponse issue(Long operationId, MemberEntity creator);

    void revoke(Long operationId);

    PublicSharePreviewResponse preview(String token);
}
