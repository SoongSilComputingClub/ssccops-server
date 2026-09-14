package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedDocument;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedPage;
import org.sscc.ssccopsserver.domain.assistant.dto.GenericChunk;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationDocument;

import lombok.RequiredArgsConstructor;

/*
 * 두 파싱 결과가 같은 `Document[]`로 나가는 자리 (#398 · 기획안 §5.4).
 *
 * ── 왜 한 클래스가 둘을 받는가 ──────────────────────────────
 *
 * 색인 워커(#400)는 판본의 유형만 알면 되고 **청킹 규칙이 둘이라는 사실을 몰라도 된다.** 워커가
 * 갈래마다 다른 타입을 들고 다르게 조립하면 새 유형이 하나 늘 때 워커도 함께 고쳐야 하고, 무엇보다
 * `toDocument(ragDocId, applyStatus)`를 부르는 자리가 갈래마다 생겨 **메타데이터를 빠뜨린 청크**가
 * 나올 여지가 생긴다 — 그 청크는 `ragDocId`가 없어 아무도 지울 수 없는 고아가 된다.
 *
 * **조 단위 규칙(#397)은 `RegulationChunker`에 그대로 둔다.** 두 규칙을 한 클래스에 합치지 않는
 * 것은 여기의 600·100이 검색 품질에 매여 자주 움직이는 값이고, 그 손이 「조 1개 = 청크 1개」쪽으로
 * 새는 순간 인용이 조 경계를 넘기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class DocumentChunker {

    /*
     * 한 청크가 담는 **새 내용**의 목표 길이 — overlap은 이 위에 얹히므로 실제 조각은 최대 700자다.
     *
     * 조 단위(450자)보다 큰 것은 여기 끊을 경계가 없기 때문이다. 조 단위는 항이 경계를 주므로
     * 작게 잡아도 발췌가 온전하지만, 평문은 600자보다 짧게 자르면 문맥 없는 조각이 늘어난다.
     */
    static final int TARGET_CHUNK_CHARS = 600;

    /*
     * 앞 청크의 끝에서 물고 오는 글자 수.
     *
     * **고정 길이가 문장을 끊기 때문에 둔다** — 경계에 걸린 문장은 어느 쪽 청크에도 온전히 담기지
     * 않아 그 부분의 검색이 무너진다. 조 단위에는 필요 없던 값이며(조 경계는 문장 경계다) 대가는
     * 청크 수가 약 1/6 늘어나는 것이다(기획안 §8.2에 반영돼 있다).
     */
    static final int OVERLAP_CHARS = 100;

    private final RegulationChunker regulationChunker;

    /** `STRUCTURED` — 조 단위. 규칙은 `RegulationChunker`가 갖고 여기서는 판본 값만 찍는다 */
    public List<Document> documents(
            RegulationDocument parsed, long ragDocumentId, RagApplyStatus applyStatus) {
        return regulationChunker.chunk(parsed).stream()
                .map(chunk -> chunk.toDocument(ragDocumentId, applyStatus))
                .toList();
    }

    /** `GENERIC` — 고정 길이. `documentName`은 표시명(`rag_doc.doc_nm`)이며 재색인 때 다시 찍힌다 */
    public List<Document> documents(
            ExtractedDocument extracted,
            String documentName,
            long ragDocumentId,
            RagApplyStatus applyStatus) {
        return chunk(extracted, documentName).stream()
                .map(chunk -> chunk.toDocument(ragDocumentId, applyStatus))
                .toList();
    }

    /**
     * 평문을 고정 길이 조각으로 자른다 — <b>문단 경계에서 끊고</b> 앞 조각의 끝 {@value #OVERLAP_CHARS}자를 물고 시작한다.
     *
     * <p>청크가 페이지 경계를 넘으면 <b>처음 담는 새 내용의 페이지</b>를 단다. 넘어온 overlap이 페이지를 정하게 두지 않는 것은, 그러면 앞 청크와 같은
     * 페이지를 가리키면서 내용은 대부분 다음 페이지인 청크가 생기기 때문이다. 두 페이지를 다 적지 않는 이유는 인용이 길어지는 데 반해 운영진이 확인하러 여는 것은 시작
     * 페이지 하나라서다.
     */
    public List<GenericChunk> chunk(ExtractedDocument extracted, String documentName) {
        List<GenericChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        // 물고 온 overlap의 길이. 「새 내용이 몇 자인가」를 이 값으로 빼서 세므로 overlap은 예산을 먹지 않는다
        int carried = 0;
        // 새 내용을 아직 담지 않았으면 null이고, 그 상태에서 첫 문단의 페이지를 받는다
        Integer page = null;

        for (Block block : blocks(extracted)) {
            int added = current.length() - carried;
            if (added > 0 && added + 1 + block.text().length() > TARGET_CHUNK_CHARS) {
                String body = current.toString();
                chunks.add(new GenericChunk(chunks.size(), page, documentName, body));
                current = new StringBuilder(overlap(body));
                carried = current.length();
                page = null;
            }
            if (page == null) {
                page = block.page();
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(block.text());
        }
        if (current.length() > carried) {
            chunks.add(new GenericChunk(chunks.size(), page, documentName, current.toString()));
        }
        return chunks;
    }

    /*
     * 문단을 페이지와 함께 늘어놓고, **목표보다 긴 문단은 먼저 쪼갠다.**
     *
     * 쪼개 두면 본 루프가 「이 문단을 더하면 넘치는가」만 보면 되고 문단 하나가 혼자 목표를 넘는
     * 경우를 따로 다루지 않는다. 조 단위 청커가 긴 항을 그대로 두는 것과 갈리는 지점인데, 거기서는
     * 청크가 조 하나를 온전히 담는 것이 인용의 뜻이지만 여기서는 길이가 곧 규칙이기 때문이다.
     */
    private static List<Block> blocks(ExtractedDocument extracted) {
        List<Block> blocks = new ArrayList<>();
        for (ExtractedPage page : extracted.pages()) {
            for (String paragraph : page.text().split("\n")) {
                String text = paragraph.strip();
                if (text.isEmpty()) {
                    continue;
                }
                for (String piece : split(text)) {
                    blocks.add(new Block(page.number(), piece));
                }
            }
        }
        return blocks;
    }

    /** 목표를 넘는 문단을 문장 경계에서, 없으면 공백에서, 그것도 없으면 글자 수로 자른다 */
    private static List<String> split(String paragraph) {
        List<String> pieces = new ArrayList<>();
        String rest = paragraph;
        while (rest.length() > TARGET_CHUNK_CHARS) {
            int cut = boundary(rest);
            pieces.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }

    /*
     * 자를 자리 — 목표 길이 앞쪽에서 문장 끝(`.` · `다.` 뒤의 공백 포함)을 찾고, 없으면 마지막 공백,
     * 그것도 없으면 목표 길이 그대로다. 한국어에는 공백 없이 이어지는 구간이 흔해 마지막 후보가 필요하다.
     */
    private static int boundary(String text) {
        String window = text.substring(0, TARGET_CHUNK_CHARS);
        for (String terminator : List.of(". ", "? ", "! ", ".")) {
            int at = window.lastIndexOf(terminator);
            if (at > TARGET_CHUNK_CHARS / 2) {
                return at + terminator.length();
            }
        }
        int space = window.lastIndexOf(' ');
        return space > TARGET_CHUNK_CHARS / 2 ? space + 1 : TARGET_CHUNK_CHARS;
    }

    /*
     * 다음 청크가 물고 갈 꼬리 — 끝에서 {@value #OVERLAP_CHARS}자를 떼고 **단어 중간이면 앞으로 밀어
     * 공백 다음에서 시작한다.** 문단 경계까지 물리면 마지막 문단이 100자보다 길 때 overlap이 0이 되어,
     * 정작 필요한 「긴 문단의 경계」에서만 사라진다.
     */
    private static String overlap(String body) {
        if (body.length() <= OVERLAP_CHARS) {
            return body;
        }
        String tail = body.substring(body.length() - OVERLAP_CHARS);
        int space = tail.indexOf(' ');
        int newline = tail.indexOf('\n');
        int start = newline >= 0 && (space < 0 || newline < space) ? newline : space;
        return start < 0 ? tail : tail.substring(start + 1);
    }

    /** 문단 하나와 그것이 있던 페이지. 페이지가 없는 형식(DOCX)에서는 `page`가 `null`이다 */
    private record Block(Integer page, String text) {}
}
