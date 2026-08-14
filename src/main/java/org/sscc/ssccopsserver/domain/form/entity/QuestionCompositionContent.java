package org.sscc.ssccopsserver.domain.form.entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionItem(
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

        /** 문항 한 건의 깊은 복사. 안쪽 컬렉션까지 새로 만든다 — 근거는 deepCopy() 주석 참조 */
        public QuestionItem copy() {
            return new QuestionItem(
                    qitemId,
                    qitemLblNm,
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
