package org.sscc.ssccopsserver.support;

import java.util.List;

/*
 * 규정 도우미 **실측 벤치마크의 시험지** (#465 · `./gradlew ragBench`).
 *
 * ══ 골든셋(`AssistantGoldenSet`)과 무엇이 다른가 ════════════════
 *
 * | | 코퍼스 | 임베딩 | 언제 도나 | 재는 것 |
 * |---|---|---|---|---|
 * | `AssistantGoldenSet` | 테스트 리소스 셋(개정안 md · 세칙 docx · 현행 pdf) | **스텁**(문자 n-gram) | CI 매번 | 회귀 |
 * | **이 표** | **로컬 DB에 실제로 적재된 것** | **실제 Gemini** | 사람이 부를 때만 | **눈금** |
 *
 * 둘을 합치지 않은 것은 **코퍼스가 다르면 정답지도 달라지기 때문**이다 — 골든셋 B는 현행 회칙
 * PDF를 일부러 함께 담아 「같은 내용, 다른 청킹」을 만들지만(유형별 점수를 재려고), 운영·로컬
 * 코퍼스에는 개정 작업 문서 세 벌만 있다. 같은 파일에 두면 «어느 코퍼스의 정답인가»가 흐려진다.
 *
 * ══ 무엇을 재려고 만들었나 ══════════════════════════════════════
 *
 * **이어 묻기가 무너지는 자리다.** 「회칙 제3조를 인용해줘」 뒤에 「그 다음 조도 알려줘」가
 * 거절로 떨어졌다(2026-09-17 화면 실측). 검색이 0건이어서가 아니라 **엉뚱한 다섯 건**이 올라와
 * 모델이 `[근거없음]`을 냈다 — 제4조가 발췌에 없었기 때문이다.
 *
 * 그래서 이 표의 무게중심은 B군(이어 묻기)이고, 나머지 군은 **그것을 고치면서 무엇을
 * 망가뜨렸는지**를 보기 위한 대조군이다. A군이 내려가면 #457의 조 지목 핀이 깨진 것이고,
 * D군이 내려가면 밀집 검색을 건드린 것이며, F군이 내려가면 거절이 헐거워진 것이다.
 *
 * ══ 기대값은 «인용 표기»다 ══════════════════════════════════════
 *
 * {@code RetrievedChunk.marker()}가 내는 문자열과 그대로 견준다 — 조항이면 `제4조`·`부칙 제3조`,
 * 쪽이 없는 평문이면 **문서 이름**이다(md 라 쪽이 없다). 기대 목록이 비어 있으면 **거절해야 하는
 * 질문**이다.
 *
 * ⚠️ **기대값을 여러 개 적은 칸은 「전부」가 아니라 「하나라도」다.** 「제18조와 제19조는 어떻게
 * 다른가」처럼 둘이 함께 와야 뜻이 서는 질문은 그 사실을 점수로 보기 위해 둘을 적되, 재현율은
 * 맞힌 개수로 센다({@code expected} 대비 몇 개가 발췌에 들었는가).
 */
public final class AssistantBenchmarkSet {

    /** 평문 문서 둘의 이름 — 적재된 `doc_nm` 그대로여야 한다(쪽이 없어 이름이 곧 인용 표기다) */
    public static final String REVIEW_LIST_NAME = "회칙개정_2026_검토목록";

    public static final String ASSEMBLY_GUIDE_NAME = "회칙개정_2026_총회안내";

    public static final List<Scenario> SCENARIOS =
            List.of(
                    /*
                     * A. 조 지목 — **지금 통과하는 것**(#457의 핀). 이 군은 기준선이라, 내려가면
                     * 고치려던 것이 아니라 이미 되던 것을 깨뜨린 것이다.
                     */
                    Scenario.single("A1", "article-pin", "회칙 제3조의 내용을 그대로 인용해줘", "제3조"),
                    Scenario.single("A2", "article-pin", "제21조는 무엇을 정하고 있어?", "제21조"),
                    Scenario.single("A3", "article-pin", "제27조의2에 대해 알려줘", "제27조의2"),
                    Scenario.single("A4", "article-pin", "부칙 제3조는 무슨 내용이야?", "부칙 제3조"),
                    Scenario.single("A5", "article-pin", "제18조 회계연도가 언제부터 언제까지야?", "제18조"),

                    /*
                     * B. 이어 묻기 — **고치려는 자리다.** 앞 턴이 세운 조를 이번 턴이 가리킨다.
                     *
                     * B4가 나머지 넷과 다른 것은 **앞 턴의 질문에 조 번호가 없다**는 점이다 —
                     * 기준점이 질문이 아니라 «그 턴이 실제로 무엇을 근거로 답했는가»에만 있다.
                     */
                    new Scenario(
                            "B1",
                            "follow-up",
                            List.of(
                                    Turn.of("회칙 제3조의 내용을 그대로 인용해줘", "제3조"),
                                    Turn.of("그 다음 조의 내용도 알려줘", "제4조"),
                                    Turn.of("그 다음은?", "제5조"))),
                    new Scenario(
                            "B2",
                            "follow-up",
                            List.of(
                                    Turn.of("제14조 탄핵 요건이 뭐야?", "제14조"),
                                    Turn.of("바로 앞 조는 무슨 내용이야?", "제13조"))),
                    new Scenario(
                            "B3",
                            "follow-up",
                            List.of(
                                    Turn.of("제24조 알려줘", "제24조"),
                                    Turn.of("그 조 2항의 정족수는 어떻게 돼?", "제24조"))),
                    new Scenario(
                            "B4",
                            "follow-up",
                            List.of(
                                    Turn.of("정회원으로 승격하려면 어떤 조건이 필요해?", "제7조"),
                                    Turn.of("그 다음 조는 무슨 내용이야?", "제8조"))),
                    new Scenario(
                            "B5",
                            "follow-up",
                            List.of(
                                    Turn.of("제22조 회비 감면 규정을 알려줘", "제22조"),
                                    Turn.of("이전 조는?", "제21조"))),

                    /*
                     * C. 한 질문에 조가 둘 이상 — 지금은 **첫 조만** 핀이 걸린다
                     * (`ArticleReference.parse`의 주석). 뒤의 조가 밀집 검색으로 따라오는지를 본다.
                     */
                    Scenario.single(
                            "C1",
                            "multi-article",
                            "제23조부터 제25조까지 개정 절차를 정리해줘",
                            "제23조",
                            "제24조",
                            "제25조"),
                    Scenario.single("C2", "multi-article", "제18조와 제19조는 어떻게 다른가요?", "제18조", "제19조"),

                    /*
                     * D. 조 번호 없는 내용 질문 — **밀집 검색만으로 서는 자리다.** 핀을 건드리는
                     * 변경이 이 군을 밀어내지 않는지 본다.
                     */
                    Scenario.single("D1", "content", "임원을 탄핵하려면 어떤 요건이 필요한가요?", "제14조"),
                    Scenario.single("D2", "content", "회칙을 개정하려면 어떤 절차를 거치나요?", "제24조", "제23조"),
                    Scenario.single("D3", "content", "휴학하면 정회원 자격을 잃나요?", "제7조", "제8조"),
                    Scenario.single("D4", "content", "회원의 개인정보는 어떻게 관리하나요?", "제27조의2"),
                    Scenario.single("D5", "content", "임원 회의는 얼마나 자주 열리나요?", "제13조"),
                    Scenario.single("D6", "content", "회비를 면제받는 사람은 누구인가요?", "제22조"),

                    /*
                     * E. 평문 문서 — 조 단위가 아닌 청크가 근거가 되어야 하는 질문이다.
                     * 인용 표기가 문서 이름인 것은 md 라 쪽이 없기 때문이다.
                     */
                    Scenario.single(
                            "E1", "generic", "이번 개정에서 오타로 고치기로 한 것은 무엇인가요?", REVIEW_LIST_NAME),
                    Scenario.single("E2", "generic", "이번 개정안의 한 장 요약을 알려줘", ASSEMBLY_GUIDE_NAME),

                    /*
                     * F. 거절해야 하는 것 — **기대 목록이 비어 있다.** 검색만 재는 모드에서는
                     * 판정하지 않는다(모델이 없으면 거절의 주체가 없다 · `AssistantQueryPolicy`).
                     */
                    Scenario.refusal("F1", "기숙사 통금 시간은 몇 시인가요?"),
                    Scenario.refusal("F2", "파이썬에서 리스트를 정렬하는 방법 알려줘"));

    private AssistantBenchmarkSet() {}

    /** 한 대화. 턴이 둘 이상이면 <b>같은 {@code conversationId}로 이어 묻는다</b> */
    public record Scenario(String id, String tag, List<Turn> turns) {

        static Scenario single(String id, String tag, String question, String... expected) {
            return new Scenario(id, tag, List.of(Turn.of(question, expected)));
        }

        static Scenario refusal(String id, String question) {
            return new Scenario(id, "refusal", List.of(new Turn(question, List.of())));
        }

        public boolean multiTurn() {
            return turns.size() > 1;
        }
    }

    /** 질문 하나와 그 답이 기대는 인용 표기. {@code expected}가 비면 거절이 정답이다 */
    public record Turn(String question, List<String> expected) {

        static Turn of(String question, String... expected) {
            return new Turn(question, List.of(expected));
        }

        public boolean mustRefuse() {
            return expected.isEmpty();
        }
    }
}
