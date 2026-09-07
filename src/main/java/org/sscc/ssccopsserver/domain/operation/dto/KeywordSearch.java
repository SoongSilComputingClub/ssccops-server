package org.sscc.ssccopsserver.domain.operation.dto;

/*
 * 제목 검색어를 해석하고 LIKE 패턴으로 바꾸는 유일한 자리 (ssccops#216).
 *
 * 업무(OPS-020)와 하위 업무(OPS-008)가 **같은 규칙**을 써야 한다. 두 목록은 회의 안건 추가
 * 화면에서 나란히 놓이므로, 한쪽만 대소문자를 가리거나 한쪽만 `%`를 특수문자로 다루면 같은
 * 검색어가 종류를 바꾼 순간 다른 결과를 낸다. 규칙을 두 벌로 적지 않으려고 여기로 모았다.
 *
 * **와일드카드는 이스케이프한다.** 검색창은 질의 언어가 아니라 제목 입력란이라, `50%`를 친
 * 사람이 기대하는 것은 "50%가 들어간 제목"이지 "50으로 시작하는 모든 제목"이 아니다.
 * 이스케이프하지 않으면 `%` 한 글자가 전체 목록이 되고 `_`는 아무 글자에나 맞아, 사용자가
 * 배운 적 없는 문법이 조용히 동작한다.
 *
 * 이스케이프 문자로 역슬래시가 아니라 `!`를 쓴다. JPQL 문자열 리터럴 안의 역슬래시는 자바
 * 소스·JPQL·JDBC 세 겹을 지나며 몇 번 이스케이프해야 하는지가 드라이버마다 갈리는데,
 * `!`는 어느 층에서도 특별한 뜻이 없어 H2와 PostgreSQL이 같게 읽는다.
 */
public final class KeywordSearch {

    /*
     * LIKE의 escape 절에 그대로 들어간다. 바꾸려면 escapeWildcards와 두 RepositoryImpl의
     * JPQL을 함께 봐야 한다 — 한쪽만 바꾸면 이스케이프한 문자가 그대로 노출된다.
     */
    public static final char ESCAPE = '!';

    private KeywordSearch() {}

    /*
     * 검색어를 조회 조건으로 쓸 수 있는 형태로 만든다. 공백만 남는 입력은 조건 없음(null)이다 —
     * 빈 문자열을 살려 보내면 `%%`가 되어 전체 조회와 결과는 같은데 조건만 붙는다.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /*
     * 부분 일치 패턴. 앞뒤 `%`는 우리가 붙이고, 사용자가 친 `%`·`_`·`!`는 리터럴로 만든다.
     * normalize를 지난 값을 받는다는 전제이며 null을 넘기지 않는다.
     */
    public static String toLikePattern(String normalized) {
        return "%" + escapeWildcards(normalized) + "%";
    }

    private static String escapeWildcards(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            // ESCAPE 자신을 먼저 막지 않으면 사용자가 친 '!'가 뒤 글자를 삼킨다
            if (ch == ESCAPE || ch == '%' || ch == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }
}
