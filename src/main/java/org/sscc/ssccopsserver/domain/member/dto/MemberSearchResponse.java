package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

/*
 * 회원 목록 조회의 서비스 반환값 (#76). 목록 응답은 data 배열과 page 봉투 두 갈래라(AP-11)
 * 서비스가 둘을 함께 돌려주고 컨트롤러가 ApiResponse에 나눠 싣는다
 * (WorkSearchResponse와 같은 모양).
 */
public record MemberSearchResponse(List<MemberSummaryResponse> members, PageResponse page) {}
