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
 *
 * **rolePstnCd에는 '해제'라는 네 번째 뜻이 필요해 enum이 아니라 문자열로 받는다** (#118).
 * 다른 세 필드와 달리 이 값은 비울 수 있어야 한다 — 잘못 지정한 직위 코드를 되돌릴 길이
 * 없으면 그 역할은 영영 승인·투표 자격을 갖는다. record는 '필드 없음'과 'null'을 구별하지
 * 못하고(#65 AuthorityUpdateRequest 주석), 여기서 null을 '해제'로 읽으면 이 필드를 모르는
 * 기존 역할 관리 화면(ssccops-web#49)이 이름만 고쳐 보낼 때마다 직위 코드가 조용히 지워진다.
 * 그래서 **null = 그대로 두라 · 빈 문자열 = 해제 · 그 밖 = 그 코드로 지정**이며, 기준 코드에
 * 없는 값은 서비스가 400 INVALID_CODE_VALUE로 끊는다. 생성 요청도 같은 이유로 이 필드만
 * 문자열이며 같은 변환을 쓴다 — 한쪽만 enum으로 두면 화면의 빈 선택 상자가 보내는 `""`가
 * 생성에서는 400인데 수정에서는 해제로 통한다.
 */
public record RoleUpdateRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*") String roleNm,
        @Size(max = 20) @Pattern(regexp = ".*\\S.*") String roleClsfCd,
        @Min(1) @Max(32767) Integer indctSeqno,
        @Size(max = 20) String rolePstnCd) {}
