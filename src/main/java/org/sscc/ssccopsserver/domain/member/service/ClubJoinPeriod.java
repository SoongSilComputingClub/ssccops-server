package org.sscc.ssccopsserver.domain.member.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * 명부의 '동아리 가입 시기' 한 칸을 연·월로 나눈 값 (#205).
 *
 * **읽는 자리가 둘이라 값 객체로 꺼냈다** — CSV 검증(MemberImportValidator)이 형식을 보고,
 * 이관 실행(MemberImportRowExecutor)이 같은 값을 컬럼에 넣는다. 두 곳이 각자 파싱하면 검증을
 * 통과한 행이 실행에서 다르게 읽혀 조용히 다른 값이 들어간다.
 *
 * ── 받아들이는 모양 ────────────────────────────────────────
 *   2020-03-02 · 2020.03.02 · 2020/3/2 · 2020년 3월 2일  →  2020년 3월
 *   2020-03 · 2020.3 · 2020년 3월                        →  2020년 3월
 *   2020                                                 →  2020년, 월은 비움
 *   (빈 값)                                              →  연·월 모두 비움
 *   20-03 · abc                                          →  형식 오류
 *
 * **일(日)은 읽고 버린다.** 연-월 정밀도로 확정했으므로 저장할 자리가 없다. 그래도 값이 있으면
 * 범위는 본다 — 2020-03-45는 저장할 자리가 없는 값이 아니라 명부가 잘못 적힌 칸이다.
 *
 * **미입력이 기본값을 갖지 않는다.** 옛 가입일 매핑은 미입력이면 이관일로 채웠지만, 이 컬럼은
 * 모르는 것을 모른다고 두기 위해 생겼다.
 *
 * 연도를 4자리로 못 박은 것은 '20-03'을 2020년으로 짐작하지 않기 위해서다 — 1920년일 수도
 * 있고, 짐작이 빗나가도 저장된 뒤에는 아무도 알 수 없다.
 */
public record ClubJoinPeriod(Integer year, Integer month) {

    /** 미입력. 형식 오류와 다르다 — 이쪽은 올바른 입력이며 두 컬럼을 비운다 */
    private static final ClubJoinPeriod EMPTY = new ClubJoinPeriod(null, null);

    /* 구분자를 '-'로 맞춘 뒤의 모양. 연도만·연월·연월일 셋을 한 번에 받는다 */
    private static final Pattern PATTERN =
            Pattern.compile("^(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?$");

    private static final int MONTH_MIN = 1;
    private static final int MONTH_MAX = 12;
    private static final int DAY_MIN = 1;
    private static final int DAY_MAX = 31;

    /*
     * CSV 한 칸을 읽는다. **빈 Optional은 '형식 오류'이지 '값 없음'이 아니다** — 값이 없는 것은
     * 연·월이 모두 null인 ClubJoinPeriod로 돌아온다. 두 결과를 같은 모양으로 두면 부르는 쪽이
     * 잘못 적힌 칸을 조용히 비워 저장하게 된다.
     */
    public static Optional<ClubJoinPeriod> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(EMPTY);
        }

        Matcher matcher = PATTERN.matcher(normalize(raw));
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Integer month = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        if (month != null && (month < MONTH_MIN || month > MONTH_MAX)) {
            return Optional.empty();
        }
        if (matcher.group(3) != null) {
            int day = Integer.parseInt(matcher.group(3));
            if (day < DAY_MIN || day > DAY_MAX) {
                return Optional.empty();
            }
        }

        return Optional.of(new ClubJoinPeriod(Integer.valueOf(matcher.group(1)), month));
    }

    /*
     * 구분자를 '-' 하나로 맞춘다. 엑셀은 같은 명부 안에서도 '2020-03'·'2020.3'·'2020년 3월'을
     * 섞어 쓰므로 모양마다 정규식을 두면 그중 하나가 반드시 빠진다.
     *
     * '일'을 지우고 남는 꼬리 '-'도 함께 턴다 — '2020년 3월'은 '2020-3-'이 되어 그대로는 어느
     * 모양에도 맞지 않는다.
     */
    private static String normalize(String raw) {
        String normalized =
                raw.replace('.', '-')
                        .replace('/', '-')
                        .replace("년", "-")
                        .replace("월", "-")
                        .replace("일", "")
                        .replaceAll("\\s+", "")
                        .replaceAll("-{2,}", "-");
        return normalized.replaceAll("^-+|-+$", "");
    }
}
