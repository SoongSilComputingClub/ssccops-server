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
 *
 * **rolePstnCd는 생략하면 NULL이고, 그것이 안전한 기본값이다** (#118). 값이 없는 역할은 승인도
 * 투표도 하지 못하므로, 새 역할을 만드는 것만으로 의사결정 자격이 딸려 오지 않는다 —
 * '동아리방국장'처럼 이름이 '국장'으로 끝난다는 이유로 승인권이 붙던 옛 접미사 판정과
 * 갈리는 자리다. 부서별 국장을 만들 때는 여기에 DIRECTOR를 명시해야 한다.
 *
 * **enum이 아니라 문자열로 받는 이유는 수정 요청과 같은 뜻을 갖게 하려는 것이다.** enum으로
 * 두면 Jackson이 빈 문자열을 역직렬화 단계에서 거절해, 화면의 빈 선택 상자가 보내는 `""`가
 * 생성에서는 400인데 수정에서는 '해제'로 통하는 상태가 된다(RoleUpdateRequest 주석 참고).
 * 여기서는 **null과 빈 문자열이 모두 '지정 없음'**이며, 기준 코드에 없는 값만 서비스가
 * 400 INVALID_CODE_VALUE로 끊는다 — 판정도 오류 응답도 수정 요청과 같은 한 곳에서 나온다.
 */
public record RoleCreateRequest(
        @NotBlank @Size(max = 100) String roleNm,
        @NotBlank @Size(max = 20) String roleClsfCd,
        @Min(1) @Max(32767) Integer indctSeqno,
        @Size(max = 20) String rolePstnCd) {}
