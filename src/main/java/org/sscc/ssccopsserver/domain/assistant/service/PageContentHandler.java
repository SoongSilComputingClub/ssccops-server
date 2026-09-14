package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedPage;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/*
 * Tika가 내는 XHTML에서 **페이지와 문단만** 건져 내는 SAX 핸들러 (#398).
 *
 * ── 왜 `BodyContentHandler`가 아닌가 ────────────────────────
 *
 * 그쪽은 평문 한 덩어리를 주는데 **페이지 경계가 거기서 사라진다** — 그리고 페이지가 이
 * 경로의 인용 전부다(`[p.12]`). Tika의 PDF 파서는 쪽마다 `<div class="page">`를 여는데
 * (실측 2026-09-14 · 6쪽짜리 회칙 PDF에서 정확히 6개), 그 신호를 읽는 유일한 방법이 XHTML을
 * 직접 받는 것이다. `ToXMLContentHandler`로 문자열을 받아 다시 파싱하는 안은 «XML을 두 번
 * 읽는» 길이라 택하지 않았다.
 *
 * ── 글자 수 상한을 두지 않는다 ──────────────────────────────
 *
 * `BodyContentHandler()`의 기본 상한은 10만 자이고 넘으면 조용히 잘린다 — 규정 문서에서
 * 뒷부분이 사라지면 그 조항만 영영 검색되지 않는데, 아무도 알아채지 못한다. 업로드 상한이
 * 10MB(#399)로 이미 앞에 있다.
 */
final class PageContentHandler extends DefaultHandler {

    /*
     * 여기서 끝나면 문단 하나가 닫힌다.
     *
     * 인라인 요소(`<a>` · `<b>`)를 넣지 않는 것은 링크 하나가 문장을 둘로 쪼개기 때문이고,
     * `<td>`·`<li>`를 넣는 것은 OOXML의 표 칸이 `<p>` 없이 글자를 바로 담기 때문이다 —
     * 빼면 표 한 장이 통째로 한 문단이 된다.
     */
    private static final Set<String> BLOCK_ELEMENTS =
            Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "td", "th", "div", "blockquote");

    private final List<ExtractedPage> pages = new ArrayList<>();
    private final List<String> blocks = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();

    /** 페이지 `<div>`를 한 번이라도 봤는가. 못 봤으면 그 형식에는 페이지가 없다(DOCX) */
    private boolean paginated;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        if (isPage(qName, attributes)) {
            closeBlock();
            closePage();
            paginated = true;
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        if (BLOCK_ELEMENTS.contains(qName)) {
            closeBlock();
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        buffer.append(ch, start, length);
    }

    @Override
    public void endDocument() {
        closeBlock();
        closePage();
    }

    /**
     * 읽어 낸 페이지들.
     *
     * <p>페이지가 있는 형식이면 1부터 번호를 매기고, 없으면 <b>번호가 {@code null}인 페이지 하나</b>로 돌려준다 — 「1쪽」으로 채우면 그것이 사실이
     * 아닌 인용이 된다.
     */
    List<ExtractedPage> pages() {
        if (!paginated) {
            String whole =
                    pages.stream().map(ExtractedPage::text).reduce("", PageContentHandler::join);
            return List.of(new ExtractedPage(null, whole));
        }
        List<ExtractedPage> numbered = new ArrayList<>();
        for (ExtractedPage page : pages) {
            numbered.add(new ExtractedPage(numbered.size() + 1, page.text()));
        }
        return numbered;
    }

    /*
     * 빈 쪽도 목록에 남긴다 — 지우면 그 뒤의 번호가 한 칸씩 밀려 인용이 통째로 어긋난다.
     * 첫 페이지 `<div>`를 열기 전에 모인 것이 없으면(대개 그렇다) 아무 일도 하지 않는다.
     */
    private void closePage() {
        if (pages.isEmpty() && blocks.isEmpty() && !paginated) {
            return;
        }
        pages.add(new ExtractedPage(null, String.join("\n", blocks)));
        blocks.clear();
    }

    /** 한 문단 안의 줄바꿈은 공백 하나로 접는다 — PDF는 줄 끝에서 단어를 끊으므로 줄이 문장이 아니다. 줄바꿈 없는 공백(`\u00a0`)도 함께 접는다 */
    private void closeBlock() {
        String text = buffer.toString().replaceAll("[\\s\\u00a0]+", " ").strip();
        buffer.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(text);
        }
    }

    private static boolean isPage(String qName, Attributes attributes) {
        return "div".equals(qName) && "page".equals(attributes.getValue("class"));
    }

    private static String join(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return left + right;
        }
        return left + "\n" + right;
    }
}
