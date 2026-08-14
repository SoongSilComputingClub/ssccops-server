package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * 하위 업무 유형이 지정하는 승인 주체 역할 (sub_work_type.autzr_role_cd).
 * 이 역할을 가진 사람만 그 유형의 하위 업무를 최종 승인(TR-03)할 수 있다.
 *
 * 엔티티는 이 값을 enum이 아니라 String으로 들고 있다. 하위 업무 상세(OPS-009)가 이미
 * 문자열로 내보내고 있어 바꾸면 그 응답까지 함께 흔들리기 때문이며, 기준 코드 검증은
 * 요청 DTO가 이 enum으로 받는 것으로 충분하다 — 목록에 없는 값은 역직렬화 단계에서
 * 걸려 전역 핸들러가 INVALID_CODE_VALUE로 바꾼다.
 *
 * 회원의 실제 역할은 role 테이블에 한글 역할명(회장·부회장·총무·국장)으로 들어 있어
 * 이 코드와 어휘가 다르다. 둘을 잇는 일은 승인 집행(OPS-014)의 몫이라 여기서 다루지 않는다.
 */
public enum AuthorizerRole {
    PRESIDENT,
    VICE_PRESIDENT,
    TREASURER,
    DIRECTOR
}
