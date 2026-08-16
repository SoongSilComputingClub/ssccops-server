package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 문항 구성 검증 규칙을 규칙 단위로 확인한다.
 *
 * 컨트롤러 테스트에서도 400이 나오는지 보지만, 규칙이 열 개가 넘어 전부를 HTTP로 태우면
 * 실패했을 때 어느 규칙이 깨졌는지 본문 JSON에서 읽어내야 한다. 검증기는 스프링 컨텍스트가
 * 필요 없는 순수 객체라 여기서 직접 부른다.
 */
class QuestionCompositionValidatorTest {

    private final QuestionCompositionValidator validator = new QuestionCompositionValidator();

    private QuestionItem item(
            String qitemId, QuestionItemType type, Integer pageSeq, List<String> optionList) {
        return new QuestionItem(
                qitemId, "문항", type, true, pageSeq, optionList, null, null, null, null, null);
    }

    private QuestionCompositionContent composition(int pageCount, QuestionItem... qitems) {
        return new QuestionCompositionContent(
                IntStream.range(0, pageCount)
                        .mapToObj(index -> new Page("페이지 " + index, null))
                        .toList(),
                List.of(qitems));
    }

    private void assertRejected(QuestionCompositionContent content) {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .isEqualTo(FormErrorCode.INVALID_QUESTION_COMPOSITION);
    }

    // 페이지가 없으면 모든 문항의 pageSeq가 갈 곳을 잃는다
    @Test
    void rejectsCompositionWithoutAnyPage() {
        assertRejected(new QuestionCompositionContent(List.of(), List.of()));
        assertRejected(new QuestionCompositionContent(null, List.of()));
    }

    // 편집을 막 시작한 DRAFT는 문항이 없는 것이 정상이다
    @Test
    void allowsCompositionWithoutQuestionItems() {
        QuestionCompositionContent validated = validator.validate(composition(1));

        assertThat(validated.qitems()).isEmpty();
        assertThat(validated.pages()).hasSize(1);
    }

    @Test
    void rejectsPageSequenceOutOfRange() {
        assertRejected(composition(2, item("q1", QuestionItemType.SHORT_TEXT, 2, List.of())));
        assertRejected(composition(2, item("q1", QuestionItemType.SHORT_TEXT, -1, List.of())));
    }

    // 웹 타입 주석대로 페이지가 1개인 폼은 pageSeq를 생략한다 — 첫 페이지로 채워야 한다
    @Test
    void fillsOmittedPageSequenceWithFirstPage() {
        QuestionCompositionContent validated =
                validator.validate(
                        composition(1, item("q1", QuestionItemType.SHORT_TEXT, null, List.of())));

        assertThat(validated.qitems().get(0).pageSeq()).isZero();
    }

    // qitemId는 응답(rspns_cn)의 key라 비어 있거나 겹치면 답을 담을 자리가 어긋난다
    @Test
    void rejectsBlankOrDuplicatedQuestionItemId() {
        assertRejected(composition(1, item(" ", QuestionItemType.SHORT_TEXT, 0, List.of())));
        assertRejected(composition(1, item(null, QuestionItemType.SHORT_TEXT, 0, List.of())));
        assertRejected(
                composition(
                        1,
                        item("q1", QuestionItemType.SHORT_TEXT, 0, List.of()),
                        item("q1", QuestionItemType.LONG_TEXT, 0, List.of())));
    }

    @Test
    void rejectsChoiceQuestionWithoutOptions() {
        assertRejected(composition(1, item("q1", QuestionItemType.SINGLE_CHOICE, 0, List.of())));
        assertRejected(composition(1, item("q1", QuestionItemType.MULTI_CHOICE, 0, null)));
    }

    /*
     * 응답은 선택지 문자열 그대로 저장되므로(rspns_cn) 같은 문자열이 두 번 있으면 어느 쪽을
     * 고른 것인지 구분할 수 없다.
     */
    @Test
    void rejectsDuplicatedOrBlankOption() {
        assertRejected(
                composition(1, item("q1", QuestionItemType.SINGLE_CHOICE, 0, List.of("가", "가"))));
        assertRejected(
                composition(1, item("q1", QuestionItemType.SINGLE_CHOICE, 0, List.of("가", " "))));
    }

    @Test
    void rejectsBranchToNonExistentPage() {
        assertRejected(branching(2, Map.of("가", 2)));
        assertRejected(branching(2, Map.of("가", -1)));
    }

    // 선택지에 없는 key는 영원히 타지 않는 분기라 조용히 두면 편집자가 잘못을 알 수 없다
    @Test
    void rejectsBranchKeyThatIsNotAnOption() {
        assertRejected(branching(2, Map.of("없는선택지", 1)));
    }

    private QuestionCompositionContent branching(int pageCount, Map<String, Integer> branchMap) {
        return composition(
                pageCount,
                new QuestionItem(
                        "q1",
                        "분기 문항",
                        QuestionItemType.SINGLE_CHOICE,
                        true,
                        0,
                        List.of("가", "나"),
                        branchMap,
                        null,
                        null,
                        null,
                        null));
    }

    /*
     * 깨진 정규식이 저장되면 응답 검증(#35)이 폼 전체에서 무너진다. 문자열 모양만 보는 검사로는
     * 열리지 않은 괄호를 잡을 수 없으므로 실제로 컴파일해 본다.
     */
    @Test
    void rejectsPatternThatDoesNotCompile() {
        assertRejected(pattern(QuestionItemType.SHORT_TEXT, "^[가-힣"));
        assertRejected(pattern(QuestionItemType.LONG_TEXT, "*"));
    }

    @Test
    void keepsCompilablePattern() {
        QuestionCompositionContent validated =
                validator.validate(pattern(QuestionItemType.SHORT_TEXT, "^[가-힣]{2,5}$"));

        assertThat(validated.qitems().get(0).ptrnCn()).isEqualTo("^[가-힣]{2,5}$");
    }

    private QuestionCompositionContent pattern(QuestionItemType type, String ptrnCn) {
        return composition(
                1,
                new QuestionItem(
                        "q1",
                        "형식 검증 문항",
                        type,
                        true,
                        0,
                        List.of(),
                        null,
                        ptrnCn,
                        "이름",
                        "형식이 올바르지 않습니다.",
                        null));
    }

    @Test
    void rejectsMaxSelectCountOutOfRange() {
        assertRejected(multiChoice(List.of("가", "나"), 3));
        assertRejected(multiChoice(List.of("가", "나"), 0));
    }

    @Test
    void keepsMaxSelectCountWithinRange() {
        QuestionCompositionContent validated =
                validator.validate(multiChoice(List.of("가", "나"), 2));

        assertThat(validated.qitems().get(0).maxSlctCnt()).isEqualTo(2);
    }

    private QuestionCompositionContent multiChoice(List<String> optionList, Integer maxSlctCnt) {
        return composition(
                1,
                new QuestionItem(
                        "q1",
                        "다중선택 문항",
                        QuestionItemType.MULTI_CHOICE,
                        false,
                        0,
                        optionList,
                        null,
                        null,
                        null,
                        null,
                        maxSlctCnt));
    }

    /*
     * 유형과 무관한 잔여 속성은 거절이 아니라 정리다 — 웹 편집기는 유형을 바꿀 때 지워서 보내지만
     * 자동 저장(#63) 타이밍에 따라 남을 수 있고, 남아도 화면이 깨지지 않아 되돌릴 이유가 없다.
     */
    @Test
    void stripsAttributesThatDoNotBelongToTheType() {
        Map<String, Integer> branchMap = new LinkedHashMap<>();
        branchMap.put("가", 0);

        QuestionCompositionContent validated =
                validator.validate(
                        composition(
                                1,
                                new QuestionItem(
                                        "q1",
                                        "짧은 텍스트인데 선택형 속성이 남아 있다",
                                        QuestionItemType.SHORT_TEXT,
                                        true,
                                        0,
                                        List.of("가", "나"),
                                        branchMap,
                                        "^.+$",
                                        "자유",
                                        "안내",
                                        2),
                                new QuestionItem(
                                        "q2",
                                        "단일선택인데 텍스트 속성이 남아 있다",
                                        QuestionItemType.SINGLE_CHOICE,
                                        true,
                                        0,
                                        List.of("가", "나"),
                                        null,
                                        "^[0-9]+$",
                                        "숫자",
                                        "안내",
                                        2)));

        QuestionItem shortText = validated.qitems().get(0);
        assertThat(shortText.optionList()).isEmpty();
        assertThat(shortText.branchMap()).isNull();
        assertThat(shortText.maxSlctCnt()).isNull();
        assertThat(shortText.ptrnCn()).isEqualTo("^.+$");

        QuestionItem singleChoice = validated.qitems().get(1);
        assertThat(singleChoice.ptrnCn()).isNull();
        assertThat(singleChoice.ptrnNm()).isNull();
        assertThat(singleChoice.ptrnMsgCn()).isNull();
        assertThat(singleChoice.maxSlctCnt()).isNull();
        assertThat(singleChoice.optionList()).containsExactly("가", "나");
    }

    // 필수 여부가 비어 있으면 "필수 아님"이다 — NULL을 그대로 두면 응답 검증(#35)이 매번 분기해야 한다
    @Test
    void fillsOmittedRequiredFlagWithFalse() {
        QuestionCompositionContent validated =
                validator.validate(
                        new QuestionCompositionContent(
                                List.of(new Page("한 장", null)),
                                List.of(
                                        new QuestionItem(
                                                "q1",
                                                "문항",
                                                QuestionItemType.DATE,
                                                null,
                                                0,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))));

        assertThat(validated.qitems().get(0).reqYn()).isFalse();
    }

    @Test
    void rejectsQuestionItemWithoutType() {
        assertRejected(composition(1, item("q1", null, 0, List.of())));
    }
}
