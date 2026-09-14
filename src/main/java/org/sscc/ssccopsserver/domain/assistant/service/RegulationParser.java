package org.sscc.ssccopsserver.domain.assistant.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationArticle;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChapter;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationClause;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationDocument;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * `.md`로 옮겨 적은 회칙을 장·조 트리로 읽는다 (#397 · 기획안 §5.3).
 *
 * **서버가 파싱의 유일한 구현이다** — CSV 명부 이관(#84)이 세운 모양 그대로다.
 *
 * ── 왜 마크다운 라이브러리(commonmark)를 쓰지 않는가 ──────────
 *
 * AST는 «제목·목록·인용»을 주지만 우리가 알아야 하는 것은 **마크다운 문법이 아니라 회칙의
 * 구조**다. `제27조의2`가 제27조의 가지라는 것도, 부칙에서 조번호가 1로 리셋된다는 것도,
 * `>` 블록이 조문이 아니라 해설이라는 것도 AST에는 없다. 그것을 얹으려면 결국 이 파일을
 * AST 위에 한 번 더 쓰게 되므로, 줄 단위 규칙 다섯을 직접 본다.
 *
 * ── 줄 단위 규칙 다섯 ─────────────────────────────────────────
 *
 * | 장 | `## 제2장 회원` · `## 부칙` |
 * | 조 | `### 제7조 (회원의 구분)` · `### 제27조의2 (개인정보의 보호)` |
 * | 항 | 들여쓰지 않은 `- **1항** …` |
 * | 호·절 | 항 아래 들여쓴 `- **1호** …` — 항의 줄로 흡수한다 |
 * | 개정 마커 | 장·조·항 제목 뒤 `⟨개정⟩` `⟨신설⟩` `⟨삭제⟩` `⟨이동⟩` `⟨전부개정⟩` |
 *
 * **`>` 블록(해설)은 트리에 들어오지 않는다.** 개정안은 조마다 «왜 바뀌나»를 다는데 제7조는
 * 조문 22줄에 해설 8문단이다 — 넣으면 검색이 해설에 걸리고 인용 발췌가 조문이 아닌 것이 된다.
 * **원본이 R2에 남으므로 잃는 것이 아니라 미루는 것이다**(기획안 §16).
 *
 * ── 계약 위반은 전부 400이고 «몇째 줄이 왜»를 싣는다 ──────────
 *
 * 사유마다 에러 코드를 만들지 않는다 — `GeneralException.detail`이 그 자리다(#150).
 * 경고로 통과시키지 않는 것은, `.md`인데 조가 없는 파일은 회칙이 아니고 그것을 받아들이면
 * **인용을 만들 수 없는 청크가 코퍼스에 남기** 때문이다.
 */
@Component
public class RegulationParser {

    /*
     * 계약이 아는 마커 다섯. 모르는 마커(`⟨수정⟩`)를 통과시키지 않는 것은 그 값이 그대로
     * 메타데이터가 되어 화면의 «이번에 바뀐 조항» 배지 어휘가 파일마다 늘어나기 때문이다.
     *
     * 영문 enum으로 옮기지 않고 원문 한국어를 그대로 싣는다 — 화면에 나가는 것이 이 글자이고,
     * 옮겨 적으면 «개정/REVISED» 두 벌이 생겨 어느 쪽이 정본인지가 다시 문제가 된다.
     */
    private static final Set<String> KNOWN_MARKERS = Set.of("개정", "신설", "삭제", "이동", "전부개정");

    private static final Pattern HEADING = Pattern.compile("^(#+)\\s+(.*)$");
    private static final Pattern CHAPTER = Pattern.compile("^제\\d+장\\s+\\S.*$");
    private static final Pattern ARTICLE =
            Pattern.compile("^제(\\d+)조(?:의(\\d+))?(?:\\s*\\(([^)]*)\\))?$");
    private static final Pattern CLAUSE = Pattern.compile("^-\\s+\\*\\*(\\d+)항\\*\\*\\s*(.*)$");
    private static final Pattern NESTED_ITEM = Pattern.compile("^\\s+[-*+]\\s+(.*)$");
    private static final Pattern MARKER = Pattern.compile("⟨([^⟩]*)⟩");

    /** 부칙의 장 제목이자 인용 접두사. 조번호가 1로 리셋되는 유일한 자리다 */
    private static final String SUPPLEMENTARY_TITLE = "부칙";

    /** 오류 메시지에 원문 줄을 옮길 때의 상한 — 사유는 사람이 읽는 한 줄이지 파일 덤프가 아니다 */
    private static final int QUOTED_LINE_LIMIT = 40;

    /**
     * 올라온 파일의 바이트를 그대로 읽는다 — <b>계약의 대상이 문자열이 아니라 파일이기 때문</b>이다 (#400).
     *
     * <p>업로드(#399, 검증)와 색인 워커(#400, 적재)가 <b>같은 바이트에서 같은 트리를 얻어야</b> 하므로 디코딩을 부르는 쪽마다 두지 않는다. 한쪽만
     * BOM을 걷어내면 같은 파일이 업로드는 통과하고 색인은 실패한다.
     *
     * <p><b>BOM을 걷어내는 것은 첫 줄이 장 제목이기 때문이다</b> — 남겨 두면 {@code ﻿## 제1장 …}이 계약에 없는 제목 모양으로 읽혀, 운영진이
     * 눈으로는 아무 문제를 찾을 수 없는 400을 받는다(윈도우 메모장이 붙인다).
     */
    public RegulationDocument parse(byte[] content) {
        String text = new String(content == null ? new byte[0] : content, StandardCharsets.UTF_8);
        return parse(text.startsWith("﻿") ? text.substring(1) : text);
    }

    public RegulationDocument parse(String markdown) {
        Cursor cursor = new Cursor();
        String[] lines = markdown == null ? new String[0] : markdown.split("\\R", -1);

        for (int index = 0; index < lines.length; index++) {
            readLine(cursor, lines[index], index + 1);
        }
        cursor.closeChapter();

        if (cursor.chapters.stream().allMatch(chapter -> chapter.articles().isEmpty())) {
            // 조가 하나도 없는 `.md`는 회칙이 아니다. 장만 있는 파일도 여기 걸린다
            throw parseFailed("조(`### 제1조 (제목)`)가 하나도 없습니다. 회칙 본문이 맞는지 확인해 주세요.");
        }
        return new RegulationDocument(cursor.chapters);
    }

    private static void readLine(Cursor cursor, String raw, int line) {
        if (raw.isBlank() || raw.startsWith(">") || raw.strip().equals("---")) {
            // 빈 줄 · 해설(`>`) · 구분선은 조문이 아니다
            return;
        }

        Matcher heading = HEADING.matcher(raw);
        if (heading.matches()) {
            readHeading(cursor, heading.group(1).length(), heading.group(2), line);
            return;
        }

        Matcher clause = CLAUSE.matcher(raw);
        if (clause.matches()) {
            cursor.openClause(clause, line);
            return;
        }

        Matcher nested = NESTED_ITEM.matcher(raw);
        cursor.addContent(nested.matches() ? "  " + normalize(nested.group(1)) : normalize(raw));
    }

    private static void readHeading(Cursor cursor, int level, String rawText, int line) {
        Marked marked = stripMarkers(rawText, line);
        String text = marked.text();

        switch (level) {
            case 1 -> {
                // 문서 제목(`# SSCC 동아리 회칙`). 장·조 어느 쪽도 아니므로 트리에 자리가 없다
            }
            case 2 -> {
                if (!SUPPLEMENTARY_TITLE.equals(text) && !CHAPTER.matcher(text).matches()) {
                    throw parseFailed(line, text, "장은 `## 제N장 제목` 또는 `## 부칙`이어야 합니다.");
                }
                cursor.openChapter(text, SUPPLEMENTARY_TITLE.equals(text), marked.marker(), line);
            }
            case 3 -> openArticle(cursor, marked, line);
            default ->
                    throw parseFailed(
                            line, text, "계약에 없는 제목 수준입니다. `#`(문서)·`##`(장)·`###`(조)만 씁니다.");
        }
    }

    private static void openArticle(Cursor cursor, Marked marked, int line) {
        Matcher matcher = ARTICLE.matcher(marked.text());
        if (!matcher.matches()) {
            throw parseFailed(line, marked.text(), "조는 `### 제N조 (제목)` 또는 `### 제N조의M (제목)`이어야 합니다.");
        }
        if (cursor.chapterTitle == null) {
            // 장이 없으면 임베딩 텍스트에 붙일 맥락이 없다 — «제28조 (설치)»가 무엇의 설치인지 말하지 못한다
            throw parseFailed(line, marked.text(), "장(`## 제N장 …`) 없이 조가 시작합니다.");
        }

        int number = Integer.parseInt(matcher.group(1));
        Integer branchNumber = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        String label = "제" + number + "조" + (branchNumber == null ? "" : "의" + branchNumber);
        cursor.rejectDuplicate(label, line);

        cursor.openArticle(
                new OpenArticle(
                        number, branchNumber, label, matcher.group(3), marked.marker(), line));
    }

    /*
     * 마커를 떼어 내고 «본문»과 «마커»로 가른다.
     *
     * 마커가 둘 이상이면 어느 것이 그 조의 사건인지 고를 수 없어 거절한다 — 메타데이터가 하나뿐인
     * 값이기 때문이고, 실제로 필요해지면 그때 계약을 바꾼다.
     */
    private static Marked stripMarkers(String rawText, int line) {
        Matcher matcher = MARKER.matcher(rawText);
        List<String> markers = new ArrayList<>();
        while (matcher.find()) {
            markers.add(matcher.group(1));
        }
        for (String marker : markers) {
            if (!KNOWN_MARKERS.contains(marker)) {
                throw parseFailed(line, rawText, "«⟨" + marker + "⟩»는 계약에 없는 개정 마커입니다.");
            }
        }
        if (markers.size() > 1) {
            throw parseFailed(line, rawText, "개정 마커가 둘 이상입니다.");
        }

        String text = normalize(rawText).strip();
        return new Marked(text, markers.isEmpty() ? null : markers.get(0));
    }

    /*
     * 임베딩 텍스트에 실을 모양으로 다듬는다 — 마커와 강조(`**`)를 벗긴다.
     *
     * 강조는 개정안에서 «이번에 바뀐 문구»를 가리키는 표시라 조문의 일부가 아니고, 남겨 두면
     * 인용 발췌가 마크다운으로 보인다. 그 사실은 조 단위 마커가 이미 말하고 있다.
     */
    private static String normalize(String raw) {
        return MARKER.matcher(raw).replaceAll("").replace("**", "").stripTrailing();
    }

    private static GeneralException parseFailed(String reason) {
        return new GeneralException(AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED, reason);
    }

    private static GeneralException parseFailed(int line, String rawText, String reason) {
        String quoted = rawText.strip();
        if (quoted.length() > QUOTED_LINE_LIMIT) {
            quoted = quoted.substring(0, QUOTED_LINE_LIMIT) + "…";
        }
        return parseFailed(line + "번째 줄 «" + quoted + "» — " + reason);
    }

    /** 마커를 떼어 낸 본문과 그 마커 */
    private record Marked(String text, String marker) {}

    /** 조 하나를 모으는 동안의 가변 상태 */
    private static final class OpenArticle {
        private final int number;
        private final Integer branchNumber;
        private final String label;
        private final String title;
        private final String marker;
        private final int line;
        private final List<String> preamble = new ArrayList<>();
        private final List<RegulationClause> clauses = new ArrayList<>();

        private OpenArticle(
                int number,
                Integer branchNumber,
                String label,
                String title,
                String marker,
                int line) {
            this.number = number;
            this.branchNumber = branchNumber;
            this.label = label;
            this.title = title;
            this.marker = marker;
            this.line = line;
        }

        private RegulationArticle close() {
            return new RegulationArticle(
                    number, branchNumber, label, title, marker, line, preamble, clauses);
        }
    }

    /** 항 하나를 모으는 동안의 가변 상태 — 호·절과 번호 없는 단서 문장이 여기로 흡수된다 */
    private static final class OpenClause {
        private final int number;
        private final String marker;
        private final int line;
        private final List<String> lines = new ArrayList<>();

        private OpenClause(int number, String marker, int line, String firstLine) {
            this.number = number;
            this.marker = marker;
            this.line = line;
            this.lines.add(firstLine);
        }

        private RegulationClause close() {
            return new RegulationClause(number, marker, line, lines);
        }
    }

    /*
     * 한 번 훑는 동안의 위치 — 열려 있는 장·조·항과, 이미 본 조번호.
     *
     * ── 조번호 중복을 «장»이 아니라 «본칙/부칙»에서 본다 ─────────
     *
     * 기획안이 적은 «한 장 안의 중복»보다 넓다. 본칙 제7조가 제2장과 제3장에 한 번씩 있으면
     * 장 단위 검사는 통과하는데 인용 `[제7조 …]`는 두 청크를 가리켜 어느 쪽인지 말하지 못한다 —
     * 그 검사가 막으려던 고장이 그대로 남는 셈이다. **부칙만 따로 세는 것으로 «조번호 리셋»은
     * 그대로 허용된다**(본칙 제1조 명칭 · 부칙 제1조 용어).
     */
    private static final class Cursor {
        private final List<RegulationChapter> chapters = new ArrayList<>();
        private final Map<Boolean, Map<String, Integer>> seenArticles =
                new HashMap<>(
                        Map.of(Boolean.TRUE, new HashMap<>(), Boolean.FALSE, new HashMap<>()));

        private String chapterTitle;
        private boolean supplementary;
        private String chapterMarker;
        private int chapterLine;
        private List<RegulationArticle> chapterArticles = new ArrayList<>();
        private OpenArticle article;
        private OpenClause clause;

        private void openChapter(
                String title, boolean supplementaryChapter, String marker, int line) {
            closeChapter();
            this.chapterTitle = title;
            this.supplementary = supplementaryChapter;
            this.chapterMarker = marker;
            this.chapterLine = line;
            this.chapterArticles = new ArrayList<>();
        }

        private void closeChapter() {
            closeArticle();
            if (chapterTitle != null) {
                chapters.add(
                        new RegulationChapter(
                                chapterTitle,
                                supplementary,
                                chapterMarker,
                                chapterLine,
                                chapterArticles));
            }
            chapterTitle = null;
        }

        private void openArticle(OpenArticle next) {
            closeArticle();
            this.article = next;
        }

        private void closeArticle() {
            closeClause();
            if (article != null) {
                chapterArticles.add(article.close());
                article = null;
            }
        }

        private void openClause(Matcher matched, int line) {
            if (article == null) {
                // 조 밖의 `- **1항**`은 어느 조의 항인지 말할 수 없다
                throw parseFailed(line, matched.group(0), "조(`### 제N조 …`) 없이 항이 시작합니다.");
            }
            closeClause();

            int number = Integer.parseInt(matched.group(1));
            Marked marked = stripMarkers(matched.group(2), line);
            String head = number + "항";
            String text = marked.text().isEmpty() ? head : head + " " + marked.text();
            if (marked.text().isEmpty() && marked.marker() != null) {
                // `- **3항** ⟨삭제⟩` — 번호를 당기지 않고 남긴 자리다. 마커를 벗기면 «3항»만 남아
                // 무엇이 있었는지 읽을 수 없으므로 그 사실을 조문 글자로 적는다
                text = head + " (" + marked.marker() + ")";
            }
            this.clause = new OpenClause(number, marked.marker(), line, text);
        }

        private void closeClause() {
            if (clause != null) {
                article.clauses.add(clause.close());
                clause = null;
            }
        }

        private void addContent(String text) {
            if (article == null) {
                // 문서 머리말 — 조 밖의 평문은 트리에 자리가 없다
                return;
            }
            if (clause != null) {
                clause.lines.add(text);
            } else {
                article.preamble.add(text);
            }
        }

        private void rejectDuplicate(String label, int line) {
            Map<String, Integer> seen = seenArticles.get(supplementary);
            Integer first = seen.putIfAbsent(label, line);
            if (first != null) {
                String scope = supplementary ? "부칙" : "본칙";
                throw parseFailed(line, label, scope + "에서 이미 " + first + "번째 줄에 나온 조번호입니다.");
            }
        }
    }
}
