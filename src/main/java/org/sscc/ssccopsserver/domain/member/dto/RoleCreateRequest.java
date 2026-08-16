package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 역할 생성 요청 (#79 POST /v1/roles).
 *
 * **roleNm이 @NotBlank인 것은 데이터사전과 어긋나는 것이 아니다.** 사전의 role_nm은 NULL을
 * 허용하지만(Not Null = N) 이름 없는 역할은 화면 목록·드롭다운에서 고를 수 없고 회원에게
 * 부여할 수도 없다 — 사전이 허용하는 것과 화면이 만들 수 있는 것은 다른 축이며, 여기서는
 * 좁은 쪽을 계약으로 삼는다.
 *
 * indctSeqno는 생략할 수 있다. 채워 넣는 값은 같은 분류(roleClsfCd) 안의 최대값 + 1이며
 * (RoleServiceImpl 참고) 분류를 가로지르는 서열이 아니라 분류 안의 표시 순번이다 (VR-M11).
 * 상한이 32767인 것은 컬럼이 SMALLINT이기 때문이다.
 */
public record RoleCreateRequest(
        @NotBlank @Size(max = 100) String roleNm,
        @NotBlank @Size(max = 20) String roleClsfCd,
        @Min(1) @Max(32767) Integer indctSeqno) {}
