package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationArticle;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChapter;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationDocument;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 줄 단위 계약 다섯과 그 위반 (#397).
 *
 * **스프링 컨텍스트가 없다** — 파서는 순수 함수이고, 실제 문서로 세는 쪽은
 * `RegulationParserGoldenTest`가 따로 본다.
 */
class RegulationParserTest {

    private final RegulationParser parser = new RegulationParser();

    @Test
    void readsChapterArticleAndClauseTree() {
        RegulationDocument document =
                parser.parse(
                        """
                        # SSCC 동아리 회칙

                        머리말은 조문이 아니다.

                        ## 제1장 총칙

                        ### 제1조 (명칭)

                        본 회의 명칭은 숭실 컴퓨팅 클럽이라 한다.

                        ### 제5조 (활동)

                        다음과 같은 활동을 한다.

                        - **1항** 연구 활동
                        - **2항** 친목 활동
                        """);

        assertThat(document.chapters()).hasSize(1);
        RegulationChapter chapter = document.chapters().get(0);
        assertThat(chapter.title()).isEqualTo("제1장 총칙");
        assertThat(chapter.supplementary()).isFalse();

        assertThat(document.articles())
                .extracting(RegulationArticle::label)
                .containsExactly("제1조", "제5조");
        assertThat(document.articles().get(0).preamble())
                .as("항이 없는 조는 머리글이 본문의 전부다")
                .containsExactly("본 회의 명칭은 숭실 컴퓨팅 클럽이라 한다.");
        assertThat(document.articles().get(1).clauses())
                .extracting(clause -> clause.text())
                .containsExactly("1항 연구 활동", "2항 친목 활동");
    }

    /** `int` 하나로 두면 파싱이 죽거나 27과 한 조가 된다 — 뒤의 것은 답변이 섞여야만 드러난다 */
    @Test
    void keepsBranchArticleNumberSeparateFromItsParent() {
        RegulationDocument document =
                parser.parse(
                        """
                        ## 제7장 상벌과 보칙

                        ### 제27조 (세부 규정)

                        「SSCC스터디세부규정」을 따른다.

                        ### 제27조의2 (개인정보의 보호)

                        - **1항** 필요한 범위에서만 수집한다.
                        """);

        List<RegulationArticle> articles = document.articles();
        assertThat(articles).hasSize(2);
        assertThat(articles.get(0).number()).isEqualTo(27);
        assertThat(articles.get(0).branchNumber()).isNull();
        assertThat(articles.get(1).number()).isEqualTo(27);
        assertThat(articles.get(1).branchNumber()).isEqualTo(2);
        assertThat(articles.get(1).label()).as("표기는 원문 그대로 보존한다").isEqualTo("제27조의2");
    }

    /** 부칙에서 조번호가 1로 리셋된다 — 본칙 제1조와 부칙 제1조가 둘 다 있다 */
    @Test
    void supplementaryArticleNumbersRestartWithoutColliding() {
        RegulationDocument document =
                parser.parse(
                        """
                        ## 제1장 총칙

                        ### 제1조 (명칭)

                        본 회의 명칭은 숭실 컴퓨팅 클럽이라 한다.

                        ## 부칙

                        ### 제1조 (용어)

                        '본 회'란 숭실 컴퓨팅 클럽을 지칭한다.
                        """);

        assertThat(document.chapters())
                .extracting(RegulationChapter::supplementary)
                .containsExactly(false, true);
        assertThat(document.chapters().get(1).title()).isEqualTo("부칙");
        assertThat(document.articles()).hasSize(2);
    }

    /** 해설이 조문보다 길다 — 넣으면 검색이 해설에 걸리고 인용 발췌가 조문이 아닌 것이 된다 */
    @Test
    void dropsCommentaryBlocks() {
        RegulationDocument document =
                parser.parse(
                        """
                        ## 제2장 회원

                        ### 제7조 (회원의 구분)

                        - **1항** 등급과 학적으로 구분한다.

                        > **왜 바뀌나** — 지금은 휴학회원이 한 줄에 분류되어 있어 읽을 수 없다.
                        >
                        > 등급과 학적은 애초에 다른 축이다.
                        """);

        RegulationArticle article = document.articles().get(0);
        assertThat(article.preamble()).isEmpty();
        assertThat(article.clauses()).hasSize(1);
        assertThat(article.clauses().get(0).text()).doesNotContain("왜 바뀌나");
    }

    /** 마커는 벗기고 메타데이터로 남긴다 — «이번에 바뀐 조항»을 화면이 표시할 재료다 */
    @Test
    void stripsRevisionMarkersIntoMetadata() {
        RegulationDocument document =
                parser.parse(
                        """
                        ## 제5장 재정 ⟨이동⟩

                        ### 제22조 (회비) ⟨개정⟩

                        - **1항** ⟨개정⟩ **회비는 학기당 10,000원**으로 한다.
                        - **3항** ⟨삭제⟩
                        """);

        RegulationChapter chapter = document.chapters().get(0);
        assertThat(chapter.revisionMarker()).isEqualTo("이동");
        assertThat(chapter.title()).as("장 제목에서 마커를 뗀다").isEqualTo("제5장 재정");

        RegulationArticle article = chapter.articles().get(0);
        assertThat(article.revisionMarker()).isEqualTo("개정");
        assertThat(article.clauses().get(0).revisionMarker()).isEqualTo("개정");
        assertThat(article.clauses().get(0).text())
                .as("마커도 강조(**)도 조문 글자가 아니다")
                .isEqualTo("1항 회비는 학기당 10,000원으로 한다.");
    }

    /** 삭제된 항은 번호를 당기지 않고 남는다 — 마커만 벗기면 «3항»만 남아 읽을 수 없다 */
    @Test
    void keepsDeletedClauseReadable() {
        RegulationDocument document =
                parser.parse(
                        """
                        ## 제3장 임원

                        ### 제9조 (구성)

                        - **3항** ⟨삭제⟩
                        """);

        assertThat(document.articles().get(0).clauses().get(0).text()).isEqualTo("3항 (삭제)");
    }

    /** 호·절은 별도 노드가 아니라 항의 줄이다 — 나누면 «1호만 담긴 청크»가 생긴다 */
    @Test
    void absorbsSubItemsIntoTheirClause() {
        RegulationDocument document =
                parser.parse(
                        """
                        ## 제2장 회원

                        ### 제7조 (회원의 구분)

                        - **5항** 활동회원은 다음 각 호의 어느 하나에 해당하는 자를 말한다.
                            - **1호** 스터디장을 맡아 종료 승인을 받은 자
                            - 이 항의 스터디·트랙·프로젝트는 제15조에 따른다.
                        - **6항** 정회원은 다음과 같다.
                        """);

        RegulationArticle article = document.articles().get(0);
        assertThat(article.clauses()).hasSize(2);
        assertThat(article.clauses().get(0).lines())
                .containsExactly(
                        "5항 활동회원은 다음 각 호의 어느 하나에 해당하는 자를 말한다.",
                        "  1호 스터디장을 맡아 종료 승인을 받은 자",
                        "  이 항의 스터디·트랙·프로젝트는 제15조에 따른다.");
    }

    @Test
    void rejectsFileWithoutAnyArticle() {
        assertThatThrownBy(() -> parser.parse("## 제1장 총칙\n\n본 회는 동아리이다.\n"))
                .isInstanceOf(GeneralException.class)
                .satisfies(thrown -> assertParseFailed(thrown, "조(`### 제1조 (제목)`)가 하나도 없습니다"));
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(GeneralException.class)
                .satisfies(thrown -> assertParseFailed(thrown, "하나도 없습니다"));
    }

    /** 장이 없으면 임베딩 텍스트에 붙일 맥락이 없다 — 줄 번호와 함께 거절한다 */
    @Test
    void rejectsArticleBeforeAnyChapter() {
        assertThatThrownBy(() -> parser.parse("# 회칙\n\n### 제1조 (명칭)\n\n본 회의 명칭.\n"))
                .isInstanceOf(GeneralException.class)
                .satisfies(
                        thrown -> assertParseFailed(thrown, "3번째 줄 «제1조 (명칭)» — 장(`## 제N장 …`) 없이"));
    }

    /** 같은 조번호가 둘이면 인용이 어느 쪽인지 말하지 못한다 — «앞서 몇째 줄»까지 싣는다 */
    @Test
    void rejectsDuplicateArticleNumber() {
        String markdown =
                """
                ## 제1장 총칙

                ### 제1조 (명칭)

                본 회의 명칭.

                ## 제2장 회원

                ### 제1조 (다른 조)

                본문.
                """;

        assertThatThrownBy(() -> parser.parse(markdown))
                .isInstanceOf(GeneralException.class)
                .satisfies(thrown -> assertParseFailed(thrown, "9번째 줄 «제1조» — 본칙에서 이미 3번째 줄에 나온"));
    }

    @Test
    void rejectsUnknownChapterOrArticleShape() {
        assertThatThrownBy(() -> parser.parse("## 총칙\n\n### 제1조 (명칭)\n\n본문.\n"))
                .isInstanceOf(GeneralException.class)
                .satisfies(thrown -> assertParseFailed(thrown, "장은 `## 제N장 제목` 또는 `## 부칙`이어야"));

        assertThatThrownBy(() -> parser.parse("## 제1장 총칙\n\n### 제일조 (명칭)\n\n본문.\n"))
                .isInstanceOf(GeneralException.class)
                .satisfies(thrown -> assertParseFailed(thrown, "조는 `### 제N조 (제목)`"));
    }

    /** 모르는 마커를 통과시키면 화면의 배지 어휘가 파일마다 늘어난다 */
    @Test
    void rejectsUnknownRevisionMarker() {
        assertThatThrownBy(() -> parser.parse("## 제1장 총칙\n\n### 제1조 (명칭) ⟨수정⟩\n\n본문.\n"))
                .isInstanceOf(GeneralException.class)
                .satisfies(thrown -> assertParseFailed(thrown, "«⟨수정⟩»는 계약에 없는 개정 마커입니다"));
    }

    @Test
    void rejectsClauseOutsideAnyArticle() {
        assertThatThrownBy(
                        () -> parser.parse("## 제1장 총칙\n\n- **1항** 떠 있는 항\n\n### 제1조 (명칭)\n\n본문.\n"))
                .isInstanceOf(GeneralException.class)
                .satisfies(thrown -> assertParseFailed(thrown, "조(`### 제N조 …`) 없이 항이 시작합니다"));
    }

    private static void assertParseFailed(Throwable thrown, String expectedDetail) {
        GeneralException exception = (GeneralException) thrown;
        assertThat(exception.getErrorCode())
                .isEqualTo(AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED);
        assertThat(exception.getDetail()).contains(expectedDetail);
    }
}
