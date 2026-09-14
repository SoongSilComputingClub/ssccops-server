package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChunk;

/*
 * 조 하나가 몇 개의 청크가 되고 무엇을 메타로 다는가 (#397 · 기획안 §5.3).
 *
 * 실제 문서로 세는 쪽은 `RegulationGoldenSetTest`가 본다 — 여기서는 «왜 그렇게 갈리는가»를
 * 최소한의 입력으로 고정한다.
 */
class RegulationChunkerTest {

    private final RegulationParser parser = new RegulationParser();
    private final RegulationChunker chunker = new RegulationChunker();

    /** 짧은 조는 갈리지 않는다 — 조 1개 = 청크 1개가 기본이다 */
    @Test
    void keepsShortArticleAsOneChunk() {
        List<RegulationChunk> chunks =
                chunk(
                        """
                        ## 제1장 총칙

                        ### 제1조 (명칭)

                        본 회의 명칭은 숭실 컴퓨팅 클럽이라 하며 SSCC를 약칭으로 한다.
                        """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sequence()).isZero();
        assertThat(chunks.get(0).text())
                .isEqualTo("제1장 총칙 · 제1조 (명칭)\n본 회의 명칭은 숭실 컴퓨팅 클럽이라 하며 SSCC를 약칭으로 한다.");
    }

    /** 긴 조는 항 경계에서만 갈리고, 갈린 묶음마다 조 헤더가 반복된다 */
    @Test
    void splitsLongArticleAtClauseBoundariesRepeatingTheHeader() {
        List<RegulationChunk> chunks = chunk(longArticle(8));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
                .extracting(RegulationChunk::heading)
                .allMatch(heading -> heading.equals("제2장 회원 · 제7조 (회원의 구분)"));
        assertThat(chunks).extracting(RegulationChunk::sequence).containsExactly(0, 1);
        assertThat(chunks)
                .as("묶음은 언제나 항의 첫 줄에서 시작한다 — 항 중간에서 끊기지 않는다")
                .allSatisfy(chunk -> assertThat(chunk.body()).matches("(?s)^\\d+항 .*"));
        assertThat(String.join("\n", chunks.stream().map(RegulationChunk::body).toList()))
                .as("어느 항도 사라지지 않는다")
                .contains("1항 ")
                .contains("8항 ");
    }

    /** 목표 길이는 상한이 아니다 — 항 하나가 혼자 더 길면 그대로 둔다 */
    @Test
    void neverCutsInsideASingleClause() {
        String clause = "가".repeat(RegulationChunker.TARGET_CHUNK_CHARS * 2);
        List<RegulationChunk> chunks =
                chunk("## 제2장 회원\n\n### 제7조 (회원의 구분)\n\n- **1항** " + clause + "\n");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).body()).isEqualTo("1항 " + clause);
    }

    /** 순번은 판본 전체를 가로질러 하나다 — 장이 바뀌어도 0으로 돌아가지 않는다 */
    @Test
    void numbersChunksAcrossTheWholeDocument() {
        List<RegulationChunk> chunks =
                chunk(
                        """
                        ## 제1장 총칙

                        ### 제1조 (명칭)

                        본문.

                        ## 부칙

                        ### 제1조 (용어)

                        본문.
                        """);

        assertThat(chunks).extracting(RegulationChunk::sequence).containsExactly(0, 1);
        assertThat(chunks)
                .extracting(RegulationChunk::citation)
                .containsExactly("제1조 (명칭)", "부칙 제1조 (용어)");
    }

    /** 판본을 아는 쪽이 `ragDocId`·`applyStatus`를 얹는다 — 그 한 메서드가 key 이름을 아는 자리다 */
    @Test
    void carriesStructureIntoDocumentMetadata() {
        RegulationChunk chunk =
                chunk(
                                """
                                ## 제7장 상벌과 보칙 ⟨이동⟩

                                ### 제27조의2 (개인정보의 보호) ⟨신설⟩

                                - **1항** 필요한 범위에서만 수집한다.
                                """)
                        .get(0);

        Document document = chunk.toDocument(42L, RagApplyStatus.DRAFT);

        assertThat(document.getText()).isEqualTo(chunk.text());
        assertThat(document.getMetadata())
                .containsEntry(RagChunkStore.RAG_DOCUMENT_ID_KEY, 42L)
                .containsEntry(RagChunkMetadata.DOC_TYPE, "STRUCTURED")
                .containsEntry(RagChunkMetadata.APPLY_STATUS, "DRAFT")
                .containsEntry(RagChunkMetadata.SEQUENCE, 0)
                .containsEntry(RagChunkMetadata.CHAPTER, "제7장 상벌과 보칙")
                .containsEntry(RagChunkMetadata.SUPPLEMENTARY, false)
                .containsEntry(RagChunkMetadata.ARTICLE_NUMBER, 27)
                .containsEntry(RagChunkMetadata.ARTICLE_BRANCH_NUMBER, 2)
                .containsEntry(RagChunkMetadata.ARTICLE_LABEL, "제27조의2")
                .containsEntry(RagChunkMetadata.ARTICLE_TITLE, "개인정보의 보호")
                .containsEntry(RagChunkMetadata.CITATION, "제27조의2 (개인정보의 보호)")
                .containsEntry(RagChunkMetadata.REVISION_MARKER, "신설");
    }

    /** 값이 없는 key는 아예 넣지 않는다 — jsonb에 든 `null`은 필터에서 «있음»으로 세어진다 */
    @Test
    void omitsAbsentMetadataKeys() {
        Document document =
                chunk("## 부칙\n\n### 제4조 (시행일)\n\n이 회칙은 1984년 3월 1일부터 시행한다.\n")
                        .get(0)
                        .toDocument(1L, RagApplyStatus.EFFECTIVE);

        assertThat(document.getMetadata())
                .doesNotContainKey(RagChunkMetadata.ARTICLE_BRANCH_NUMBER)
                .doesNotContainKey(RagChunkMetadata.REVISION_MARKER)
                .containsEntry(RagChunkMetadata.SUPPLEMENTARY, true)
                .containsEntry(RagChunkMetadata.CITATION, "부칙 제4조 (시행일)");
    }

    private List<RegulationChunk> chunk(String markdown) {
        return chunker.chunk(parser.parse(markdown));
    }

    private static String longArticle(int clauses) {
        StringBuilder markdown = new StringBuilder("## 제2장 회원\n\n### 제7조 (회원의 구분)\n\n");
        for (int number = 1; number <= clauses; number++) {
            markdown.append("- **")
                    .append(number)
                    .append("항** ")
                    .append("가".repeat(60))
                    .append('\n');
        }
        return markdown.toString();
    }
}
