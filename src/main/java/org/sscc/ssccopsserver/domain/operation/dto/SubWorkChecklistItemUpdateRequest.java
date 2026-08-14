package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 완료 체크리스트 항목 체크·해제 요청 (OPS-013).
 *
 * 체크와 해제가 같은 엔드포인트를 쓰므로 액션이 아니라 값을 받는다 — 화면의 체크박스가
 * 같은 자리에서 켜지고 꺼지기 때문이다.
 *
 * boolean이 아니라 Boolean인 것은 의도된 것이다. 원시 타입이면 필드를 아예 빠뜨린 요청이
 * false로 역직렬화돼 '해제'로 처리된다 — 누락은 400(VALIDATION_FAILED)이어야 한다.
 *
 * 항목 내용(article)·순서(sortOrder)는 받지 않는다. 유형에서 복사된 값이라 사후 수정은
 * 유형 관리(OPS-019) 몫이다.
 */
public record SubWorkChecklistItemUpdateRequest(@NotNull Boolean isCompleted) {}
