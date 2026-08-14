package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

/*
 * 상위 업무 목록 조회(OPS-020) 결과. 카드 목록과 페이지 봉투를 함께 들고 나온다.
 *
 * 이 record 자체가 응답 본문이 되지는 않는다 — AP-11의 목록 응답은 data가 배열이고 page가
 * 그 옆에 오는 형태라, Controller가 여기서 둘을 꺼내 ApiResponse.success(data, page)로 싼다.
 * Service가 ApiResponse를 직접 만들면 응답 봉투를 서비스 계층이 알게 된다 (LY-03).
 */
public record WorkSearchResponse(List<WorkListItemResponse> works, PageResponse page) {}
