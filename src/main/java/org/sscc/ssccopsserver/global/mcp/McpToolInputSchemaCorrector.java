package org.sscc.ssccopsserver.global.mcp;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/*
 * 도구 입력 스키마의 `required` 를 **서버가 실제로 강제하는 것**으로 고쳐 쓴다 (#591 · ssccops#365).
 *
 * ── 무엇이 고장이었나 ─────────────────────────────────────────
 *
 * `mcp-annotations` 가 스키마를 만들 때 **중첩 객체의 모든 필드를 required 로 박는다.**
 * `SpringAiSchemaModule` 을 `PROPERTY_REQUIRED_FALSE_BY_DEFAULT` 없이 만들어 `requiredByDefault`
 * 가 true 이기 때문이고, 그 모듈이 든 `SchemaGenerator` 는 `JsonSchemaGenerator` 의
 * **`static final`** 이라 밖에서 설정할 수 없다.
 *
 * 2026-09-27 실측: 도구 입력 record 46종의 필드 171개 중 **서버가 요구하는 것은 40개인데 스키마는
 * 171개 전부를 required 라고 말했다.** 스키마 검증은 클라이언트가 하므로 결과는 이렇게 갈린다.
 *
 * | 도구 | 무슨 일이 났나 |
 * |---|---|
 * | `add_meeting_agenda` | **부를 수 있는 방법이 없었다** — 안건명·연결 운영 건이 «둘 중 하나»인데 스키마가 둘 다 요구한다.
 * |   | 하나만 보내면 클라이언트가 막고, 둘 다 보내면 서버가 400 으로 거절한다 |
 * | `update_*`(읽고-합치기 5종) | «바꿀 것만 준다»가 성립하지 않는다 — 전 필드를 보내야 호출이 나간다 |
 * | `list_*`(검색 조건) | 필터 하나만 걸 수 없다 — `list_sub_works` 는 조건 11개를 다 요구했다 |
 *
 * ── 왜 애노테이션이 아니라 여기인가 ───────────────────────────
 *
 * 필드마다 `@Schema(requiredMode = NOT_REQUIRED)` 를 붙이면 막을 수 있다(그 모듈이 읽는다).
 * 택하지 않은 이유 둘: **131개**를 붙여야 하고, **새 필드의 기본이 여전히 «필수»**라 잊는 순간
 * 조용히 돌아온다 — 그것이 이 결함이 배포된 경로다. 여기서 고치면 출처가 하나다: 서버의
 * `@Valid` 가 보는 바로 그 애노테이션(`@NotNull`·`@NotBlank`·`@NotEmpty`)이 스키마의 `required`
 * 가 된다. 필드를 더해도 자동으로 맞고, 어긋나면 `McpToolSchemaContractTest` 가 깨진다.
 *
 * **상위 인자(`meetingId`·`request`)는 손대지 않는다** — 그쪽은 `@McpToolParam(required)` 가
 * 이미 정하고 실측에서도 맞았다. 고치는 것은 **중첩 객체와 배열 요소**뿐이다.
 *
 * ⚠️ 라이브러리가 만든 빈을 고쳐 쓰는 자리다. 업그레이드로 스키마 모양이나 기본값이 바뀌면
 * 계약 테스트가 먼저 깨지게 해 두었다 — 그때는 이 클래스가 필요 없어졌는지부터 본다.
 */
@Component
public class McpToolInputSchemaCorrector implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(McpToolInputSchemaCorrector.class);

    /** 도구가 사는 곳. 여기 밖에 `@McpTool` 을 두면 `McpToolSchemaContractTest` 가 잡는다. */
    static final String TOOL_PACKAGE = "org.sscc.ssccopsserver.global.mcp.tool";

    /** 서버가 «비어 있으면 거절한다»고 말하는 애노테이션. 이름으로 보는 것은 패키지가 갈려서다. */
    private static final Set<String> REQUIRED_MARKERS = Set.of("NotNull", "NotBlank", "NotEmpty");

    private Map<String, Method> toolMethods;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof List<?> list)) {
            return bean;
        }
        if (list.isEmpty()
                || !(list.get(0) instanceof McpStatelessServerFeatures.SyncToolSpecification)) {
            return bean;
        }
        List<McpStatelessServerFeatures.SyncToolSpecification> corrected = new ArrayList<>();
        int touched = 0;
        for (Object o : list) {
            McpStatelessServerFeatures.SyncToolSpecification spec =
                    (McpStatelessServerFeatures.SyncToolSpecification) o;
            McpStatelessServerFeatures.SyncToolSpecification fixed = correct(spec);
            if (fixed != spec) {
                touched++;
            }
            corrected.add(fixed);
        }
        log.info("mcp 입력 스키마 required 를 서버 계약으로 맞췄다 — 도구 {}종 중 {}종", list.size(), touched);
        return corrected;
    }

    private McpStatelessServerFeatures.SyncToolSpecification correct(
            McpStatelessServerFeatures.SyncToolSpecification spec) {
        McpSchema.Tool tool = spec.tool();
        Method method = toolMethods().get(tool.name());
        if (method == null || tool.inputSchema() == null) {
            return spec;
        }
        Map<String, Type> paramTypes = new LinkedHashMap<>();
        for (Parameter p : method.getParameters()) {
            paramTypes.put(p.getName(), p.getParameterizedType());
        }

        Map<String, Object> properties = tool.inputSchema().properties();
        if (properties == null || properties.isEmpty()) {
            return spec;
        }
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            Type type = paramTypes.get(e.getKey());
            Object before = e.getValue();
            Object after = type == null ? before : correctNode(before, type);
            changed |= after != before;
            rebuilt.put(e.getKey(), after);
        }
        if (!changed) {
            return spec;
        }
        McpSchema.JsonSchema in = tool.inputSchema();
        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema(
                        in.type(),
                        rebuilt,
                        in.required(),
                        in.additionalProperties(),
                        in.defs(),
                        in.definitions());
        return new McpStatelessServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        tool.name(),
                        tool.title(),
                        tool.description(),
                        schema,
                        tool.outputSchema(),
                        tool.annotations(),
                        tool.meta()),
                spec.callHandler());
    }

    /** 스키마 조각 하나를 그 자바 타입에 맞춰 고친다. 객체는 required 를 다시 쓰고 배열은 items 로 내려간다. */
    @SuppressWarnings("unchecked")
    private Object correctNode(Object node, Type type) {
        if (!(node instanceof Map<?, ?> raw)) {
            return node;
        }
        Map<String, Object> schema = (Map<String, Object>) raw;
        Class<?> clazz = rawClass(type);

        if (Collection.class.isAssignableFrom(clazz)) {
            Object items = schema.get("items");
            Type element = elementType(type);
            if (items == null || element == null) {
                return node;
            }
            Object fixedItems = correctNode(items, element);
            if (fixedItems == items) {
                return node;
            }
            Map<String, Object> copy = new LinkedHashMap<>(schema);
            copy.put("items", fixedItems);
            return copy;
        }

        if (!clazz.isRecord()) {
            return node;
        }
        Object propsNode = schema.get("properties");
        if (!(propsNode instanceof Map<?, ?> propsRaw)) {
            return node;
        }
        Map<String, Object> props = (Map<String, Object>) propsRaw;
        Map<String, Type> componentTypes = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        for (RecordComponent c : clazz.getRecordComponents()) {
            componentTypes.put(c.getName(), c.getGenericType());
            if (requiredByServer(c)) {
                required.add(c.getName());
            }
        }

        Map<String, Object> rebuiltProps = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Type componentType = componentTypes.get(e.getKey());
            rebuiltProps.put(
                    e.getKey(),
                    componentType == null
                            ? e.getValue()
                            : correctNode(e.getValue(), componentType));
        }

        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put("properties", rebuiltProps);
        if (required.isEmpty()) {
            copy.remove("required");
        } else {
            copy.put("required", List.copyOf(required));
        }
        return copy;
    }

    /**
     * 서버가 이 필드를 요구하는가 — record component · 필드 · 접근자에 붙은 애노테이션을 다 본다.
     *
     * <p>record 는 애노테이션이 `@Target` 에 따라 셋 중 어디로든 갈 수 있어 한 자리만 보면 놓친다.
     */
    private boolean requiredByServer(RecordComponent component) {
        List<Annotation> all = new ArrayList<>(List.of(component.getAnnotations()));
        all.addAll(List.of(component.getAccessor().getAnnotations()));
        try {
            Field field = component.getDeclaringRecord().getDeclaredField(component.getName());
            all.addAll(List.of(field.getAnnotations()));
        } catch (NoSuchFieldException ignored) {
            // record 라면 있을 수 없다 — 없으면 나머지 둘로 판단한다
        }
        return all.stream()
                .anyMatch(a -> REQUIRED_MARKERS.contains(a.annotationType().getSimpleName()));
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }

    private static Type elementType(Type type) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length == 1) {
            return p.getActualTypeArguments()[0];
        }
        return null;
    }

    /** 도구 이름 → 메서드. 빈을 만들지 않고 클래스패스만 훑는다(BeanPostProcessor 라 이른 시점이다). */
    private Map<String, Method> toolMethods() {
        if (toolMethods == null) {
            Map<String, Method> found = new HashMap<>();
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
            scanner.findCandidateComponents(TOOL_PACKAGE)
                    .forEach(
                            definition -> {
                                Class<?> clazz =
                                        ClassUtils.resolveClassName(
                                                definition.getBeanClassName(), null);
                                for (Method m : clazz.getDeclaredMethods()) {
                                    McpTool annotation = m.getAnnotation(McpTool.class);
                                    if (annotation != null) {
                                        found.put(annotation.name(), m);
                                    }
                                }
                            });
            toolMethods = Map.copyOf(found);
        }
        return toolMethods;
    }
}
