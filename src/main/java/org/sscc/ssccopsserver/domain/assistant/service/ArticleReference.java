package org.sscc.ssccopsserver.domain.assistant.service;

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

    /**
     * 질문에서 **처음 나오는** 조를 뽑는다. 없으면 {@code null}.
     *
     * <p>둘 이상이 나와도 첫 번째만 쓰는 것은, 발췌 예산이 좁은데 「제7조와 제8조를 비교해줘」 같은 질문이 그 예산을 조 청크로만 채우면 정작 비교에 필요한 맥락이
     * 밀려나기 때문이다. 첫 조를 확보하고 나머지는 벡터 검색에 맡긴다.
     */
    static ArticleReference parse(String question) {
        if (question == null) {
            return null;
        }
        Matcher matcher = ARTICLE.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        int number = Integer.parseInt(matcher.group(2));
        if (number < 1) {
            // 「제0조」는 없다 — 조가 아닌 무언가를 집은 것이므로 부스트하지 않는다
            return null;
        }
        Integer branch = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        return new ArticleReference(number, branch, matcher.group(1) != null);
    }
}
