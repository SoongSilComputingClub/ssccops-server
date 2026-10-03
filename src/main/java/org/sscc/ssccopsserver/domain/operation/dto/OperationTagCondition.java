package org.sscc.ssccopsserver.domain.operation.dto;

/*
 * 페이징이 없는 전량 목록의 태그 필터 (#637) — 회의 목록(GET /v1/meetings)과 운영 통합
 * (GET /v1/operations)의 쿼리 파라미터 tagId. 업무·하위 업무 목록은 이 값을 각자의
 * *SearchCondition에 함께 받는다.
 *
 * @RequestParam이 아니라 record인 것은 MCP 도구가 컨트롤러의 조건 record를 그대로 입력으로 받는
 * 규약(OperationTools 클래스 주석) 때문이다 — 필드 이름이 곧 쿼리 파라미터 이름이다.
 *
 * 없는 태그 id는 400이 아니라 빈 결과다 — 지운 태그의 칩을 누른 화면이 오류를 볼 이유가 없다.
 *
 * 쓰는 쪽에 @ParameterObject가 붙는다(NotificationAppCondition과 같은 이유) — 없으면 springdoc이
 * «condition이라는 필수 쿼리 파라미터»로 내 OpenAPI 하위 호환 게이트(ADR-0032)가 새 필수 파라미터로
 * 막는다. 문서에만 영향을 주고 바인딩은 그대로라 스펙에는 선택 파라미터 tagId가 더해질 뿐이다.
 */
public record OperationTagCondition(Long tagId) {}
