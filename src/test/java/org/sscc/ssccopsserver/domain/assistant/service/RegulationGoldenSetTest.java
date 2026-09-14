package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationArticle;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChapter;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChunk;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationDocument;

/*
 * 골든셋 — **실제 문서 한 벌을 그대로 돌린다** (#397 · 기획안 §14.1).
 *
 * 리소스 `rag/regulation-2026-amendment.md`는 `private-workspace/rag/회칙개정_2026_개정안전문.md`의
 * 사본이다(내용은 바이트 그대로, 파일명만 ASCII다 — CI 러너의 로캘이 C면 한글 파일명이 클래스패스
 * 조회에서 깨진다). 이 개정안이 **첫 코퍼스이자 이 파서의 유일한 대상**이다
 * (SoongSilComputingClub/ssccops#323 — 현행 회칙은 옮기지 않기로 했다).
 *
 * **외부 호출이 없어 CI에서 매번 돈다.** 여기 있는 숫자는 «구현이 이렇게 나왔다»가 아니라
 * 문서를 세어 본 값이며, 파서를 만들면서 확인한 것이라 구현을 베낀 것이 아니다(#405에서 옮겨 온
 * 범위다). **문서를 바꾸면 이 숫자가 먼저 틀린다 — 그것이 이 클래스가 하는 일이다.**
 */
class RegulationGoldenSetTest {

    private static final String RESOURCE = "/rag/regulation-2026-amendment.md";
    private static final Pattern MARKER = Pattern.compile("⟨([^⟩]*)⟩");

    private final RegulationParser parser = new RegulationParser();
    private final RegulationChunker chunker = new RegulationChunker();

    private final String markdown = readResource();
    private final RegulationDocument document = parser.parse(markdown);
    private final List<RegulationChunk> chunks = chunker.chunk(document);

    @Test
    void countsChaptersAndArticles() {
        assertThat(document.chapters()).as("제1~9장 · 부칙").hasSize(10);
        assertThat(document.articles()).as("본칙 31 · 부칙 5").hasSize(36);

        Map<Boolean, List<RegulationArticle>> bySection =
                document.chapters().stream()
                        .collect(
                                Collectors.partitioningBy(
                                        RegulationChapter::supplementary,
                                        Collectors.flatMapping(
                                                chapter -> chapter.articles().stream(),
                                                Collectors.toList())));
        assertThat(bySection.get(false)).hasSize(31);
        assertThat(bySection.get(true)).hasSize(5);
    }

    /** `제27조의2`가 `(27, 2)`로 나오고 제27조와 **별개 청크**다 */
    @Test
    void branchArticleIsItsOwnChunk() {
        RegulationChunk parent = chunk("제27조");
        RegulationChunk branch = chunk("제27조의2");

        assertThat(parent.article().branchNumber()).isNull();
        assertThat(branch.article().number()).isEqualTo(27);
        assertThat(branch.article().branchNumber()).isEqualTo(2);
        assertThat(branch.sequence()).isNotEqualTo(parent.sequence());
        assertThat(branch.text()).contains("개인정보").doesNotContain("SSCC스터디세부규정");
    }

    /** 본칙 제1조(명칭)와 부칙 제1조(용어)가 **다른 청크**이며 인용 표기가 갈린다 */
    @Test
    void supplementaryArticleOneDoesNotCollideWithTheMainOne() {
        List<RegulationChunk> articleOnes =
                chunks.stream().filter(chunk -> chunk.article().label().equals("제1조")).toList();

        assertThat(articleOnes).hasSize(2);
        assertThat(articleOnes)
                .extracting(RegulationChunk::supplementary)
                .containsExactly(false, true);
        assertThat(articleOnes)
                .extracting(RegulationChunk::citation)
                .containsExactly("제1조 (명칭)", "부칙 제1조 (용어)");
        assertThat(articleOnes.get(1).heading())
                .as("부칙은 인용에 이미 들어 있어 장을 한 번 더 적지 않는다")
                .isEqualTo("부칙 제1조 (용어)");
    }

    /** 제7조는 조문 22줄에 해설 8문단이다 — 청크 본문에 그 «왜 바뀌나»가 없어야 한다 */
    @Test
    void commentaryNeverReachesTheChunks() {
        assertThat(chunks)
                .extracting(RegulationChunk::text)
                .noneMatch(text -> text.contains("왜 바뀌나"));
        assertThat(chunks)
                .extracting(RegulationChunk::text)
                .noneMatch(text -> text.contains("왜 신설하나"));
        assertThat(chunks)
                .as("해설은 인용문(`>`)으로 달려 있고 조문에는 그 기호가 없다")
                .extracting(RegulationChunk::text)
                .noneMatch(text -> text.contains("\n>"));

        String article7 =
                chunksOf("제7조").stream()
                        .map(RegulationChunk::text)
                        .collect(Collectors.joining("\n"));
        assertThat(article7).contains("정회원은 다음 각 호의 어느 하나에 해당하는 자를 말한다.");
        assertThat(article7).doesNotContain("등급과 학적은 애초에 다른 축이다");
    }

    /** 원문의 마커 수 — 이 표가 틀리면 파일이 바뀐 것이다 */
    @Test
    void countsRevisionMarkersInTheSourceFile() {
        Map<String, Long> markers =
                MARKER.matcher(markdown)
                        .results()
                        .map(result -> result.group(1))
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(markers)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("개정", 41L, "삭제", 6L, "신설", 5L, "이동", 4L, "전부개정", 1L));
    }

    /*
     * 트리에 남는 마커는 그중 **구조에 붙은 것**뿐이다.
     *
     * 원문 57개 중 여섯은 조문이 아니라 머리말의 «읽는 법»과 해설 블록에 있고, 둘은 호·절에 붙어
     * 있어 항으로 흡수된다 — 조 하나에 마커가 하나라는 계약(메타데이터가 한 값이다)이 그 이유다.
     */
    @Test
    void keepsStructuralMarkersAsMetadata() {
        assertThat(
                        markerCounts(
                                document.chapters().stream()
                                        .map(RegulationChapter::revisionMarker)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("개정", 1L, "이동", 3L));
        assertThat(
                        markerCounts(
                                document.articles().stream()
                                        .map(RegulationArticle::revisionMarker)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("개정", 21L, "신설", 1L, "전부개정", 1L));
        assertThat(
                        markerCounts(
                                document.articles().stream()
                                        .flatMap(article -> article.clauses().stream())
                                        .map(clause -> clause.revisionMarker())))
                .containsExactlyInAnyOrderEntriesOf(Map.of("개정", 16L, "삭제", 3L, "신설", 3L));

        assertThat(chunks)
                .as("마커는 벗겨서 메타데이터로 간다 — 임베딩 텍스트에는 한 글자도 남지 않는다")
                .extracting(RegulationChunk::text)
                .noneMatch(text -> MARKER.matcher(text).find());
    }

    /** 같은 조번호가 판본에 따라 다른 맥락을 갖는다 — 판본을 섞어 검색하면 안 되는 이유다 */
    @Test
    void chapterMoveShowsUpInTheEmbeddingHeading() {
        RegulationChunk article28 = chunk("제28조");

        assertThat(article28.chapter()).isEqualTo("제8장 동기회");
        assertThat(article28.heading()).isEqualTo("제8장 동기회 · 제28조 (설치)");
        assertThat(article28.text()).startsWith("제8장 동기회 · 제28조 (설치)\n");
    }

    /** 조 36개 → 청크 40개. 넷만 갈리고 갈린 묶음마다 조 헤더가 반복된다 */
    @Test
    void splitsOnlyTheLongArticlesAndRepeatsTheHeader() {
        assertThat(chunks).hasSize(40);

        Map<String, Long> perArticle =
                chunks.stream()
                        .collect(
                                Collectors.groupingBy(
                                        chunk ->
                                                (chunk.supplementary() ? "부칙 " : "")
                                                        + chunk.article().label(),
                                        Collectors.counting()));
        assertThat(perArticle.entrySet().stream().filter(entry -> entry.getValue() > 1))
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("제7조", "제8조", "제16조", "제17조");

        List<RegulationChunk> article7 = chunksOf("제7조");
        assertThat(article7)
                .extracting(RegulationChunk::heading)
                .allMatch(heading -> heading.equals("제2장 회원 · 제7조 (회원의 구분)"));
        assertThat(article7.get(0).sequence() + 1).isEqualTo(article7.get(1).sequence());
    }

    /** 항이 없는 조는 목표 길이를 넘어도 한 청크다 — 쪼갤 경계가 없다 */
    @Test
    void keepsClauselessArticleWholeEvenWhenLong() {
        List<RegulationChunk> revisionHistory =
                chunks.stream()
                        .filter(
                                chunk ->
                                        chunk.supplementary()
                                                && chunk.article().label().equals("제5조"))
                        .toList();

        assertThat(revisionHistory).hasSize(1);
        assertThat(revisionHistory.get(0).text().length())
                .isGreaterThan(RegulationChunker.TARGET_CHUNK_CHARS);
        assertThat(revisionHistory.get(0).text()).contains("| 17차 |");
    }

    private RegulationChunk chunk(String label) {
        List<RegulationChunk> found = chunksOf(label);
        assertThat(found).as("%s 청크", label).isNotEmpty();
        return found.get(0);
    }

    private List<RegulationChunk> chunksOf(String label) {
        return chunks.stream()
                .filter(chunk -> !chunk.supplementary() && chunk.article().label().equals(label))
                .toList();
    }

    private static Map<String, Long> markerCounts(Stream<String> markers) {
        return markers.filter(marker -> marker != null)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static String readResource() {
        try (InputStream stream = RegulationGoldenSetTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(stream).as("테스트 리소스 %s", RESOURCE).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
