package org.sscc.ssccopsserver.global.security.handler;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

/*
 * 401·403 로그 한 줄에 싣는 요청 컨텍스트 (#389 · ssccops#319 · ADR-0024).
 *
 * `Authentication failed: …` 한 문장만으로는 `/mcp` 발견 프로브·만료된 웹 토큰·스캐너를 가를 수 없어
 * ECS 필드로 **`url.path` · `http.request.method` · `user_agent.original`** 셋을 싣는다. 객체 모양은
 * `AuditLog.write`가 `kv("user", map)`으로 하는 것과 같다 — `EcsJsonEncoder`가 그 Map을 중첩 JSON으로
 * 내고, Logstash·Kibana는 중첩 키만 찾는다(`url.path`라는 평면 키를 만들면 별개 필드가 된다).
 *
 * **싣지 않는 것** — 쿼리 문자열(`getRequestURI()`에는 애초에 없다)·`Authorization` 값·`source.ip`.
 * 토큰이 로그에 남으면 로그 열람 권한이 곧 인증 권한이 되고, IP는 감사 로그에만 둔다(ADR-0024
 * «개인정보 넣지 않는 것»과 같은 축 — 경로·UA로 충분하다). User-Agent가 없으면 `user_agent` 객체
 * 자체를 내지 않는다(빈 문자열을 지어내면 Kibana에서 «UA 없음»을 셀 수 없다).
 */
final class RequestLogFields {

    private RequestLogFields() {}

    /*
     * `{}` 자리에 들어갈 인자 뒤에 StructuredArguments를 이은 배열. 배열 하나로 넘겨야 SLF4J가
     * `warn(String, Object...)`를 고른다 — `warn(fmt, msg, fields)`처럼 두 인자로 부르면 고정 인자
     * 오버로드가 잡혀 배열이 통째로 하나의 인자가 되고 인코더가 kv를 보지 못한다.
     */
    static Object[] args(Object message, HttpServletRequest request) {
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("path", request.getRequestURI());

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("request", Map.of("method", String.valueOf(request.getMethod())));

        List<Object> args = new ArrayList<>();
        args.add(message);
        args.add(kv("url", url));
        args.add(kv("http", http));
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null && !userAgent.isBlank()) {
            args.add(kv("user_agent", Map.of("original", userAgent)));
        }
        return args.toArray();
    }
}
