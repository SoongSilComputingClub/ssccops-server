package org.sscc.ssccopsserver.domain.form.entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/*
 * form.qitem_cpst_cn(JSONB)의 내용 타입 — 폼이 실제로 사용하는 문항 구조.
 *
 * 문항을 컬럼이나 별도 테이블로 펼치지 않고 JSON 문서 하나로 두는 이유는 폼마다 문항의
 * 개수·유형·검증 규칙이 전부 달라서다. 정규화하면 문항 유형이 늘 때마다 컬럼이 늘고,
 * 폼 한 건을 그리려고 문항·선택지·분기 테이블을 매번 조인해야 한다.
 *
 * 필드명이 자바 관례(questionItems 등)가 아니라 데이터사전 표기 그대로(qitems·qitemLblNm)인
 * 것은 의도된 것이다. 이 record는 그대로 JSON 키가 되고, 그 JSON은 웹 프로토타입이 이미
 * 확정해 둔 계약(entities/form/model/types.ts)이다. 자바 쪽 취향으로 이름을 바꾸면
 * @JsonProperty 매핑 표가 하나 더 생기고, 그 표가 어긋나면 조용히 빈 폼이 렌더링된다.
 *
 * ignoreUnknown = true는 웹이 필드를 먼저 추가해도 서버가 저장된 폼을 못 읽는 상황을
 * 막기 위한 것이다 — 서버가 모르는 필드는 그대로 흘려보내는 대신, 알던 필드는 계속 읽는다.
 * 반대로 서버가 모르는 필드를 저장 왕복에서 보존하지는 못하므로, 문항 구성을 수정하는
 * API(#32)는 클라이언트가 보낸 전체 구성으로 통째로 덮어써야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionCompositionContent(List<Page> pages, List<QuestionItem> qitems) {

    /*
     * 깊은 복사 (#32 폼 복제).
     *
     * record 자체는 불변이지만 안에 든 List·Map은 Jackson이 역직렬화하면서 만든 가변 컬렉션이라,
     * 얕게 복사하면 원본과 사본이 같은 리스트를 가리킨다. 그 상태로 원본을 수정하면 사본의
     * 문항까지 함께 바뀌어 "복제했는데 원본을 고치니 사본도 바뀐다"는 형태로 드러난다.
     *
     * List.copyOf가 아니라 원소까지 새로 만드는 것은, 원소인 QuestionItem이 다시 List·Map을
     * 품고 있어 한 겹만 복사해서는 그 안쪽이 그대로 공유되기 때문이다.
     */
    public QuestionCompositionContent deepCopy() {
        return new QuestionCompositionContent(
                pages == null
                        ? null
                        : pages.stream()
                                .map(page -> new Page(page.pageTtl(), page.pageDescCn()))
                                .toList(),
                qitems == null ? null : qitems.stream().map(QuestionItem::copy).toList());
    }

    /*
     * 구성이 담고 있는 qitemId 집합.
     *
     * "문항 식별자가 그대로 남아 있는가"를 묻는 곳이 둘이라 여기에 둔다 — 응답이 있는 폼의
     * 식별자 보호(QUESTION_ITEM_IN_USE)와 시스템 폼의 코드 계약(#140)이다. 두 판정은 기준이
     * 다르지만 재료가 같아, 각자 스트림을 돌리면 NULL 처리 같은 사소한 차이가 갈릴 자리가 된다.
     *
     * qitems가 NULL일 수 있는 것은 이 record가 JSONB에서 그대로 역직렬화되기 때문이다.
     * 저장 경로는 QuestionCompositionValidator를 지나 항상 리스트를 갖지만, 옛 데이터나
     * 검증을 거치지 않은 값도 이 자리에 올 수 있어 빈 집합으로 받는다.
     */
    public static Set<String> qitemIdsOf(QuestionCompositionContent content) {
        if (content == null || content.qitems() == null) {
            return Set.of();
        }
        return content.qitems().stream()
                .map(QuestionItem::qitemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /*
     * 페이지. 다중 페이지 폼의 표지 역할만 하며, 어떤 문항이 어느 페이지에 있는지는
     * 페이지가 아니라 문항의 pageSeq가 갖는다 — 문항을 옮길 때 한쪽만 고치면 어긋난다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(String pageTtl, String pageDescCn) {}

    /*
     * 문항. 유형별 전용 속성(branchMap·ptrnCn·maxSlctCnt)을 유형마다 다른 record로 쪼개지 않고
     * 한 record에 모아 두고 쓰지 않는 속성을 NULL로 둔다. 다형 역직렬화(@JsonSubTypes)를 쓰면
     * JSON에 유형 판별용 필드가 하나 더 필요한데, qitemTypeCd가 이미 그 역할을 하고 있어
     * 웹이 보내는 JSON 모양을 바꾸지 않고는 붙일 수 없다.
     *
     * qitemId는 응답(rspns_cn)의 key다. 문항을 수정해도 이 값만은 바뀌면 안 된다 —
     * 바뀌는 순간 이미 접수된 응답이 어느 문항의 답인지 알 수 없게 된다.
     *
     * qitemDescCn(문항 설명)은 qitemLblNm의 짝이다 (ssccops#222) — 질문 문구와 안내를 한 값에
     * 담으면 화면이 둘을 다르게 그릴 수 없다. 페이지의 pageDescCn과 같은 어휘를 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionItem(
            String qitemId,
            String qitemLblNm,
            String qitemDescCn,
            QuestionItemType qitemTypeCd,
            Boolean reqYn,
            Integer pageSeq,
            List<String> optionList,
            Map<String, Integer> branchMap,
            String ptrnCn,
            String ptrnNm,
            String ptrnMsgCn,
            Integer maxSlctCnt) {

        /*
         * qitemDescCn이 없던 시절의 시그니처 (ssccops#222).
         *
         * 설명은 **유형과 무관하게 모든 문항이 가질 수 있는 선택 값**이라, 그것을 모르는 자리
         * (학술·행사 도메인의 테스트, 시스템 폼 시드)까지 인자 하나를 더 적게 만들 이유가 없다.
         * 옛 시그니처를 남겨 두면 이 변경의 diff가 폼 도메인 안에 머문다.
         *
         * Jackson은 record의 **정규 생성자**(12개)를 자동으로 골라 쓰므로 이 생성자가 역직렬화에
         * 끼어들지 않는다 — 인자 수가 달라 모호할 자리도 없다. 그 사실은 왕복 테스트가 지킨다.
         */
        public QuestionItem(
                String qitemId,
                String qitemLblNm,
                QuestionItemType qitemTypeCd,
                Boolean reqYn,
                Integer pageSeq,
                List<String> optionList,
                Map<String, Integer> branchMap,
                String ptrnCn,
                String ptrnNm,
                String ptrnMsgCn,
                Integer maxSlctCnt) {
            this(
                    qitemId,
                    qitemLblNm,
                    null,
                    qitemTypeCd,
                    reqYn,
                    pageSeq,
                    optionList,
                    branchMap,
                    ptrnCn,
                    ptrnNm,
                    ptrnMsgCn,
                    maxSlctCnt);
        }

        /** 문항 한 건의 깊은 복사. 안쪽 컬렉션까지 새로 만든다 — 근거는 deepCopy() 주석 참조 */
        public QuestionItem copy() {
            return new QuestionItem(
                    qitemId,
                    qitemLblNm,
                    qitemDescCn,
                    qitemTypeCd,
                    reqYn,
                    pageSeq,
                    optionList == null ? null : List.copyOf(optionList),
                    branchMap == null ? null : new LinkedHashMap<>(branchMap),
                    ptrnCn,
                    ptrnNm,
                    ptrnMsgCn,
                    maxSlctCnt);
        }
    }
}
