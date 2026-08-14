package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 라벨 사용 여부 토글 요청 (PATCH /v1/form-labels/{formLblId}).
 *
 * 활성화·비활성화가 같은 엔드포인트를 쓰므로 액션이 아니라 값을 받는다 — 관리 화면의 토글이
 * 같은 자리에서 켜지고 꺼지기 때문이다 (SubWorkChecklistItemUpdateRequest와 같은 판단).
 *
 * boolean이 아니라 Boolean인 것은 의도된 것이다. 원시 타입이면 필드를 빠뜨린 요청이 false로
 * 역직렬화돼 '비활성화'로 처리된다 — 누락은 400(VALIDATION_FAILED)이어야 한다.
 *
 * 이름 수정은 받지 않는다. 라벨명을 바꾸면 그 라벨이 이미 붙어 있는 과거 폼의 분류 표기가
 * 소급해 바뀌므로, 이름 변경은 별도 결정이 필요한 행위라 이 이슈의 범위에 넣지 않았다.
 */
public record FormLabelUpdateRequest(@NotNull Boolean useYn) {}
