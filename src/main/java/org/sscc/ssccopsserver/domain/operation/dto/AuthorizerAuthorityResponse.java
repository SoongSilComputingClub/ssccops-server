package org.sscc.ssccopsserver.domain.operation.dto;

/*
 * 유형 관리 폼의 승인자 선택지 한 줄 (#123 · GET /v1/sub-work-types/authorizer-authorities).
 *
 * 코드 어휘는 AuthorityCode.subWorkApprovers()가 정하지만 표시명은 authrt_nm(운영 데이터)이라
 * 서버가 합쳐 내린다 — 웹이 코드 → 이름 사전을 하드코딩하면(직위 코드 시절 AUTZR_ROLE_NM)
 * 권한 개명 즉시 화면이 어긋난다.
 */
public record AuthorizerAuthorityResponse(String authrtCd, String authrtNm) {}
