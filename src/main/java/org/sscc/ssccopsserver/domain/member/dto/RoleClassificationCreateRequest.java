package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/*
 * 역할 분류 생성 요청 (#80 POST /v1/role-classifications).
 *
 * **코드는 서버가 채번하지 않고 요청 본문으로 받는다.** 자동 채번(CLSF_6 …)이 짧지만,
 * role_clsf_cd는 데이터사전의 표준코드 시트에 사람이 등재하는 값이라 읽을 수 있어야 하고
 * 그것이 이 컬럼에 코드V20 도메인을 쓰는 이유다 — 의미 없는 일련번호를 등재하면 시트가
 * 아무것도 설명하지 못하게 된다. 운영진이 'TF'처럼 뜻이 드러나는 코드를 직접 정한다.
 *
 * 그 대가로 오타·소문자·중복이 그대로 PK가 될 수 있어 형식을 서버가 검증한다 —
 * ^[A-Z][A-Z0-9_]{1,19}$. 대문자로 시작하고 대문자·숫자·밑줄만 쓰며 길이는 2~20자다
 * (컬럼이 VARCHAR(20)이고, 한 글자 코드는 시트에서 구별되지 않는다). 표기를 시드 5종
 * (POSITION·DEPT·PROJECT·STUDY·EVENT)과 같은 UPPER_SNAKE_CASE로 못 박는 것은 어긋나면
 * 화면과 시트에 두 벌의 어휘가 생기기 때문이다. AuthorityCreateRequest.authrtCd와 같은 태도다.
 *
 * **새 분류를 만들 때마다 데이터사전의 표준코드 시트에 그 코드를 등재해야 한다.** 시트가
 * SSoT이므로 여기서만 만들어 두면 코드값의 근거가 어디에도 남지 않는다. (시드의 SYSTEM이
 * 아직 미등재인 것은 SoongSilComputingClub/ssccops#74에서 다룬다.)
 *
 * indctSeqno는 화면 표시·정렬 순서다. 생략하면 서비스가 기본값으로 뒤쪽에 밀어 둔다 —
 * 순서를 정하지 않았다고 생성이 막힐 이유가 없다.
 */
public record RoleClassificationCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,19}$") String roleClsfCd,
        @NotBlank @Size(max = 50) String roleClsfNm,
        Integer indctSeqno) {}
