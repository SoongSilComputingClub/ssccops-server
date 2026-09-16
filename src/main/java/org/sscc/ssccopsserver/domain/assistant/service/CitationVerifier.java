package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.CitationType;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantCitationResponse;

import lombok.RequiredArgsConstructor;

/*
 * 인용 해석 — 모델이 쓴 `[3]`이 **우리가 넣어 준 세 번째 발췌인가** (#403 · #447 · 기획안 §6.3).
 *
 * ══ 왜 이것이 있어야 하는가 ═════════════════════════════════════
 *
 * **없는 조를 인용하는 순간 이 기능의 값이 사라진다.** 규정 답변은 운영진이 사람의 자격을
 * 판단하는 근거이고, 「제7조에 따르면」이 틀렸다는 것은 조문을 직접 열어 보기 전에는 드러나지
 * 않는다.
 *
 * ══ 문자열 대조가 아니라 범위 검사다 (#447) ═════════════════════
 *
 * 예전에는 모델이 `[제7조 6항]`처럼 **조 문자열을 직접 썼고** 여기서 그것을 발췌 목록과 대조해
 * 세 갈래(조 · 쪽 · 문서명)로 맞춰 봤다. 지금 모델이 쓰는 것은 **발췌 번호**뿐이라
 * (`AssistantPrompt`) 판정이 «1..N 안인가» 하나로 줄었고, 조·쪽·문서명은 **서버가 그 번호로
 * 찾아 붙인다.**
 *
 * | | 옛 계약 `[제7조 6항]` | 지금 `[3]` |
 * |---|---|---|
 * | 모델이 없는 조를 지어낼 수 있나 | 예 — 그래서 사후 대조가 필요했다 | **아니오** — 범위가 `1..N`뿐이다 |
 * | 판정 시점 | 답이 완성된 뒤 | **토큰이 닫히는 즉시** |
 * | 조·항 문자열을 누가 쓰나 | 모델 | **서버**({@code RetrievedChunk.marker()} · `articleCitation()`) |
 *
 * **안전성이 내려간 것이 아니라 올라갔다** — 사후에 잡으려던 「틀린 조 번호」가 애초에 일어나지
 * 않는다. 잃은 것은 항 표기 하나다(`AssistantCitationResponse.clause`가 언제나 `null`인 이유).
 *
 * ══ 그래서 흘려보내면서 판정할 수 있다 ══════════════════════════
 *
 * {@link Session}이 그 자리다. 판정에 «답 전체»가 필요 없으므로 조각이 올 때마다 돌 수 있고,
 * **아직 닫히지 않은 대괄호와 그 앞의 공백만 붙들었다가** 판정이 끝나면 내보낸다. 흘려보낸
 * 글자는 되돌릴 수 없으므로 **버릴 것은 나가기 전에 버린다.**
 *
 * 한 번에 답하는 경로({@link #verify})도 같은 세션을 쓴다 — 두 길이 **글자 하나까지 같은
 * 문자열**을 내야 «스트리밍이 아닌 경로의 회귀»라는 말이 성립한다.
 *
 * ══ 거절도 대괄호로 온다 — `[근거없음]` (#455) ══════════════════
 *
 * 모델이 근거를 찾지 못했다고 **말로** 하면 서버는 그것을 알 수 없다. 실제로 그 말에 출처가 함께
 * 붙어 나갔고(«… 근거를 찾지 못했습니다 [1][2][3][4][5]»), 인용 수만 보던 판정이 그것을 답변으로
 * 셌다 — 판본 배지와 인용 카드 다섯 장이 달린 「찾지 못했습니다」가 화면에 그려졌다. 모델이 규칙을
 * 어긴 것이 아니다: 「근거가 없다」도 주장이라 규칙 2가 시키는 대로 출처를 붙였을 뿐이다.
 *
 * 그래서 거절을 **구조로** 받는다. `[근거없음]`이 보이면 본문에서 지우고 `refused`를 세우며 **그 뒤
 * 조각은 내보내지 않는다** — 붙들고 있던 꼬리까지 함께 버리므로 표식이 첫 줄이면 `answer`가 빈 채로
 * 끝나고, 두 경로 모두 정해진 안내 문구로 떨어진다(`AssistantPrompt.NO_EVIDENCE`). 이미 흘려보낸
 * 글자가 있으면 그것은 회수하지 않는다(#447).
 *
 * **프롬프트가 그 표식을 쓰라고 시키는 것이 짝이다**(`AssistantPrompt` 규칙 3) — 한쪽만 고치면
 * 아무 일도 일어나지 않는다.
 *
 * ══ 통과하지 못한 토큰은 본문에서도 지운다 ══════════════════════
 *
 * 인용 목록에서만 빼면 답변 문장에 `[9]`가 그대로 남아 화면이 «근거가 있는 문장»으로 읽는다 —
 * 인용 카드가 없다는 것을 알아채는 사람은 없다. 지우는 것은 **인용이려고 한 토큰**뿐이다:
 *
 *   - 범위 밖의 번호 (`[9]`인데 발췌가 여덟 개)
 *   - 옛 계약의 조·쪽 표기 (`[제7조]` · `[부칙 제3조]` · `[p.12]`) — 이제 무엇도 통과하지 못한다
 *   - 넣어 준 발췌의 문서명 (`[학술국 운영 세칙]`)
 *
 * `[참고]` 같은 평범한 대괄호는 건드리지 않는다. **토큰을 지울 때 그 앞의 공백도 함께 지운다** —
 * 지운 자리에 «문장입니다 .»가 남지 않게 하는 유일한 방법이 그것이다(흘려보낸 뒤에는 앞의 공백을
 * 되돌릴 수 없으므로 애초에 붙들고 있는다).
 */
@Component
@RequiredArgsConstructor
public class CitationVerifier {

    /** 답변 속의 대괄호 후보. 줄바꿈을 넘지 않는 짧은 것만 본다 — 코드 블록·표를 건드리지 않기 위해서다 */
    private static final int MAX_TOKEN_LENGTH = 100;

    private static final Pattern MARKER =
            Pattern.compile("\\[([^\\[\\]\\r\\n]{1," + MAX_TOKEN_LENGTH + "})]");

    /** `[3]` — <b>인용이 될 수 있는 유일한 모양이다</b>. 세 자리로 묶는 것은 발췌가 topK개뿐이기 때문이다 */
    private static final Pattern REFERENCE = Pattern.compile("^\\d{1,3}$");

    /** 옛 계약의 조 표기. 지금은 어느 것도 통과하지 못하며 **본문에서 지워진다** */
    private static final Pattern ARTICLE =
            Pattern.compile("^(부칙\\s*)?제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?(?:\\s*제?\\s*\\d+\\s*항)?$");

    /** 옛 계약의 쪽 표기 — `p.12` · `p12` · `P. 12` */
    private static final Pattern PAGE = Pattern.compile("^[pP]\\s*\\.?\\s*\\d+$");

    /**
     * 모델이 «근거가 없다»고 말하는 <b>유일한 모양</b> (#455).
     *
     * <p>공백은 지우고 견준다 — `[근거 없음]`도 같은 표식이다. 모델이 띄어쓰기를 바꾼 것으로 판정이 갈리면 그 고장은 «가끔 답변으로 샌다»로 나타나 아무도
     * 재현하지 못한다.
     */
    private static final String REFUSAL = "근거없음";

    private final AssistantQueryPolicy policy;

    /**
     * 한 번에 받은 답을 해석한다 — <b>스트리밍과 같은 세션을 쓴다</b>.
     *
     * @param answer 모델이 낸 문장
     * @param chunks <b>모델에게 실제로 넣어 준 발췌</b>. 본문의 `[N]`이 이 목록의 N번째를 가리킨다
     */
    public Verified verify(String answer, List<RetrievedChunk> chunks) {
        Session session = open(chunks);
        session.accept(answer);
        session.finish();
        return session.verified();
    }

    /** 흘려보내면서 해석한다 (#447). 조각마다 {@link Session#accept}, 끝나면 {@link Session#finish} */
    public Session open(List<RetrievedChunk> chunks) {
        return new Session(chunks, policy.getSnippetLength());
    }

    /**
     * 해석 결과 — 손본 답변과 <b>코드가 번호로 찾아낸 인용만</b>. {@code dropped}는 로그용이다(사용자에게 말하지 않는다).
     *
     * <p><b>{@code refused}면 {@code citations}는 언제나 비어 있다</b> (#455) — 거절에 인용이 딸리는 상태를 만들지 않기 위해서다.
     * 그 상태가 성립했던 것이 이 필드가 생긴 이유다: 모델이 «근거를 찾지 못했습니다»라고 말하면서 그 문장에 `[1][2][3]`을 달면, 인용 수만 보던 판정이 그것을
     * 답변으로 셌다.
     */
    public record Verified(
            String answer, List<VerifiedCitation> citations, int dropped, boolean refused) {

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

    /**
     * 답 하나를 <b>조각 단위로</b> 해석하는 상태.
     *
     * <p>스레드 하나가 쓴다 — 생성 하나에 세션 하나이고, 그 조각들은 구독 하나에서 순서대로 온다.
     */
    public static final class Session {

        private final List<RetrievedChunk> chunks;
        private final int snippetLength;

        /** 번호 → 인용. 같은 발췌를 두 번 인용해도 카드는 하나다 */
        private final Map<Integer, VerifiedCitation> citations = new LinkedHashMap<>();

        /** 지금까지 내보낸 것 전부 — <b>조각들을 이어 붙인 것과 글자 하나까지 같다</b> */
        private final StringBuilder answer = new StringBuilder();

        /** 아직 판정하지 못한 꼬리 — 닫히지 않은 대괄호와 그 앞의 공백이다 */
        private final StringBuilder pending = new StringBuilder();

        private int dropped;

        /** 모델이 표식으로 «근거 없음»을 알렸나 — 그 뒤로는 아무것도 내보내지 않는다 (#455) */
        private boolean refused;

        /** 첫 글자를 내보냈나 — 앞쪽 공백을 버리기 위한 값이다(끝쪽은 붙들고 있다가 버린다) */
        private boolean started;

        private Session(List<RetrievedChunk> chunks, int snippetLength) {
            this.chunks = List.copyOf(chunks);
            this.snippetLength = snippetLength;
        }

        /**
         * 조각 하나를 받아 <b>내보내도 되는 만큼</b>을 돌려준다.
         *
         * <p>돌려주지 않은 것은 버려졌거나(인용이려다 실패한 토큰) 아직 판정 중이다(닫히지 않은 대괄호 · 뒤따라올 토큰이 함께 지울 수도 있는 끝 공백).
         */
        public String accept(String delta) {
            if (refused) {
                // 표식 뒤는 내보내지 않는다 — 거절의 답은 서버가 정한 문구다(#455)
                return "";
            }
            pending.append(delta);

            StringBuilder out = new StringBuilder();
            Matcher marker = MARKER.matcher(pending);
            int cursor = 0;
            while (marker.find(cursor)) {
                String token = marker.group(1).trim();
                if (isRefusal(token)) {
                    return refuse();
                }
                VerifiedCitation citation = resolve(token);
                if (citation != null) {
                    citations.putIfAbsent(Integer.valueOf(token), citation);
                    out.append(pending, cursor, marker.end());
                } else if (looksLikeCitation(token)) {
                    out.append(pending, cursor, Math.max(cursor, blankStartBefore(marker.start())));
                    dropped++;
                } else {
                    // 인용이려던 것이 아니다 — 문장을 건드리지 않는다
                    out.append(pending, cursor, marker.end());
                }
                cursor = marker.end();
            }

            int safe = safeEnd(cursor);
            out.append(pending, cursor, safe);
            pending.delete(0, safe);
            return emit(out.toString());
        }

        /** 스트림이 끝났다 — 붙들고 있던 꼬리를 비운다. 끝의 공백은 내보내지 않는다(= {@code strip}) */
        public String finish() {
            if (refused) {
                return "";
            }
            int end = pending.length();
            while (end > 0 && Character.isWhitespace(pending.charAt(end - 1))) {
                end--;
            }
            String tail = pending.substring(0, end);
            pending.setLength(0);
            return emit(tail);
        }

        /** 지금까지 내보낸 답과 통과한 인용. <b>거절이면 인용은 없다</b>(record 주석) */
        public Verified verified() {
            List<VerifiedCitation> found = refused ? List.of() : List.copyOf(citations.values());
            return new Verified(answer.toString(), found, dropped, refused);
        }

        /*
         * 표식을 만났다 — **아직 내보내지 않은 것은 함께 버린다** (#455).
         *
         * 붙들고 있던 꼬리(표식과 그 앞의 글자·공백)는 화면에 닿지 않았으므로 버릴 수 있고, 버려야
         * `answer`가 빈 채로 끝나 두 경로 모두 **정해진 안내 문구**로 떨어진다. 이미 나간 글자는
         * 되돌리지 않는다 — 그때는 `answer`가 비어 있지 않아 «근거 없음»으로 표시된다(#447).
         *
         * `dropped`를 올리지 않는 것은 그 수가 «출처를 달려다 실패한 토큰»이기 때문이다. 표식은
         * 실패한 인용이 아니라 모델이 제대로 지킨 규칙이다.
         */
        private String refuse() {
            refused = true;
            pending.setLength(0);
            return "";
        }

        /** 공백을 지우고 견준다 — `[근거 없음]`도 같은 표식이다 */
        private static boolean isRefusal(String token) {
            return REFUSAL.equals(token.replaceAll("\\s+", ""));
        }

        /*
         * 번호가 범위 안인가 — **판정의 전부다.** 조·쪽·문서명은 그 번호로 찾은 청크가 말한다.
         */
        private VerifiedCitation resolve(String token) {
            if (!REFERENCE.matcher(token).matches()) {
                return null;
            }
            int number = Integer.parseInt(token);
            if (number < 1 || number > chunks.size()) {
                return null;
            }
            RetrievedChunk chunk = chunks.get(number - 1);
            return new VerifiedCitation(response(number, chunk), chunk.source());
        }

        private AssistantCitationResponse response(int number, RetrievedChunk chunk) {
            if (chunk.citationType() == CitationType.ARTICLE) {
                return AssistantCitationResponse.article(
                        number,
                        chunk.marker(),
                        chunk.source().name(),
                        chunk.chapter(),
                        chunk.supplementary(),
                        chunk.articleCitation(),
                        snippet(chunk));
            }
            return AssistantCitationResponse.page(
                    number, chunk.marker(), chunk.source().name(), chunk.page(), snippet(chunk));
        }

        /*
         * 인용이려고 한 토큰인가 — **지울지 말지를 가르는 판정이다.**
         *
         * 번호 모양(범위 밖) · 옛 계약의 조·쪽 표기 · 넣어 준 발췌의 문서명. 셋 다 «출처를 달려고
         * 했는데 우리가 받아 줄 수 없는 것»이라 본문에 남기면 근거가 있는 문장으로 읽힌다.
         */
        private boolean looksLikeCitation(String token) {
            if (REFERENCE.matcher(token).matches()
                    || ARTICLE.matcher(token).matches()
                    || PAGE.matcher(token).matches()) {
                return true;
            }
            String normalized = token.replaceAll("\\s+", "");
            return chunks.stream()
                    .map(chunk -> chunk.source().name())
                    .anyMatch(
                            name -> name != null && name.replaceAll("\\s+", "").equals(normalized));
        }

        /*
         * 어디까지 내보내도 되는가.
         *
         * 아직 닫히지 않은 대괄호가 있으면 그 앞까지이고, 없으면 끝까지다. 어느 쪽이든 **끝의
         * 공백은 붙들어 둔다** — 뒤이어 버려질 토큰이 오면 그 공백도 함께 버려야 하는데, 이미
         * 내보낸 뒤에는 그럴 수 없다.
         */
        private int safeEnd(int cursor) {
            int end = pending.length();
            int open = pending.lastIndexOf("[");
            if (open >= cursor && stillOpen(open)) {
                end = open;
            }
            while (end > cursor && Character.isWhitespace(pending.charAt(end - 1))) {
                end--;
            }
            return end;
        }

        /*
         * 이 대괄호가 아직 인용 토큰이 될 수 있는가. 닫는 괄호가 이미 있거나(그런데 위에서 맞지
         * 않았다면 인용 모양이 아니다) 줄이 바뀌었거나 100자를 넘으면 더는 자라도 소용이 없다.
         */
        private boolean stillOpen(int open) {
            for (int at = open + 1; at < pending.length(); at++) {
                char character = pending.charAt(at);
                if (character == ']' || character == '\r' || character == '\n') {
                    return false;
                }
            }
            return pending.length() - open - 1 <= MAX_TOKEN_LENGTH;
        }

        /** {@code at} 바로 앞의 공백 뭉치가 시작되는 자리 — 토큰과 함께 버릴 구간의 왼쪽 끝이다 */
        private int blankStartBefore(int at) {
            int start = at;
            while (start > 0 && Character.isWhitespace(pending.charAt(start - 1))) {
                start--;
            }
            return start;
        }

        /*
         * 내보낸 것을 답에도 함께 쌓는다. 앞쪽 공백은 여기서 버린다 — 끝쪽은 `safeEnd`가 붙들고
         * 있다가 `finish`에서 버리므로, 둘이 합쳐 `strip()` 한 번과 같은 결과가 된다.
         */
        private String emit(String text) {
            String emitted = text;
            if (!started) {
                int at = 0;
                while (at < emitted.length() && Character.isWhitespace(emitted.charAt(at))) {
                    at++;
                }
                emitted = emitted.substring(at);
                if (emitted.isEmpty()) {
                    return "";
                }
                started = true;
            }
            answer.append(emitted);
            return emitted;
        }

        /*
         * 인용 카드의 원문 발췌. 헤더 한 줄(`제2장 회원 · 제7조 …`)은 카드가 이미 그리므로 빼고
         * 본문만 담는다. 조문을 통째로 옮기지 않는 것은 답변 길이 규칙(§6.2)과 같은 줄기다.
         */
        private String snippet(RetrievedChunk chunk) {
            String text = chunk.text();
            int newline = text.indexOf('\n');
            String body = (newline < 0 ? text : text.substring(newline + 1)).strip();
            return body.length() <= snippetLength
                    ? body
                    : body.substring(0, snippetLength).stripTrailing() + "…";
        }
    }
}
