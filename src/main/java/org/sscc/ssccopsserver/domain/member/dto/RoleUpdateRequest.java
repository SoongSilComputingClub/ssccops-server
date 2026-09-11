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
 * 정규식이 `.*\S.*`가 아니라 NOT_BLANK(`\s*\S[\s\S]*`)인 것은 백트래킹 때문이다 (#357 ·
 * Sonar S8786). `.*\S.*`는 `.*` 둘이 겹쳐 "x…x + 줄바꿈" 꼴에서 길이의 제곱으로 돈다 —
 * 10만 자에 13초를 쟀다. 옆의 @Size(max)는 이것을 막지 못한다. Bean Validation은 한 필드의
 * 제약을 전부 돌리지 @Size가 실패했다고 @Pattern을 건너뛰지 않으므로, 상한을 넘긴 값도
 * 정규식을 끝까지 탄다. 새 식은 앞의 `\s*`와 `\S`가 겹치지 않아 선형이고, 뜻은 그대로
 * "공백 아닌 글자가 하나는 있다"다. 다른 점은 줄바꿈뿐이다 — 옛 식은 `.`이 줄바꿈을 못 받아
 * 줄바꿈이 낀 이름을 우연히 거절했는데, 등록(RoleCreateRequest)의 @NotBlank는 그것을
 * 받으므로 두 요청이 이제 같은 값을 받는다. 회귀 테스트는 RoleControllerTest에 있다.
 *
 * roleClsfCd만 바꾸고 indctSeqno를 생략하면 순번은 새 분류 안의 최대값 + 1로 다시 매겨진다
 * (RoleServiceImpl 참고) — 옛 분류에서 쓰던 숫자는 새 분류 안에서 아무 뜻이 없기 때문이다.
 *
 * 승인·투표 자격 필드는 없다 (#123). 직위 코드(rolePstnCd) 시절에는 이 요청만 '빈 문자열 =
 * 해제'라는 특례를 가졌는데 — 필드를 모르는 화면이 이름만 고쳐 보내도 자격이 지워지지 않게
 * 하려는 방어였다 — 자격이 역할별 권한 화면으로 옮겨 가며 특례째 사라졌다.
 */
public record RoleUpdateRequest(
        @Size(max = 100) @Pattern(regexp = NOT_BLANK) String roleNm,
        @Size(max = 20) @Pattern(regexp = NOT_BLANK) String roleClsfCd,
        @Min(1) @Max(32767) Integer indctSeqno) {

    /** 공백 아닌 글자가 하나는 있다. 선형 정규식 — 위 주석 참고 */
    static final String NOT_BLANK = "\\s*\\S[\\s\\S]*";
}
