package org.sscc.ssccopsserver.domain.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/*
 * 행사 분류 생성 요청 (ssccops#140 · POST /v1/event-categories).
 *
 * 코드는 서버가 채번하지 않고 요청 본문으로 받는다 — event_clsf_cd는 데이터사전 표준코드
 * 시트에 사람이 등재하는 값이라 읽을 수 있어야 한다 (RoleClassificationCreateRequest와 같은
 * 판단). 형식은 시드 4종(RECRUIT·SEMINAR·PROJECT·EVENT)과 같은 UPPER_SNAKE_CASE로 못 박는다 —
 * ^[A-Z][A-Z0-9_]{1,19}$ (컬럼이 VARCHAR(20)이고 한 글자 코드는 시트에서 구별되지 않는다).
 *
 * 새 분류를 만들 때마다 데이터사전의 표준코드 시트에 그 코드를 등재해야 한다.
 *
 * indctSeqno는 화면 표시·정렬 순서다. 생략하면 서비스가 기본값으로 뒤쪽에 밀어 둔다.
 */
public record EventCategoryCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,19}$") String eventClsfCd,
        @NotBlank @Size(max = 50) String eventClsfNm,
        Integer indctSeqno) {}
