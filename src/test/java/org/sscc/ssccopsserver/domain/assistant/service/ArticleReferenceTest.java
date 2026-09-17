package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/*
 * 조 번호 파싱 (#457).
 *
 * **여기서 틀리면 엉뚱한 조가 발췌에 실린다.** 그 발췌는 점수 판정을 지나지 않으므로
 * (`AssistantServiceImpl.pinnedArticle`이 임계값을 걸지 않는다) 검색이 걸러 주지 않는다.
 */
class ArticleReferenceTest {

    @Nested
    @DisplayName("조를 지목한 질문")
    class Pointed {

        @Test
        @DisplayName("본문 조를 뽑는다")
        void picksAMainArticle() {
            ArticleReference parsed = ArticleReference.parse("회칙 제3조는 무엇을 정하고 있어?");

            assertThat(parsed).isEqualTo(new ArticleReference(3, null, false));
        }

        @Test
        @DisplayName("「부칙」이 조 번호 바로 앞에 붙으면 부칙으로 읽는다 — 본문 제3조와 부칙 제3조가 함께 있다")
        void readsSupplementaryWhenItPrefixesTheNumber() {
            ArticleReference parsed = ArticleReference.parse("부칙 제3조가 정하는 의결 순서는?");

            assertThat(parsed).isEqualTo(new ArticleReference(3, null, true));
        }

        @Test
        @DisplayName("「부칙」이 다른 자리에 있으면 본문으로 읽는다 — 「부칙 말고 제3조」가 거꾸로 걸리지 않게")
        void ignoresSupplementaryWordElsewhere() {
            ArticleReference parsed = ArticleReference.parse("부칙 말고 제3조를 알려줘");

            assertThat(parsed).isEqualTo(new ArticleReference(3, null, false));
        }

        @Test
        @DisplayName("가지 번호를 뽑는다")
        void picksABranchNumber() {
            ArticleReference parsed = ArticleReference.parse("제27조의2 내용 알려줘");

            assertThat(parsed).isEqualTo(new ArticleReference(27, 2, false));
        }

        @Test
        @DisplayName("「제3조의 의결」은 가지 번호가 아니다 — 「의」 뒤에 숫자가 와야 한다")
        void doesNotReadTheParticleAsABranch() {
            ArticleReference parsed = ArticleReference.parse("제3조의 내용을 그대로 인용해줘");

            assertThat(parsed).isEqualTo(new ArticleReference(3, null, false));
        }

        @Test
        @DisplayName("사이 공백을 허용한다")
        void allowsSpacesInsideTheNumber() {
            ArticleReference parsed = ArticleReference.parse("제 21 조 에 대해 알려줘");

            assertThat(parsed).isEqualTo(new ArticleReference(21, null, false));
        }

        @Test
        @DisplayName("둘 이상이면 첫 번째만 — 기준점을 세우는 자리가 쓰는 값이다")
        void picksOnlyTheFirstArticle() {
            ArticleReference parsed = ArticleReference.parse("제7조와 제8조를 비교해줘");

            assertThat(parsed).isEqualTo(new ArticleReference(7, null, false));
        }

        @Test
        @DisplayName("장 번호를 조 번호로 읽지 않는다 — 「제5장 제18조」는 18조다")
        void doesNotConfuseChapterWithArticle() {
            ArticleReference parsed = ArticleReference.parse("제5장 제18조를 알려줘");

            assertThat(parsed).isEqualTo(new ArticleReference(18, null, false));
        }

        @Test
        @DisplayName("코퍼스에 없는 조도 파싱은 된다 — 핀이 0건이 되어 벡터 검색만 남는 것이 맞다")
        void parsesAnArticleThatTheCorpusDoesNotHave() {
            ArticleReference parsed = ArticleReference.parse("제99조는 무슨 내용이야?");

            assertThat(parsed).isEqualTo(new ArticleReference(99, null, false));
        }
    }

    @Nested
    @DisplayName("조를 지목하지 않은 질문")
    class NotPointed {

        @Test
        @DisplayName("조 번호가 없으면 부스트하지 않는다")
        void returnsNullWithoutAnArticle() {
            assertThat(ArticleReference.parse("표결 방식으로 무엇을 제안했어?")).isNull();
        }

        @Test
        @DisplayName("연도는 조 번호가 아니다 — 「제」가 앞에 붙어야 한다")
        void doesNotPickAYear() {
            assertThat(ArticleReference.parse("2026년 개정안이 뭐야?")).isNull();
        }

        @Test
        @DisplayName("null 질문에도 깨지지 않는다")
        void toleratesNullQuestion() {
            assertThat(ArticleReference.parse(null)).isNull();
        }

        @Test
        @DisplayName("「조」로 끝나도 「제」가 없으면 조가 아니다 — 「3조원」")
        void doesNotPickANumberWithoutThePrefix() {
            assertThat(ArticleReference.parse("예산 3조원은 어디에 쓰나요?")).isNull();
        }

        @Test
        @DisplayName("「제N」으로 시작해도 「조」가 아니면 집지 않는다 — 「제1지망」·「제3장」")
        void doesNotPickOtherOrdinals() {
            assertThat(ArticleReference.parse("제1지망 학과를 어디로 쓰나요?")).isNull();
            assertThat(ArticleReference.parse("회칙 제3장은 무엇인가요?")).isNull();
        }
    }

    /*
     * 앞 턴이 세운 조를 가리키는 말 (#465).
     *
     * **여기가 헐거우면 조 참조가 아닌 질문에 엉뚱한 조가 핀으로 박힌다** — 그 발췌는 임계값
     * 판정을 지나지 않으므로(`AssistantServiceImpl.pinnedArticle`) 검색이 걸러 주지 않는다.
     * 그래서 «푸는 것»만큼 «풀지 않는 것»을 많이 본다.
     */
    @Nested
    @DisplayName("이어 묻기 — 앞 턴의 조를 가리키는 말")
    class FollowUp {

        private static final ArticleReference ANCHOR = new ArticleReference(3, null, false);

        @Test
        @DisplayName("「그 다음 조」는 +1")
        void movesToTheNextArticle() {
            assertThat(ArticleReference.parse("그 다음 조의 내용도 알려줘", ANCHOR))
                    .isEqualTo(new ArticleReference(4, null, false));
        }

        @Test
        @DisplayName("「그 다음은?」처럼 「조」가 없어도 푼다 — 화면에서 실제로 이렇게 친다")
        void movesOnWithoutTheWordArticle() {
            assertThat(ArticleReference.parse("그 다음은?", ANCHOR))
                    .isEqualTo(new ArticleReference(4, null, false));
        }

        @Test
        @DisplayName("「바로 앞 조」·「이전 조」는 −1")
        void movesToThePreviousArticle() {
            assertThat(ArticleReference.parse("바로 앞 조는 무슨 내용이야?", ANCHOR))
                    .isEqualTo(new ArticleReference(2, null, false));
            assertThat(ArticleReference.parse("이전 조는?", ANCHOR))
                    .isEqualTo(new ArticleReference(2, null, false));
        }

        @Test
        @DisplayName("「그 조」는 기준점 그대로 — 「그 조 2항」을 물으면 같은 조가 다시 실려야 한다")
        void keepsTheAnchorForTheSameArticle() {
            assertThat(ArticleReference.parse("그 조 2항의 정족수는 어떻게 돼?", ANCHOR)).isEqualTo(ANCHOR);
        }

        @Test
        @DisplayName("부칙은 부칙으로 옮긴다 — 본문 제3조와 부칙 제3조가 함께 있다")
        void staysInsideTheSupplementaryProvisions() {
            assertThat(ArticleReference.parse("그 다음 조는?", new ArticleReference(3, null, true)))
                    .isEqualTo(new ArticleReference(4, null, true));
        }

        @Test
        @DisplayName("가지 번호는 버린다 — 제27조의2의 다음은 제28조다")
        void dropsTheBranchNumberWhenMoving() {
            assertThat(ArticleReference.parse("그 다음 조는?", new ArticleReference(27, 2, false)))
                    .isEqualTo(new ArticleReference(28, null, false));
        }

        @Test
        @DisplayName("제1조의 이전 조는 없다")
        void refusesToMoveBeforeTheFirstArticle() {
            assertThat(ArticleReference.parse("이전 조는?", new ArticleReference(1, null, false)))
                    .isNull();
        }

        @Test
        @DisplayName("기준점이 없으면 풀지 않는다 — 첫 턴에 「그 다음 조」를 물은 경우")
        void needsAnAnchor() {
            assertThat(ArticleReference.parse("그 다음 조는?", null)).isNull();
        }

        @Test
        @DisplayName("명시가 언제나 이긴다 — 「제7조의 다음 조」를 제8조로 옮기지 않는다")
        void prefersTheExplicitArticle() {
            assertThat(ArticleReference.parse("제7조의 다음 조가 뭐야?", ANCHOR))
                    .isEqualTo(new ArticleReference(7, null, false));
        }

        @Test
        @DisplayName("「조」로 시작하는 다른 낱말을 조 참조로 읽지 않는다")
        void doesNotReadOtherWordsStartingWithTheSameSyllable() {
            assertThat(ArticleReference.parse("그 조건은 어떻게 되나요?", ANCHOR)).isNull();
            assertThat(ArticleReference.parse("그 조직은 어떻게 구성되나요?", ANCHOR)).isNull();
            assertThat(ArticleReference.parse("해당 조치는 누가 하나요?", ANCHOR)).isNull();
        }

        @Test
        @DisplayName("「다음과 같이」·「다음 각 호」는 이어 묻기가 아니다")
        void doesNotReadEnumerationsAsAFollowUp() {
            assertThat(ArticleReference.parse("절차는 다음과 같이 진행되나요?", ANCHOR)).isNull();
            assertThat(ArticleReference.parse("다음 각 호에 해당하면 어떻게 되나요?", ANCHOR)).isNull();
        }

        @Test
        @DisplayName("가리키는 말이 아예 없으면 기준점이 있어도 풀지 않는다")
        void leavesUnrelatedQuestionsAlone() {
            assertThat(ArticleReference.parse("회비는 얼마인가요?", ANCHOR)).isNull();
        }
    }

    /*
     * 발췌에 넣을 조 목록 — 지목이 둘까지 실린다 (#465).
     */
    @Nested
    @DisplayName("핀으로 집을 조 목록")
    class References {

        @Test
        @DisplayName("나란히 놓고 묻는 두 조를 함께 집는다")
        void pinsTwoNamedArticles() {
            assertThat(ArticleReference.references("제18조와 제19조는 어떻게 다른가요?", null))
                    .containsExactly(
                            new ArticleReference(18, null, false),
                            new ArticleReference(19, null, false));
        }

        @Test
        @DisplayName("셋 이상을 적어도 둘까지 — 나머지는 밀집 검색에 맡긴다")
        void stopsAtTwo() {
            assertThat(ArticleReference.references("제23조부터 제25조까지 정리해줘", null)).hasSize(2);
        }

        @Test
        @DisplayName("같은 조를 두 번 적어도 한 번만 집는다")
        void doesNotPinTheSameArticleTwice() {
            assertThat(ArticleReference.references("제7조 1항과 제7조 6항의 차이는?", null))
                    .containsExactly(new ArticleReference(7, null, false));
        }

        @Test
        @DisplayName("지목이 하나도 없을 때에만 기준점에서 옮긴다")
        void fallsBackToTheAnchorOnlyWhenNothingIsNamed() {
            assertThat(
                            ArticleReference.references(
                                    "그 다음 조는?", new ArticleReference(7, null, false)))
                    .containsExactly(new ArticleReference(8, null, false));
        }

        @Test
        @DisplayName("집을 것이 없으면 빈 목록 — 핀 없이 밀집 검색만 남는다")
        void returnsNothingToPin() {
            assertThat(ArticleReference.references("회비는 얼마인가요?", null)).isEmpty();
        }
    }
}
