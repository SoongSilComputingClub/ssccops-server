package org.sscc.ssccopsserver.domain.form.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 응답 내용(rspns_cn) 검증기 — QuestionCompositionValidator(#32)의 짝.
 *
 * #32가 "문항이 무엇을 요구하는가"를 확정했다면 여기는 "이 답이 그 요구를 만족하는가"를 본다.
 * 두 클래스가 같은 어휘(QuestionCompositionValidator.CHOICE_TYPES·TEXT_TYPES·BRANCHABLE_TYPES)를
 * 나눠 쓰는 것은 의도된 것이다 — 유형별 규칙이 두 벌이 되면 저장은 통과하는데 제출은 거부되는
 * 폼이 생긴다.
 *
 * 웹(public-form-page.tsx의 validatePage)도 같은 검사를 하지만 그 검사는 신뢰 대상이 아니다.
 * 공개 링크라 누구나 요청을 직접 만들 수 있고, 문항을 고친 뒤 열어 둔 낡은 탭도 그대로 제출한다.
 *
 * ── 분기로 건너뛴 페이지 ──
 *
 * 이 검증기에서 가장 틀리기 쉬운 지점이다. branchMap은 단일선택의 답에 따라 다음 페이지를
 * 갈아치우므로, 응답자가 아예 보지 못한 페이지가 생긴다. 그 페이지의 필수 문항까지 요구하면
 * 분기가 있는 폼은 어떤 답으로도 제출할 수 없다.
 *
 * 그래서 제출된 답으로 웹의 nextTarget()과 같은 이동을 되짚어 실제 도달한 페이지 집합을
 * 재현하고, 그 집합 밖의 문항은 필수 검사에서 뺀다. 형식(정규식)·최대 선택 수는 반대로
 * 도달 여부와 무관하게 검사한다 — 답이 실려 온 이상 그 답은 규칙을 지켜야 한다.
 */
@Component
public class ResponseAnswerValidator {

    /*
     * 페이지 이동 상한. 분기가 앞 페이지를 가리키면 경로가 순환할 수 있는데, 구성 검증(#32)은
     * 대상 페이지가 실재하는지만 보고 되돌아가는 분기를 막지 않는다. 방문한 페이지를 다시 만나면
     * 그 자리에서 끊으므로 실제로는 pages.size()를 넘길 수 없지만, 재현 로직이 잘못돼도
     * 요청 스레드가 멈추지 않도록 상한을 함께 둔다.
     */
    private static final int MAX_PAGE_HOPS = 1000;

    /*
     * 검증하고 저장할 형태로 정리된 응답을 돌려준다. 원본을 고치지 않는 이유는 #32와 같다 —
     * 저장에 실패했을 때 요청 객체가 반쯤 바뀐 채로 남으면 로그·재시도가 원래 요청과 달라진다.
     *
     * 돌려주는 값에서 빈 값(""·[])인 key는 빠져 있다. 웹(use-public-submit)이 이미 그렇게
     * 걸러 보내지만 서버가 다시 거르는 것은, 저장된 뒤에는 "빈 문자열로 답했다"와 "답하지
     * 않았다"를 구별할 방법이 없기 때문이다.
     */
    public ResponseContent validate(
            QuestionCompositionContent composition, ResponseContent submitted) {
        Map<String, QuestionItem> qitemsById = qitemsById(composition);
        Map<String, Object> answers = submitted == null ? Map.of() : submitted.answers();

        /*
         * 폼에 없는 qitemId는 조용히 버리지 않는다. 버리면 응답자는 제출에 성공했다고 믿는데
         * 실제로 저장된 답은 화면에서 본 것과 다르고, 그 어긋남이 접수 마감 후 집계에서야 드러난다.
         */
        for (String qitemId : answers.keySet()) {
            if (!qitemsById.containsKey(qitemId)) {
                throw new GeneralException(FormErrorCode.UNKNOWN_QUESTION_ITEM);
            }
        }

        // 문항 순서대로 담는다 — 응답 원문을 다시 보여줄 때 화면 순서와 같아야 한다
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (QuestionItem qitem : qitemsById.values()) {
            Object value = normalizeAnswer(qitem, answers.get(qitem.qitemId()));
            if (value != null) {
                normalized.put(qitem.qitemId(), value);
            }
        }

        requireAnswersOnReachedPages(composition, qitemsById.values(), normalized);

        return ResponseContent.of(normalized);
    }

    /*
     * 문항 색인. 구성 검증(#32)이 qitemId의 유일성을 이미 보장하므로 뒤에 온 문항이 앞을 덮어써도
     * 실제로는 겹치지 않는다. LinkedHashMap인 것은 아래에서 문항 순서를 그대로 쓰기 위해서다.
     */
    private Map<String, QuestionItem> qitemsById(QuestionCompositionContent composition) {
        Map<String, QuestionItem> qitemsById = new LinkedHashMap<>();
        if (composition == null || composition.qitems() == null) {
            return qitemsById;
        }
        for (QuestionItem qitem : composition.qitems()) {
            if (qitem != null && qitem.qitemId() != null) {
                qitemsById.put(qitem.qitemId(), qitem);
            }
        }
        return qitemsById;
    }

    /*
     * 답 하나를 유형에 맞는 저장 형태로 정리한다. 답이 없으면(빈 값 포함) null을 돌려주고
     * 호출부가 key 자체를 빼므로, 저장된 rspns_cn에는 빈 값인 key가 남지 않는다.
     *
     * 저장 형태는 ResponseContent가 정한 계약을 따른다 — 다중선택은 List<String>, 나머지는 String.
     */
    private Object normalizeAnswer(QuestionItem qitem, Object value) {
        if (value == null) {
            return null;
        }
        QuestionItemType type = qitem.qitemTypeCd();
        if (type == QuestionItemType.MULTI_CHOICE) {
            return normalizeMultiChoice(qitem, value);
        }
        if (QuestionCompositionValidator.CHOICE_TYPES.contains(type)) {
            return normalizeSingleChoice(qitem, value);
        }
        return normalizeText(qitem, value);
    }

    /*
     * 텍스트·날짜. 값은 문자열이어야 하며 공백뿐이면 답하지 않은 것으로 본다.
     *
     * 날짜(DATE)에 별도 형식 검사를 걸지 않는 것은 구성 검증이 이 유형에 정규식을 두지 않기
     * 때문이다(#32) — 여기서만 형식을 요구하면 폼 편집기가 안내하지 않은 규칙이 제출 시점에
     * 처음 드러난다.
     */
    private Object normalizeText(QuestionItem qitem, Object value) {
        if (!(value instanceof CharSequence text)) {
            throw new GeneralException(FormErrorCode.INVALID_ANSWER_VALUE);
        }
        String answer = text.toString();
        if (answer.isBlank()) {
            return null;
        }
        requirePatternMatch(qitem, answer);
        return answer;
    }

    /*
     * 단일선택. 웹은 선택지를 배열 한 칸에 담아 보내지만(pickChoice가 [option]으로 쓴다)
     * 저장 계약은 문자열이므로 여기서 벗겨 굳힌다. 두 모양이 그대로 저장되면 응답 조회(#37)와
     * 집계가 문항마다 어느 쪽인지 따져야 한다.
     *
     * 원소가 둘 이상인 배열은 정리하지 않고 거절한다 — 단일선택에 답이 둘이면 어느 쪽을 고른
     * 것인지 서버가 정할 수 없고, 잘못 고르면 분기 목적지까지 달라진다.
     */
    private Object normalizeSingleChoice(QuestionItem qitem, Object value) {
        Object single = value;
        if (value instanceof List<?> values) {
            if (values.isEmpty()) {
                return null;
            }
            if (values.size() > 1) {
                throw new GeneralException(FormErrorCode.INVALID_ANSWER_VALUE);
            }
            single = values.get(0);
        }
        if (!(single instanceof CharSequence text)) {
            throw new GeneralException(FormErrorCode.INVALID_ANSWER_VALUE);
        }
        String answer = text.toString();
        if (answer.isBlank()) {
            return null;
        }
        requireKnownOption(qitem, answer);
        return answer;
    }

    /*
     * 다중선택. 배열이 아니면 거절한다 — 단일선택과 달리 문자열 하나를 받아 배열로 감싸면
     * 쉼표가 든 선택지와 구분되지 않는다.
     */
    private Object normalizeMultiChoice(QuestionItem qitem, Object value) {
        if (!(value instanceof List<?> values)) {
            throw new GeneralException(FormErrorCode.INVALID_ANSWER_VALUE);
        }
        List<String> answers = new ArrayList<>();
        for (Object element : values) {
            if (!(element instanceof CharSequence text)) {
                throw new GeneralException(FormErrorCode.INVALID_ANSWER_VALUE);
            }
            String answer = text.toString();
            if (answer.isBlank()) {
                continue;
            }
            requireKnownOption(qitem, answer);
            answers.add(answer);
        }
        if (answers.isEmpty()) {
            return null;
        }

        Integer maxSelectCount = qitem.maxSlctCnt();
        if (maxSelectCount != null && answers.size() > maxSelectCount) {
            throw new GeneralException(FormErrorCode.ANSWER_SELECTION_LIMIT_EXCEEDED);
        }
        return List.copyOf(answers);
    }

    /*
     * 선택지 실재 여부. 응답은 선택지의 값(문자열) 자체로 저장되므로, 목록에 없는 값이 들어오면
     * 그 답은 어떤 선택지도 가리키지 않는 채 남는다. 분기(branchMap)의 key도 선택지 값이라
     * 목적지 계산까지 틀어진다.
     */
    private void requireKnownOption(QuestionItem qitem, String answer) {
        List<String> optionList = qitem.optionList();
        if (optionList == null || !optionList.contains(answer)) {
            throw new GeneralException(FormErrorCode.INVALID_ANSWER_VALUE);
        }
    }

    /*
     * 입력 형식. 구성 검증이 저장 시점에 Pattern.compile()을 통과시켰으므로 여기서 깨질 일은
     * 없지만, 저장 이후 DB가 직접 수정되는 경우까지 500으로 새지 않게 감싼다.
     *
     * matches()가 아니라 find()인 것은 웹의 new RegExp(p).test(text)와 같은 뜻이기 때문이다.
     * 자바의 matches()는 전체 일치라, 앵커(^ $)를 쓰지 않은 정규식을 웹은 통과시키고 서버는
     * 거절하는 어긋남이 생긴다. 폼 편집기가 안내한 규칙은 웹이 검사한 그 규칙이어야 한다.
     */
    private void requirePatternMatch(QuestionItem qitem, String answer) {
        String pattern = qitem.ptrnCn();
        if (!QuestionCompositionValidator.TEXT_TYPES.contains(qitem.qitemTypeCd())
                || pattern == null
                || pattern.isBlank()) {
            return;
        }
        try {
            if (!Pattern.compile(pattern).matcher(answer).find()) {
                throw new GeneralException(FormErrorCode.ANSWER_PATTERN_MISMATCH);
            }
        } catch (PatternSyntaxException e) {
            throw new GeneralException(FormErrorCode.INVALID_QUESTION_COMPOSITION);
        }
    }

    /*
     * 필수 검사. 실제로 도달한 페이지의 문항만 대상이다 — 분기로 건너뛴 페이지는 응답자가
     * 보지도 못했으므로 그 페이지의 필수 문항을 요구하면 분기 폼은 제출 자체가 불가능해진다.
     */
    private void requireAnswersOnReachedPages(
            QuestionCompositionContent composition,
            Iterable<QuestionItem> qitems,
            Map<String, Object> answers) {

        Set<Integer> reachedPages = reachedPages(composition, answers);
        for (QuestionItem qitem : qitems) {
            boolean required = Boolean.TRUE.equals(qitem.reqYn());
            if (required
                    && reachedPages.contains(pageSeqOf(qitem))
                    && !answers.containsKey(qitem.qitemId())) {
                throw new GeneralException(FormErrorCode.REQUIRED_ANSWER_MISSING);
            }
        }
    }

    /*
     * 제출된 답으로 실제 페이지 경로를 되짚는다. 웹의 nextTarget()과 같은 규칙이다 —
     * 현재 페이지의 문항을 순서대로 훑어 분기표에 걸리는 단일선택 답을 처음 만나면 그 목적지로
     * 가고, 없으면 다음 페이지로 간다. 마지막 페이지에 닿으면 끝난다.
     *
     * 이미 지나온 페이지를 다시 만나면 그 자리에서 멈춘다. 되돌아가는 분기는 구성 검증이 막지
     * 않아 순환하는 폼이 저장될 수 있는데, 순환한다는 것은 그 뒤의 페이지에 새로 도달하지
     * 않는다는 뜻이라 필수 검사 대상도 늘지 않는다.
     */
    private Set<Integer> reachedPages(
            QuestionCompositionContent composition, Map<String, Object> answers) {

        List<QuestionCompositionContent.Page> pages =
                composition == null ? null : composition.pages();
        if (pages == null || pages.isEmpty()) {
            return Set.of();
        }

        int lastPage = pages.size() - 1;
        Set<Integer> reached = new LinkedHashSet<>();
        int page = 0;
        for (int hop = 0; hop <= MAX_PAGE_HOPS; hop++) {
            if (page < 0 || page > lastPage || !reached.add(page) || page == lastPage) {
                break;
            }
            Integer branchTarget = branchTargetOf(composition, page, answers);
            page = branchTarget == null ? page + 1 : branchTarget;
        }
        return reached;
    }

    /*
     * 이 페이지에서 분기가 걸리는가. 분기표가 있는 단일선택 문항 중 답이 분기표의 key와 맞는
     * 첫 문항이 목적지를 정한다 — 한 페이지에 분기 문항이 여럿이면 웹도 첫 번째를 쓴다.
     */
    private Integer branchTargetOf(
            QuestionCompositionContent composition, int page, Map<String, Object> answers) {

        if (composition.qitems() == null) {
            return null;
        }
        for (QuestionItem qitem : composition.qitems()) {
            if (qitem == null
                    || pageSeqOf(qitem) != page
                    || !QuestionCompositionValidator.BRANCHABLE_TYPES.contains(qitem.qitemTypeCd())
                    || qitem.branchMap() == null) {
                continue;
            }
            // 정리된 답이라 단일선택은 반드시 문자열이다 (normalizeSingleChoice)
            Object picked = answers.get(qitem.qitemId());
            if (picked instanceof String option && qitem.branchMap().containsKey(option)) {
                return qitem.branchMap().get(option);
            }
        }
        return null;
    }

    /** pageSeq 생략은 첫 페이지를 뜻한다 (#32 · 웹 타입 주석과 같은 해석) */
    private int pageSeqOf(QuestionItem qitem) {
        return qitem.pageSeq() == null ? 0 : qitem.pageSeq();
    }
}
