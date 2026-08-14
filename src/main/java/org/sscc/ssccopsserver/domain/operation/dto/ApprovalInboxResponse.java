package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

/*
 * 승인함 조회 결과 (OPS-017). 컨트롤러가 data 배열과 page 봉투 두 갈래로 나눠 내린다 (AP-11).
 * SubWorkSearchResponse와 같은 모양이며, 서비스가 두 값을 함께 만들어야 해서 record로 묶는다.
 */
public record ApprovalInboxResponse(List<ApprovalInboxItemResponse> approvals, PageResponse page) {}
