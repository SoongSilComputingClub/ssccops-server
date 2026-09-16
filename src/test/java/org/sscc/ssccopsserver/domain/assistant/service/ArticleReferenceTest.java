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
        @DisplayName("둘 이상이면 첫 번째만 — 나머지 맥락은 벡터 검색에 맡긴다")
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
}
