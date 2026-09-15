package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.CitationType;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantCitationResponse;

import lombok.RequiredArgsConstructor;

/*
 * 인용 검증 — 모델이 단 인용이 **실제로 넣어 준 청크에 있는가** (#403 · 기획안 §6.3).
 *
 * ══ 왜 이것이 있어야 하는가 ═════════════════════════════════════
 *
 * **없는 조를 인용하는 순간 이 기능의 값이 사라진다.** 규정 답변은 운영진이 사람의 자격을
 * 판단하는 근거이고, 「제7조에 따르면」이 틀렸다는 것은 조문을 직접 열어 보기 전에는 드러나지
 * 않는다. 프롬프트의 «발췌에 없는 표기를 만들지 마세요»는 2차 방어선이며 모델은 그 지시를
 * 종종 어긴다 — **여기서 대조하는 코드가 마지막 층이다.**
 *
 * ══ 무엇을 하는가 ══════════════════════════════════════════════
 *
 * 답변에 박힌 `[...]` 토큰을 훑어 발췌 목록과 대조한다.
 *
 * | 토큰 | 언제 통과하나 |
 * |---|---|
 * | `제7조` · `제27조의2` · `부칙 제3조` | 같은 (부칙 여부 · 조번호 · 가지번호)의 조 단위 청크를 넣어 줬을 때 |
 * | `제7조 6항` | 위에 더해 **그 청크 본문에 `6항` 줄이 실제로 있을 때** — 없으면 조까지만 싣는다 |
 * | `p.12` | 그 쪽에서 시작하는 평문 청크를 넣어 줬을 때 |
 * | 문서명 | 페이지가 없는 형식(DOCX · #398)의 청크를 넣어 줬을 때 |
 *
 * **통과하지 못한 토큰은 본문에서도 지운다.** 인용 목록에서만 빼면 답변 문장에는 `[제99조]`가
 * 그대로 남아 화면이 «근거가 있는 문장»으로 읽힌다 — 인용 카드가 없다는 것을 알아채는 사람은
 * 없다. 지우는 것은 **조·페이지 모양인데 대조에 실패한 토큰**뿐이고, 인용처럼 보이지 않는
 * 대괄호(`[참고]`)는 건드리지 않는다.
 *
 * ══ 항 표기를 버려도 조는 남기는 이유 ═══════════════════════════
 *
 * 긴 조는 항 묶음으로 갈리므로(#397), 모델이 인용한 항이 **같은 조의 다른 청크**에 있을 수
 * 있다. 그때 조 인용은 맞고 항만 확인되지 않은 것이라 «조까지 싣고 항은 `null`»이 정확한
 * 표현이다 — 확인하지 못한 값을 싣지 않는다는 규칙과 「맞는 인용을 버리지 않는다」가 여기서
 * 만난다.
 *
 * ══ 같은 조가 두 문서에 있으면 ══════════════════════════════════
 *
 * 회칙과 세칙이 둘 다 `제7조`를 가질 수 있다. 그때는 **먼저 온 청크**를 고른다 — 검색 결과가
 * 유사도 순이므로 그것이 답변이 기댄 쪽이다. 모델에게 문서명을 함께 쓰게 하는 안은 표기를
 * 길게 만들어 **맞는 인용이 버려질 확률**을 올린다.
 */
@Component
@RequiredArgsConstructor
public class CitationVerifier {

    /** 답변 속의 인용 후보. 줄바꿈을 넘지 않는 짧은 대괄호만 본다 — 코드 블록·표를 건드리지 않기 위해서다 */
    private static final Pattern MARKER = Pattern.compile("\\[([^\\[\\]\\r\\n]{1,100})]");

    /** `부칙 제3조` · `제27조의2` · `제7조 6항` — 공백과 «제»의 유무를 너그럽게 받는다 */
    private static final Pattern ARTICLE =
            Pattern.compile(
                    "^(부칙\\s*)?제\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?(?:\\s*제?\\s*(\\d+)\\s*항)?$");

    /** `p.12` · `p12` · `P. 12` */
    private static final Pattern PAGE = Pattern.compile("^[pP]\\s*\\.?\\s*(\\d+)$");

    private final AssistantQueryPolicy policy;

    /**
     * 답변에서 검증을 통과한 인용만 남긴다.
     *
     * @param answer 모델이 낸 문장
     * @param chunks <b>모델에게 실제로 넣어 준 발췌</b>. 이 목록 밖의 것은 무엇도 인용이 될 수 없다
     */
    public Verified verify(String answer, List<RetrievedChunk> chunks) {
        Map<String, VerifiedCitation> citations = new LinkedHashMap<>();
        StringBuilder kept = new StringBuilder();
        Matcher marker = MARKER.matcher(answer);
        int cursor = 0;
        int dropped = 0;

        while (marker.find()) {
            String token = marker.group(1).trim();
            Resolved resolved = resolve(token, chunks);
            if (resolved != null) {
                citations.putIfAbsent(resolved.key(), resolved.citation());
                continue;
            }
            if (!looksLikeCitation(token)) {
                // 인용이 아니라 그냥 대괄호다. 문장을 건드리지 않는다
                continue;
            }
            kept.append(answer, cursor, marker.start());
            cursor = marker.end();
            dropped++;
        }
        kept.append(answer, cursor, answer.length());

        return new Verified(tidy(kept.toString()), List.copyOf(citations.values()), dropped);
    }

    /** 검증 결과 — 손본 답변과 <b>코드가 대조한 인용만</b>. {@code dropped}는 로그용이다(사용자에게 말하지 않는다) */
    public record Verified(String answer, List<VerifiedCitation> citations, int dropped) {

        public Verified {
            citations = List.copyOf(citations);
        }

        /** 응답에 실리는 모양 */
        public List<AssistantCitationResponse> responses() {
            return citations.stream().map(VerifiedCitation::response).toList();
        }
    }

    /**
     * 통과한 인용 하나와 <b>그것이 나온 판본</b>.
     *
     * <p>판본을 붙여 두는 것은 답변 위의 «2026-03-24 시행 회칙 기준» 배지가 그 값을 쓰기 때문이다(§13.1). 인용 카드에는 {@code
     * applyStatus}가 실리지 않으므로(문서명·판본까지다) 응답 record에서 되짚을 수 없다.
     */
    public record VerifiedCitation(AssistantCitationResponse response, SearchableDocument source) {}

    private Resolved resolve(String token, List<RetrievedChunk> chunks) {
        Matcher article = ARTICLE.matcher(token);
        if (article.matches()) {
            return resolveArticle(article, chunks);
        }
        Matcher page = PAGE.matcher(token);
        if (page.matches()) {
            return resolvePage(Integer.parseInt(page.group(1)), chunks);
        }
        return resolveDocumentName(token, chunks);
    }

    private Resolved resolveArticle(Matcher token, List<RetrievedChunk> chunks) {
        boolean supplementary = token.group(1) != null;
        int number = Integer.parseInt(token.group(2));
        Integer branch = token.group(3) == null ? null : Integer.valueOf(token.group(3));
        String clause = token.group(4);

        for (RetrievedChunk chunk : chunks) {
            if (chunk.citationType() != CitationType.ARTICLE
                    || chunk.supplementary() != supplementary
                    || !Objects.equals(chunk.articleNumber(), number)
                    || !Objects.equals(chunk.articleBranchNumber(), branch)) {
                continue;
            }
            String verifiedClause = containsClause(chunk, clause) ? clause + "항" : null;
            return new Resolved(
                    "%s#%d#%s#%s"
                            .formatted(
                                    CitationType.ARTICLE,
                                    chunk.source().ragDocId(),
                                    chunk.articleCitation(),
                                    String.valueOf(verifiedClause)),
                    new VerifiedCitation(
                            AssistantCitationResponse.article(
                                    chunk.source().name(),
                                    chunk.source().version(),
                                    chunk.chapter(),
                                    supplementary,
                                    chunk.articleCitation(),
                                    verifiedClause,
                                    snippet(chunk)),
                            chunk.source()));
        }
        return null;
    }

    private Resolved resolvePage(int page, List<RetrievedChunk> chunks) {
        for (RetrievedChunk chunk : chunks) {
            if (chunk.citationType() == CitationType.PAGE && Objects.equals(chunk.page(), page)) {
                return new Resolved(
                        "%s#%d#%d".formatted(CitationType.PAGE, chunk.source().ragDocId(), page),
                        new VerifiedCitation(
                                AssistantCitationResponse.page(
                                        chunk.source().name(),
                                        chunk.source().version(),
                                        page,
                                        snippet(chunk)),
                                chunk.source()));
            }
        }
        return null;
    }

    /*
     * 페이지가 없는 형식(DOCX)의 인용 표기는 문서명이다(#398). 표시명이 모델이 옮겨 적기에 긴
     * 값일 수 있어 공백만 무시하고 나머지는 그대로 본다 — 느슨하게 맞추면 「학술국 운영 세칙」과
     * 「학술국 운영 세칙 부록」이 같은 인용이 된다.
     */
    private Resolved resolveDocumentName(String token, List<RetrievedChunk> chunks) {
        String normalized = token.replaceAll("\\s+", "");
        for (RetrievedChunk chunk : chunks) {
            String name = chunk.source().name();
            if (chunk.citationType() == CitationType.PAGE
                    && chunk.page() == null
                    && name != null
                    && name.replaceAll("\\s+", "").equals(normalized)) {
                return new Resolved(
                        "%s#%d#-".formatted(CitationType.PAGE, chunk.source().ragDocId()),
                        new VerifiedCitation(
                                AssistantCitationResponse.page(
                                        name, chunk.source().version(), null, snippet(chunk)),
                                chunk.source()));
            }
        }
        return null;
    }

    /** 조·페이지 모양인가 — <b>지울지 말지를 가르는 판정이다</b>. 문서명 모양은 가릴 수 없어 건드리지 않는다 */
    private boolean looksLikeCitation(String token) {
        return ARTICLE.matcher(token).matches() || PAGE.matcher(token).matches();
    }

    /*
     * 항은 청크 본문의 줄머리에 `6항 …`으로 들어 있다(#397 파서의 표기). 문장 한가운데의 «6항»을
     * 세지 않으려고 줄머리로 고정한다 — 조문이 다른 항을 가리키는 일이 흔하다.
     */
    private boolean containsClause(RetrievedChunk chunk, String clause) {
        return clause != null
                && Pattern.compile("(?m)^\\s*" + Pattern.quote(clause) + "항\\b")
                        .matcher(chunk.text())
                        .find();
    }

    /*
     * 인용 카드의 원문 발췌. 헤더 한 줄(`제2장 회원 · 제7조 …`)은 카드가 이미 그리므로 빼고
     * 본문만 담는다. 조문을 통째로 옮기지 않는 것은 답변 길이 규칙(§6.2)과 같은 줄기다.
     */
    private String snippet(RetrievedChunk chunk) {
        String text = chunk.text();
        int newline = text.indexOf('\n');
        String body = (newline < 0 ? text : text.substring(newline + 1)).strip();
        int limit = policy.getSnippetLength();
        return body.length() <= limit ? body : body.substring(0, limit).stripTrailing() + "…";
    }

    /*
     * 토큰을 들어낸 자리에 남는 겹공백과 구두점 앞 공백을 정리한다. 줄바꿈은 건드리지 않는다 —
     * 모델이 문단을 나눠 답할 수 있고 그 모양은 화면이 그대로 그린다.
     */
    private String tidy(String answer) {
        return answer.replaceAll("[ \\t]{2,}", " ").replaceAll("[ \\t]+([.,)\\]»」])", "$1").strip();
    }

    /** 대조에 성공한 인용 하나 — {@code key}는 같은 근거를 두 번 싣지 않기 위한 값이다 */
    private record Resolved(String key, VerifiedCitation citation) {}
}
