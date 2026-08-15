package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/*
 * 사용자 정의 묶음 권한 생성 요청 (#65 POST /v1/authorities).
 *
 * sys_yn은 요청에 없다 — 화면에서 만든 권한은 언제나 false다 (BR-M32). 요청으로 받으면
 * true를 실어 보내는 것만으로 삭제·코드 변경 보호를 스스로 걸 수 있고, 그렇게 만들어진 권한은
 * 코드가 가리키지 않는데도 지울 수 없게 된다.
 *
 * authrtCd는 PK이며 @RequireAuthority가 가리키는 값과 같은 이름 공간을 쓴다. 대문자·숫자·
 * 밑줄만 허용하는 것은 AuthorityCode enum의 표기(UPPER_SNAKE_CASE)와 어긋나면 화면에 두 벌의
 * 어휘가 생기기 때문이다.
 *
 * upAuthrtCd가 null이면 최상위 권한이 된다.
 */
public record AuthorityCreateRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String authrtCd,
        @NotBlank @Size(max = 50) String authrtNm,
        @Size(max = 50) String upAuthrtCd,
        @Size(max = 500) String authrtExpln,
        Short indctSeqno) {}
