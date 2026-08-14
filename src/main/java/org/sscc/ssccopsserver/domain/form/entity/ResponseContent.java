package org.sscc.ssccopsserver.domain.form.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/*
 * form_rspns_hstry.rspns_cn(JSONB)의 내용 타입 — 문항 식별자(qitemId)를 key로 하는 답변 묶음.
 *
 * 문항 구성과 달리 여기에는 고정된 필드가 없다. key가 폼마다 다르기 때문이다.
 * 그래서 record의 필드가 그대로 JSON 키가 되는 QuestionCompositionContent와는 반대로,
 * 이 record는 Map을 하나 감싸고 @JsonValue/@JsonCreator로 그 껍데기를 벗겨 저장한다 —
 * 감싸지 않으면 {"answers": {...}} 처럼 한 겹 더 들어가 웹이 확정한 계약과 어긋난다.
 *
 * 그럼에도 Map을 그대로 엔티티 필드로 쓰지 않는 이유는, 응답 검증(#35)과 자동 저장(#36)이
 * "이 문항의 답"을 꺼내는 코드를 각자 쓰게 되기 때문이다. 꺼내는 규칙(다중선택은 배열,
 * 그 외는 문자열)이 한 군데에 있어야 두 이슈가 같은 뜻으로 읽는다.
 *
 * 값 타입이 Object인 것은 그 규칙의 결과다. 다중선택 문항은 List<String>, 나머지는 String이라
 * 공통 상위 타입이 Object뿐이다. 어느 쪽인지는 문항 유형(QuestionItemType)이 정하므로
 * 값만 보고 판단하지 않는다.
 */
public record ResponseContent(Map<String, Object> answers) {

    /*
     * 방어적 복사. JSONB로 오간 Map을 호출부가 그대로 들고 고치면 영속 상태의 응답 내용이
     * 트랜잭션 밖에서 바뀐다 — 더티 체킹이 언제 잡는지에 따라 저장 여부가 갈린다.
     *
     * Map.copyOf가 아니라 LinkedHashMap을 감싸는 것은 문항 순서를 보존하기 위해서다.
     * Map.copyOf가 돌려주는 맵은 반복 순서를 보장하지 않아, 응답 원문을 그대로 다시 보여줄 때
     * 같은 응답이 매번 다른 순서로 보인다.
     */
    public ResponseContent {
        answers =
                answers == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }

    /*
     * 역직렬화 진입점. Jackson이 record의 정규 생성자를 property 모드로 쓰면 {"answers": ...}를
     * 기대하므로, DELEGATING으로 못 박아 JSON 객체 전체를 Map 하나로 받게 한다.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ResponseContent of(Map<String, Object> answers) {
        return new ResponseContent(answers);
    }

    /** 직렬화 진입점. record 껍데기 없이 Map 자체를 JSON 객체로 내보낸다 */
    @JsonValue
    public Map<String, Object> answers() {
        return answers;
    }

    /** 해당 문항에 답이 있는지. 빈 문자열·빈 배열은 답하지 않은 것으로 본다 */
    public boolean hasAnswer(String questionItemId) {
        Object value = answers.get(questionItemId);
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (value instanceof Iterable<?> values) {
            return values.iterator().hasNext();
        }
        return value != null;
    }
}
