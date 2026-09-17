package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * 질문이 지목한 조 (#457).
 *
 * ══ 왜 필요한가 ════════════════════════════════════════════════
 *
 * **밀집 임베딩은 「제3조」를 식별자로 잡지 못한다.** 2026-09-17 실측(문서 3건 67청크 ·
 * `gemini-embedding-2`)에서 「회칙 제3조는 무엇을 정하고 있어?」의 1위가 **부칙 제4조**였고
 * 정답은 8위라 `top-k=5` 밖이었다 — 조 번호가 아니라 「회칙·의결·개정」 같은 주변 어휘로
 * 매칭되기 때문이다. 조 번호만으로 묻는 질문 6건 중 2건이 그렇게 실패했다.
 *
 * 조 단위 청크가 짧은 것이 겹친다(평균 180자 · 최소 38자). 661자짜리 평문 청크와 같은 공간에서
 * 경쟁하면 밀린다.
 *
 * ══ 검색 신호이지 인용이 아니다 ═════════════════════════════════
 *
 * 여기서 뽑은 조 번호는 **무엇을 발췌에 넣을지**를 정할 뿐이고, 인용의 모양은 그대로
 * {@code RetrievedChunk.citation()}이 메타데이터에서 만든다. 평문에서 「제○조」를 정규식으로
 * 긁어 조항 인용을 흉내 내지 말라는 규칙(#398)과 갈리는 지점이 여기다 — 그 규칙이 막는 것은
 * **맞을 때도 틀릴 때도 있는 인용**이고, 이쪽은 틀려도 발췌 하나가 더 실릴 뿐이다.
 *
 * ══ 앞 턴이 세운 조를 잇는다 (#465) ════════════════════════════
 *
 * **「그 다음 조」에는 조 번호가 없다.** 그런데 밀집 검색으로는 그 말을 풀 수 없다 — 「다음」이
 * 무엇의 다음인지가 **이번 질문 밖**에 있고, 설령 앞 질문을 검색어에 이어 붙여도 나오는 것은
 * 제3조이지 제4조가 아니다(임베딩에 «+1»이 없다). 2026-09-17 실측에서 「회칙 제3조를 인용해줘」
 * 다음의 「그 다음 조도 알려줘」가 제10조·제29조·부칙 제3조를 물어 왔고, 제4조가 발췌에 없으니
 * 모델은 규칙대로 {@code [근거없음]}을 냈다 — **거절이 옳았고 검색이 틀렸다.**
 *
 * 그래서 앞 턴이 세운 조를 기준점으로 받아 **번호를 옮긴다**(`다음` +1 · `이전` −1 · `그 조` 0).
 * 기준점이 없으면(첫 턴이거나 앞 턴이 조항을 근거로 답하지 않았으면) 아무 일도 하지 않는다 —
 * 지어낸 기준점으로 엉뚱한 조를 핀으로 박는 것보다 낫다.
 *
 * ⚠️ **가지 번호는 옮기지 않고 버린다.** 제27조의2의 「다음 조」는 제28조라 그대로 맞지만,
 * 제28조의 「이전 조」는 제27조가 되어 제27조의2를 건너뛴다. 가지까지 세려면 코퍼스에 무엇이
 * 있는지를 알아야 하는데 이 record 는 질문만 본다 — **그 한 칸은 밀집 검색이 메운다**(기준 조가
 * 발췌에 함께 오므로 모델이 「그 사이에 제27조의2가 있다」를 읽을 수 있다).
 *
 * ══ 부칙을 가른다 ══════════════════════════════════════════════
 *
 * 회칙에는 본문 제3조와 **부칙 제3조**가 함께 있다. 제3조 질의의 1위가 부칙 제4조였던 것이
 * 그 혼동을 그대로 보여 주므로, 「부칙」이 조 번호 **바로 앞에 붙었을 때만** 부칙으로 읽는다.
 * 질문 어디에든 「부칙」이 있으면 부칙으로 보는 규칙은 쓰지 않는다 — 「부칙 말고 제3조」가
 * 거꾸로 걸린다.
 */
public record ArticleReference(int number, Integer branchNumber, boolean supplementary) {

    /**
     * 「(부칙) 제N조(의M)」. 사이 공백을 허용하는 것은 사람이 「제 3 조」로도 쓰기 때문이고, 조 번호를 세 자리로 끊는 것은 네 자리 숫자가 조 번호일 리 없기
     * 때문이다(연도가 걸린다 — 「2026년 제3조」에서 2026을 집지 않는다).
     */
    private static final Pattern ARTICLE =
            Pattern.compile("(부칙\\s*)?제\\s*(\\d{1,3})\\s*조(?:\\s*의\\s*(\\d{1,2}))?");

    /** 한 질문에서 핀으로 집을 조의 수 상한 — 이유는 {@link #references}에 있다 */
    private static final int MAX_REFERENCES = 2;

    /** 조를 가리키는 말의 꼬리 — 뒤에 조사·부호·공백·끝만 올 수 있다(위 {@link #NEXT} 주석) */
    private static final String BOUNDED_ARTICLE = "조(?:항|문)?(?=[\\s은는이가을를의에도와과만?!.,]|$)";

    /**
     * 「(그) 다음 조」 · 「그 다음은?」.
     *
     * <p><b>{@code 조} 뒤에 무엇이 오는지를 본다</b> — 조사·문장부호·공백·끝만 받는다. 이것이 없으면 「그 <b>조건</b>은?」·「그
     * <b>조직</b>은?」이 조 참조로 읽힌다. 뒤 갈래(「그 다음은?」)가 문장 끝을 요구하는 것도 같은 이유다 — 「다음과 같이」·「다음 각 호」가 걸리지 않는다.
     */
    private static final Pattern NEXT =
            Pattern.compile(
                    "(?:바로\\s*)?(?:그\\s*)?다음\\s*"
                            + BOUNDED_ARTICLE
                            + "|(?:그\\s*)?다음(?:은|도|것|거)?\\s*[?!.]*$");

    /** 「바로 앞 조」 · 「이전 조」 · 「직전 조항」 */
    private static final Pattern PREVIOUS =
            Pattern.compile(
                    "(?:바로\\s*)?(?:그\\s*)?(?:이전|직전|앞)\\s*(?:의\\s*)?"
                            + BOUNDED_ARTICLE
                            + "|(?:이전|직전)(?:은|것|거)?\\s*[?!.]*$");

    /** 「그 조」 · 「해당 조항」 — 기준점을 그대로 쓴다(옮기지 않는다) */
    private static final Pattern SAME = Pattern.compile("(?:그|이|해당|위|같은)\\s*" + BOUNDED_ARTICLE);

    /**
     * 질문에서 **처음 나오는** 조를 뽑는다. 없으면 {@code null}.
     *
     * <p>기준점을 세우는 자리({@code AssistantServiceImpl.anchorOf}의 폴백)와 아래 {@link #references}가 함께 쓴다.
     */
    static ArticleReference parse(String question) {
        List<ArticleReference> found = explicit(question, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 발췌에 반드시 넣을 조들 — <b>질문이 지목한 것, 없으면 앞 턴에서 옮긴 것</b> (#457 · #465).
     *
     * <p><b>지목이 둘까지 실린다</b>({@value #MAX_REFERENCES}). 예전에는 첫 조만 실었고 그 이유가 «발췌 예산이 좁다»였는데, 지목 발췌는
     * 유사도 검색의 {@code topK}와 <b>별도 예산</b>이라 실은 맥락을 밀어내지 않는다 — 미는 것이 아니라 프롬프트가 길어지는 것이고, 그 값이 곧
     * 지연이다(#448이 {@code topK}를 8에서 5로 좁힌 축). 그래서 무제한이 아니라 둘이다: 「제18조와 제19조는 어떻게 다른가」·「제14조 3항이 제7조를
     * 가리킨다는데」처럼 <b>실제로 둘을 나란히 놓고 묻는 질문</b>이 여기까지이고, 셋을 적는 질문(「제23조부터 제25조까지」)은 가운데가 밀집 검색으로
     * 따라온다(실측).
     *
     * <p><b>상대 표현은 지목이 하나도 없을 때만 푼다.</b> 「제7조의 다음 조」가 제8조로 옮겨지지 않는 이유는 {@link #parse(String,
     * ArticleReference)}에 있다.
     *
     * @param anchor 앞 턴이 근거로 삼은 조. {@code null}이면 상대 표현을 풀지 않는다
     */
    static List<ArticleReference> references(String question, ArticleReference anchor) {
        List<ArticleReference> found = explicit(question, MAX_REFERENCES);
        if (!found.isEmpty()) {
            return found;
        }
        ArticleReference resolved = parse(question, anchor);
        return resolved == null ? List.of() : List.of(resolved);
    }

    /*
     * 질문에 **적혀 있는** 조들 — 앞에서부터 `limit`개, 같은 조는 한 번만.
     */
    private static List<ArticleReference> explicit(String question, int limit) {
        if (question == null) {
            return List.of();
        }
        List<ArticleReference> found = new ArrayList<>();
        Matcher matcher = ARTICLE.matcher(question);
        while (matcher.find() && found.size() < limit) {
            int number = Integer.parseInt(matcher.group(2));
            if (number < 1) {
                // 「제0조」는 없다 — 조가 아닌 무언가를 집은 것이므로 부스트하지 않는다
                continue;
            }
            Integer branch = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
            ArticleReference reference =
                    new ArticleReference(number, branch, matcher.group(1) != null);
            if (!found.contains(reference)) {
                found.add(reference);
            }
        }
        return List.copyOf(found);
    }

    /**
     * 질문이 지목한 조 — <b>없으면 앞 턴이 세운 조에서 옮겨 본다</b> (#465).
     *
     * <p><b>명시가 언제나 이긴다.</b> 「제7조의 다음 조」처럼 둘이 함께 오면 제7조를 집는다 — 두 신호를 합쳐 제8조로 읽는 규칙은 두지 않았다. 그렇게 하면
     * 「제7조는 제8조와 어떻게 다른가」 같은 질문이 조용히 옮겨지고, 그 실패는 «엉뚱한 조가 근거로 실렸다»로만 보인다.
     *
     * @param anchor 앞 턴이 근거로 삼은 조. {@code null}이면 상대 표현을 풀지 않는다
     * @return 발췌에 반드시 넣어야 할 조. 지목도 상대 표현도 없으면 {@code null}
     */
    static ArticleReference parse(String question, ArticleReference anchor) {
        ArticleReference explicit = parse(question);
        if (explicit != null || anchor == null || question == null) {
            return explicit;
        }
        if (NEXT.matcher(question).find()) {
            return anchor.shift(1);
        }
        if (PREVIOUS.matcher(question).find()) {
            return anchor.shift(-1);
        }
        return SAME.matcher(question).find() ? anchor : null;
    }

    /*
     * 번호만 옮긴다 — **가지 번호는 버리고 부칙 여부는 지킨다**(클래스 주석의 ⚠️).
     *
     * 부칙을 지키는 것은 본문 제3조와 부칙 제3조가 함께 있어서다. 「부칙 제3조」를 보다가 「그 다음
     * 조」라고 하면 가리키는 것은 부칙 제4조이지 본문 제4조가 아니다.
     */
    private ArticleReference shift(int step) {
        int moved = number + step;
        return moved < 1 ? null : new ArticleReference(moved, null, supplementary);
    }
}
