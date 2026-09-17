package org.sscc.ssccopsserver.global.mcp.client;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * 도구 출력에서 개인정보를 걷어낸다 (#385 · ADR-0027 · ADR-0024 «절대 넣지 않는 것»).
 *
 * 모델 컨텍스트로 나간 값은 회수가 안 된다. 그런데 응답 record는 도구 출력 타입으로 **그대로**
 * 쓰기로 했고(계약 불일치를 컴파일러가 잡게 하려고), `MemberSummaryResponse`는 담당자·등록자·
 * 발표자 자리마다 중첩되어 연락처·이메일을 싣는다. 도구마다 «출력용 뷰» record를 손으로 만들면
 * 응답 record에 필드가 늘 때 한쪽만 늘어 갈리고 — 무엇보다 새 도구가 뷰를 빠뜨리면 조용히
 * 새어 나간다.
 *
 * 그래서 **JSON 트리에서 이름으로 걷어낸다.** REST 응답을 `JsonNode`로 받아 아래 키를 깊이와
 * 무관하게 지운 뒤 원래 record 타입으로 되돌린다 — 지워진 필드는 null이 되어 도구 출력에는
 * `"phoneNumber":null`처럼 값 없는 키만 남는다(mcp-annotations 직렬화는 null을 빼지 않는다). 어느 도구가 어느 record를 돌려주든 같은 자리를 지나므로 빠뜨릴 도구가 없다.
 *
 * 걷는 것: `phoneNumber` · `email` · `studentNumber`. 이름(`name`)은 남긴다 — «담당자가 누구인가»
 * 없이는 운영 도구가 성립하지 않고, 이름은 운영진 안에서 공유되는 값이다. 학번은 ADR-0024가
 * 로그에서 금한 값이고 도구가 쓸 일이 없어 함께 걷었다. 키를 더할 때는 AGENTS.md «MCP» 절의
 * 표를 같이 고친다.
 */
@Component
public class ToolOutputRedactor {

    static final Set<String> REDACTED_FIELDS = Set.of("phoneNumber", "email", "studentNumber");

    /** 트리를 제자리에서 고친다. 돌려주는 것은 같은 노드다. */
    public JsonNode redact(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove(REDACTED_FIELDS);
            object.elements().forEachRemaining(this::redact);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(this::redact);
        }
        return node;
    }
}
