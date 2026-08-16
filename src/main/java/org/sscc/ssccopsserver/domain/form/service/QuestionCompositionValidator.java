package org.sscc.ssccopsserver.domain.form.service;

import java.util.HashSet;
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
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 문항 구성(qitem_cpst_cn) 검증기 — 이 이슈의 난이도가 전부 여기에 있다.
 *
 * 클라이언트가 보낸 문항 구성이 스스로 모순되면(존재하지 않는 페이지로 분기, 컴파일되지 않는
 * 정규식, 선택지 없는 선택형 문항) 저장 시점에는 아무 일도 일어나지 않고 공개 폼 응답 화면이
 * 통째로 깨진다. JSONB는 JSON 문법만 보장할 뿐 우리 구조까지 보장하지 않으므로 서버가
 * 최종 방어선이다.
 *
 * 규칙을 컨트롤러·서비스에 흩뿌리지 않고 이 클래스에 모으는 이유는 공개 폼 응답 검증(#35)이
 * 같은 규칙을 반대 방향으로 다시 쓰기 때문이다 — "이 답이 이 문항에 맞는가"를 판단하려면
 * 여기서 확정한 "문항이 무엇을 요구하는가"가 그대로 필요하다. 두 벌이 되면 저장은 통과하는데
 * 제출은 거부되는(또는 그 반대의) 상태가 생긴다.
 *
 * 위반 처리는 두 갈래다. 유형과 무관한 잔여 속성(비선택형에 남은 optionList, 비텍스트형에 남은
 * ptrnCn)은 거절하지 않고 정리한다 — 웹 편집기가 유형을 바꿀 때 지워서 보내지만 자동 저장(#63)
 * 타이밍에 따라 남을 수 있고, 남아 있어도 화면이 깨지지 않는 값이라 요청을 되돌릴 이유가 없다.
 * 반면 유형에 해당하는 값이 잘못된 것(범위 밖 pageSeq, 없는 페이지로의 분기, 깨진 정규식)은
 * 정리할 방법이 없어 400으로 거절한다.
 */
@Component
public class QuestionCompositionValidator {

    /*
     * 유형별 어휘. private이 아니라 패키지 범위인 것은 응답 검증(ResponseAnswerValidator · #35)이
     * 같은 어휘를 반대 방향으로 쓰기 때문이다 — "이 유형이 정규식을 갖는가"를 두 곳에서 각자
     * 정의하면 저장은 통과하는데 제출은 거부되는(또는 그 반대의) 상태가 생긴다.
     */

    /** branchMap을 가질 수 있는 유일한 유형 — 다중선택은 답이 여러 개라 갈 곳을 하나로 못 정한다 */
    static final Set<QuestionItemType> BRANCHABLE_TYPES = Set.of(QuestionItemType.SINGLE_CHOICE);

    /** 선택지(optionList)를 요구하는 유형 */
    static final Set<QuestionItemType> CHOICE_TYPES =
            Set.of(QuestionItemType.SINGLE_CHOICE, QuestionItemType.MULTI_CHOICE);

    /** 정규식(ptrnCn) 검증 대상 유형. 날짜는 형식이 고정이라 정규식을 두지 않는다 */
    static final Set<QuestionItemType> TEXT_TYPES =
            Set.of(QuestionItemType.SHORT_TEXT, QuestionItemType.LONG_TEXT);

    /*
     * 검증하고 정리된 구성을 돌려준다. 원본을 고치지 않고 새 값을 만드는 것은, 저장에 실패했을 때
     * 요청 객체가 반쯤 바뀐 채로 남아 로그·재시도가 원래 요청과 달라지는 것을 막기 위해서다.
     */
    public QuestionCompositionContent validate(QuestionCompositionContent content) {
        if (content == null) {
            throw invalid();
        }

        List<QuestionCompositionContent.Page> pages = content.pages();
        // 페이지가 없으면 모든 문항의 pageSeq가 갈 곳을 잃는다. 빈 폼이라도 페이지 한 장은 있어야 한다
        if (pages == null || pages.isEmpty()) {
            throw invalid();
        }

        // 문항이 하나도 없는 폼은 허용한다 — 편집을 막 시작한 DRAFT가 정상적으로 그 상태다
        List<QuestionItem> qitems = content.qitems() == null ? List.of() : content.qitems();

        Set<String> seenIds = new HashSet<>();
        List<QuestionItem> normalized =
                qitems.stream().map(qitem -> normalize(qitem, pages.size(), seenIds)).toList();

        return new QuestionCompositionContent(List.copyOf(pages), normalized);
    }

    private QuestionItem normalize(QuestionItem qitem, int pageCount, Set<String> seenIds) {
        if (qitem == null || qitem.qitemTypeCd() == null) {
            throw invalid();
        }

        /*
         * qitemId는 응답(rspns_cn)의 key다. 비어 있으면 답을 담을 자리가 없고, 폼 안에서 겹치면
         * 두 문항의 답이 같은 key에 덮어써져 하나가 사라진다. 유일성은 폼 단위로만 요구한다 —
         * 폼이 다르면 응답 행도 다르므로 겹쳐도 무해하다.
         */
        String qitemId = qitem.qitemId();
        if (qitemId == null || qitemId.isBlank() || !seenIds.add(qitemId)) {
            throw invalid();
        }

        // pageSeq 생략은 첫 페이지를 뜻한다 (웹 타입 주석 — 페이지가 1개인 폼은 생략한다)
        int pageSeq = qitem.pageSeq() == null ? 0 : qitem.pageSeq();
        if (pageSeq < 0 || pageSeq >= pageCount) {
            throw invalid();
        }

        QuestionItemType type = qitem.qitemTypeCd();
        boolean choice = CHOICE_TYPES.contains(type);
        List<String> optionList = normalizeOptions(qitem, choice);

        return new QuestionItem(
                qitemId,
                qitem.qitemLblNm(),
                type,
                qitem.reqYn() != null && qitem.reqYn(),
                pageSeq,
                optionList,
                normalizeBranchMap(qitem, type, optionList, pageCount),
                normalizePattern(qitem, type),
                TEXT_TYPES.contains(type) ? qitem.ptrnNm() : null,
                TEXT_TYPES.contains(type) ? qitem.ptrnMsgCn() : null,
                normalizeMaxSelectCount(qitem, type, optionList));
    }

    /*
     * 선택지. 비선택형에 남아 있던 값은 빈 배열로 정리한다 — NULL이 아니라 빈 배열인 것은 웹
     * 타입에서 optionList가 선택 필드가 아니기 때문이다(빈 배열이 "선택지 없음"의 표기다).
     *
     * 선택형인데 선택지가 없으면 응답자가 고를 것이 없는 문항이 되고, 선택지가 겹치면 응답이
     * 문자열로 저장되는 계약상 어느 쪽을 고른 것인지 구분할 수 없다.
     */
    private List<String> normalizeOptions(QuestionItem qitem, boolean choice) {
        List<String> optionList = qitem.optionList();
        if (!choice) {
            return List.of();
        }
        if (optionList == null || optionList.isEmpty()) {
            throw invalid();
        }
        Set<String> distinct = new HashSet<>();
        for (String option : optionList) {
            if (option == null || option.isBlank() || !distinct.add(option)) {
                throw invalid();
            }
        }
        return List.copyOf(optionList);
    }

    /*
     * 분기. 없는 선택지를 key로 두면 영원히 타지 않는 분기가 되고, 없는 페이지를 가리키면
     * 응답 화면이 그 선택지를 고르는 순간 빈 페이지로 떨어진다.
     */
    private Map<String, Integer> normalizeBranchMap(
            QuestionItem qitem, QuestionItemType type, List<String> optionList, int pageCount) {
        Map<String, Integer> branchMap = qitem.branchMap();
        if (!BRANCHABLE_TYPES.contains(type) || branchMap == null || branchMap.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Integer> branch : branchMap.entrySet()) {
            Integer target = branch.getValue();
            if (!optionList.contains(branch.getKey())
                    || target == null
                    || target < 0
                    || target >= pageCount) {
                throw invalid();
            }
        }
        return Map.copyOf(branchMap);
    }

    /*
     * 입력 형식 정규식. 깨진 정규식이 저장되면 응답 검증(#35)이 폼 전체에서 무너지므로
     * 저장 시점에 Pattern.compile()로 실제 컴파일해 본다 — 문자열 모양만 보는 검사로는
     * 열리지 않은 괄호나 잘못된 수량자를 잡을 수 없다.
     */
    private String normalizePattern(QuestionItem qitem, QuestionItemType type) {
        String pattern = qitem.ptrnCn();
        if (!TEXT_TYPES.contains(type) || pattern == null || pattern.isBlank()) {
            return null;
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw invalid();
        }
        return pattern;
    }

    /*
     * 최대 선택 수. 0 이하면 아무것도 고를 수 없는 문항이 되고, 선택지 수보다 크면 화면이
     * 도달할 수 없는 상한을 안내하게 된다. 다중선택이 아닌 유형에 남아 있으면 정리한다.
     */
    private Integer normalizeMaxSelectCount(
            QuestionItem qitem, QuestionItemType type, List<String> optionList) {
        Integer maxSelectCount = qitem.maxSlctCnt();
        if (type != QuestionItemType.MULTI_CHOICE || maxSelectCount == null) {
            return null;
        }
        if (maxSelectCount < 1 || maxSelectCount > optionList.size()) {
            throw invalid();
        }
        return maxSelectCount;
    }

    private GeneralException invalid() {
        return new GeneralException(FormErrorCode.INVALID_QUESTION_COMPOSITION);
    }
}
