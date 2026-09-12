package org.sscc.ssccopsserver.global.mcp.client;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.common.McpTransportContext;

/*
 * 도구가 자기 REST(`http://localhost:${PORT}/v1/…`)를 부르는 유일한 자리 (#385 · ADR-0027).
 *
 * 도구는 서비스를 직접 부르지 않는다 — 인가(`@RequireAuthority`)·검증(`@Valid`)·미가입 차단
 * (`@CurrentMember`)·감사가 전부 컨트롤러 계층에 있어, 그 문을 지나지 않으면 빠뜨린 도구가 조용히
 * 열린 채 배포된다. 한 홉이 더 드는 것이 그 대가다.
 *
 * 여기서 한 번에 하는 것 셋:
 * - **Bearer pass-through** — 요청의 토큰을 그대로 싣는다(`BearerTokenSource`). 신원이 곧 사용자다.
 * - **봉투 벗기기** — `ApiResponse{success,code,message,data,page}`에서 `data`만 돌려주고
 *   `success:false`면 `code`+`message`를 도구 오류로 옮긴다. 도구마다 봉투를 열면 오류 변환이
 *   자리마다 갈린다(분석 문서 F6).
 * - **개인정보 걷어내기** — `ToolOutputRedactor`가 `data` 트리를 지나간 뒤에야 record로 굳힌다.
 *
 * 포트는 `local.server.port`(웹 서버가 뜬 뒤 스프링이 넣는 실제 값 — 테스트의 RANDOM_PORT도 이것)
 * 이고 없으면 `server.port`(= `PORT`)다. **8080을 가정하지 않는다** — Coolify가 dev 80 · prod 3000을
 * 넣는다. 타임아웃은 연결 2s · 읽기 10s — Rate limit이 없는 서버라(F7) 도구 루프가 API를 붙들고
 * 있는 시간을 여기서 자른다. 상태 코드로 던지지 않고 `exchange`로 본문을 직접 읽는 것은 4xx에도
 * ApiResponse 본문이 실려 오고 그 `code`가 모델에게 줄 유일한 정보이기 때문이다.
 */
@Component
public class McpRestClient {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** 목록 도구가 커서를 따라가는 상한. 도구 설명에도 같은 숫자가 적혀 있다. */
    public static final int MAX_PAGES = 3;

    private static final String CURSOR_PARAM = "cursor";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final BearerTokenSource tokenSource;
    private final ToolOutputRedactor redactor;
    private final Supplier<String> baseUrl;

    @Autowired
    public McpRestClient(
            ObjectMapper objectMapper,
            BearerTokenSource tokenSource,
            ToolOutputRedactor redactor,
            Environment environment) {
        this(
                objectMapper,
                tokenSource,
                redactor,
                () -> resolveBaseUrl(environment),
                CONNECT_TIMEOUT,
                READ_TIMEOUT);
    }

    /** 테스트용 — 주소와 타임아웃을 직접 준다. */
    McpRestClient(
            ObjectMapper objectMapper,
            BearerTokenSource tokenSource,
            ToolOutputRedactor redactor,
            Supplier<String> baseUrl,
            Duration connectTimeout,
            Duration readTimeout) {
        this.objectMapper = objectMapper;
        this.tokenSource = tokenSource;
        this.redactor = redactor;
        this.baseUrl = baseUrl;
        this.restClient =
                RestClient.builder()
                        .requestFactory(
                                ClientHttpRequestFactoryBuilder.jdk()
                                        .build(
                                                ClientHttpRequestFactorySettings.defaults()
                                                        .withConnectTimeout(connectTimeout)
                                                        .withReadTimeout(readTimeout)))
                        .build();
    }

    /** 단건 GET — `data`를 `type`으로 돌려준다. */
    public <T> T get(McpTransportContext context, String path, Class<T> type) {
        return exchange(HttpMethod.GET, path, Map.of(), null, context, type).data();
    }

    /**
     * 목록 GET — 커서 페이징을 {@value #MAX_PAGES}페이지까지 따라가 한 목록으로 합친다.
     *
     * @param condition 컨트롤러가 받는 검색 조건 record 그대로. 필드 이름이 곧 쿼리 파라미터 이름이다
     */
    public <T> McpListResult<T> getList(
            McpTransportContext context, String path, Object condition, Class<T> elementType) {
        Map<String, Object> query = toQuery(condition);
        JavaType listType =
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
        List<T> items = new ArrayList<>();
        int pages = 0;
        PageResponse page = null;
        while (true) {
            Envelope<List<T>> envelope =
                    exchange(HttpMethod.GET, path, query, null, context, listType);
            pages++;
            if (envelope.data() != null) {
                items.addAll(envelope.data());
            }
            page = envelope.page();
            if (page == null
                    || !page.hasNext()
                    || page.nextCursor() == null
                    || pages >= MAX_PAGES) {
                break;
            }
            query.put(CURSOR_PARAM, page.nextCursor());
        }
        boolean hasMore = page != null && page.hasNext();
        return new McpListResult<>(
                items,
                pages,
                hasMore,
                hasMore ? page.nextCursor() : null,
                page == null ? items.size() : page.totalCount());
    }

    public <T> T post(McpTransportContext context, String path, Object body, Class<T> type) {
        return exchange(HttpMethod.POST, path, Map.of(), body, context, type).data();
    }

    public <T> T patch(McpTransportContext context, String path, Object body, Class<T> type) {
        return exchange(HttpMethod.PATCH, path, Map.of(), body, context, type).data();
    }

    /** `data`와 `page`를 함께 든 응답. */
    public record Envelope<T>(T data, PageResponse page) {}

    private <T> Envelope<T> exchange(
            HttpMethod method,
            String path,
            Map<String, Object> query,
            Object body,
            McpTransportContext context,
            Class<T> type) {
        return exchange(method, path, query, body, context, objectMapper.constructType(type));
    }

    private <T> Envelope<T> exchange(
            HttpMethod method,
            String path,
            Map<String, Object> query,
            Object body,
            McpTransportContext context,
            JavaType type) {
        String authorization = tokenSource.authorizationHeader(context);
        UriTemplate template = UriTemplate.of(baseUrl.get() + path, query);
        try {
            RestClient.RequestBodySpec request =
                    restClient
                            .method(method)
                            .uri(template.template(), template.variables())
                            .header(HttpHeaders.AUTHORIZATION, authorization)
                            .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((req, response) -> unwrap(response, type));
        } catch (ResourceAccessException ex) {
            // 연결·읽기 타임아웃. 원인은 서버 로그에 있고 모델에게는 «지연»만 전한다
            throw new McpToolException("UPSTREAM_TIMEOUT", "서버 응답이 지연됩니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    private <T> Envelope<T> unwrap(ClientHttpResponse response, JavaType type) throws IOException {
        JsonNode root;
        byte[] bytes = response.getBody().readAllBytes();
        if (bytes.length == 0) {
            throw new McpToolException(
                    "EMPTY_RESPONSE", "서버가 빈 응답을 돌려줬습니다 (HTTP " + response.getStatusCode() + ").");
        }
        root = objectMapper.readTree(bytes);
        if (!root.path("success").asBoolean(false)) {
            throw toToolException(root.path("code").asText(""), root.path("message").asText(""));
        }
        JsonNode data = root.get("data");
        T value =
                data == null || data.isNull()
                        ? null
                        : objectMapper.convertValue(redactor.redact(data), type);
        JsonNode page = root.get("page");
        PageResponse pageResponse =
                page == null || page.isNull()
                        ? null
                        : objectMapper.convertValue(page, PageResponse.class);
        return new Envelope<>(value, pageResponse);
    }

    /*
     * 403 두 종류는 «재시도해도 바뀌지 않는다»를 문장에 박는다 — 세 층의 인가가 전부 FORBIDDEN 하나라
     * (#118) 모델에게 줄 수 있는 것이 «권한 없음»뿐이고, 그 말이 없으면 인자를 바꿔 가며 같은 호출을
     * 되풀이한다. 나머지는 code와 message를 그대로 — 400의 message는 어느 필드가 틀렸는지를 담고 있어
     * 모델이 고쳐 부를 수 있는 정보다.
     */
    private static McpToolException toToolException(String code, String message) {
        return switch (code) {
            case "FORBIDDEN", "COMMON403" ->
                    new McpToolException(code, "권한이 없습니다. 재시도해도 결과는 같습니다 — 사용자에게 알리고 멈추세요.");
            case "SIGNUP_REQUIRED" ->
                    new McpToolException(
                            code,
                            "가입이 필요합니다. 운영 웹에서 가입(또는 명부 계정 연결)을 마친 뒤 다시 연결해야"
                                    + " 합니다 — 재시도해도 결과는 같습니다.");
            case "COMMON401" ->
                    new McpToolException(
                            code, CommonErrorCode.UNAUTHORIZED.getMessage() + " 연결을 다시 맺어 주세요.");
            default -> new McpToolException(code, "[" + code + "] " + message);
        };
    }

    /*
     * 검색 조건 record → 쿼리 파라미터. 필드 이름이 곧 파라미터 이름이라(컨트롤러의 @ModelAttribute와
     * 같은 규칙) 도구 입력과 REST 계약이 어긋날 자리가 없다. null은 빼고, 컬렉션은 같은 이름을 반복한다.
     * OffsetDateTime은 Boot의 ObjectMapper가 ISO 문자열로 내므로 @DateTimeFormat(ISO)와 맞는다.
     */
    private Map<String, Object> toQuery(Object condition) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (condition == null) {
            return query;
        }
        Map<String, Object> fields =
                objectMapper.convertValue(condition, new TypeReference<Map<String, Object>>() {});
        fields.forEach(
                (name, value) -> {
                    if (value != null) {
                        query.put(name, value);
                    }
                });
        return query;
    }

    private static String resolveBaseUrl(Environment environment) {
        String port = environment.getProperty("local.server.port");
        if (port == null || port.isBlank()) {
            port = environment.getProperty("server.port");
        }
        if (port == null || port.isBlank()) {
            throw new IllegalStateException(
                    "자기 호출 포트를 알 수 없습니다 — local.server.port도 server.port도 없습니다.");
        }
        return "http://localhost:" + port;
    }

    /*
     * 쿼리를 URI 템플릿 + 변수로 만든다. 값을 문자열로 이어 붙이면 `+09:00`의 `+`가 서버에서 공백으로
     * 읽힌다 — RestClient의 템플릿 확장(TEMPLATE_AND_VALUES)이 변수를 엄격히 인코딩한다.
     */
    record UriTemplate(String template, Map<String, Object> variables) {

        static UriTemplate of(String base, Map<String, Object> query) {
            StringBuilder template = new StringBuilder(base);
            Map<String, Object> variables = new LinkedHashMap<>();
            String separator = base.contains("?") ? "&" : "?";
            int index = 0;
            for (Map.Entry<String, Object> entry : query.entrySet()) {
                Collection<?> values =
                        entry.getValue() instanceof Collection<?> collection
                                ? collection
                                : List.of(entry.getValue());
                for (Object value : values) {
                    if (value == null) {
                        continue;
                    }
                    String variable = "q" + index++;
                    template.append(separator)
                            .append(entry.getKey())
                            .append("={")
                            .append(variable)
                            .append('}');
                    variables.put(variable, value);
                    separator = "&";
                }
            }
            return new UriTemplate(template.toString(), variables);
        }
    }
}
