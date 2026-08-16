package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/*
 * 역할 수정 요청 (#79 PATCH /v1/roles/{roleId}).
 *
 * **여기서는 PATCH가 실제로 부분 수정이다** — null인 필드는 건드리지 않는다.
 * AuthorityUpdateRequest(#65)가 메서드만 PATCH이고 본문은 노드 한 벌 전체였던 것과 갈리는데,
 * 그쪽은 upAuthrtCd의 '생략'과 'null'이 각각 "건드리지 마라"와 "최상위로 올려라"라는 서로 다른
 * 뜻이라 구별이 필요했다. 역할의 세 필드에는 그런 자리가 없다 — 이름과 분류는 비울 수 없고
 * (분류는 FK NOT NULL, 이름은 아래 @Pattern이 막는다) 순번도 NOT NULL이라, null은 언제나
 * "그대로 두라" 하나로만 읽힌다.
 *
 * roleNm에 @NotBlank가 아니라 @Pattern을 쓰는 것은 그 때문이다. null은 통과시키고(=미지정),
 * 실려 온 값이 공백뿐이면 400 VALIDATION_FAILED로 끊는다.
 *
 * roleClsfCd만 바꾸고 indctSeqno를 생략하면 순번은 새 분류 안의 최대값 + 1로 다시 매겨진다
 * (RoleServiceImpl 참고) — 옛 분류에서 쓰던 숫자는 새 분류 안에서 아무 뜻이 없기 때문이다.
 */
public record RoleUpdateRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*") String roleNm,
        @Size(max = 20) @Pattern(regexp = ".*\\S.*") String roleClsfCd,
        @Min(1) @Max(32767) Integer indctSeqno) {}
