package org.sscc.ssccopsserver.global.mcp.tool.patch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSaveRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * patch record가 컨트롤러의 Update record와 갈리지 않는지 본다 (ssccops#365 · ADR-0027).
 *
 * 도구 입력 타입은 원래 컨트롤러 record 그대로 쓴다. `WorkPatch`·`SubWorkPatch`는 «안 바꿈»을
 * 표현해야 해서(Update record는 제목·담당자가 `@NotNull`이다) 어쩔 수 없이 따로 둔 것이라,
 * **필드가 1:1로 맞는지를 반사로 검사해** 컨트롤러 record가 자랄 때 조용히 빠지지 않게 한다.
 *
 * 이름을 그대로 맞추지 않은 자리가 하나 있다 — 업무의 `review`는 상세 응답에서 `generalReview`다.
 * 그쪽은 merge 코드가 들고 있고 이 테스트는 **요청 record와의 대응**만 본다.
 */
class McpPatchContractTest {

    @Test
    @DisplayName("WorkPatch는 WorkUpdateRequest와 같은 필드 이름·타입을 가진다")
    void workPatchMirrorsUpdateRequest() {
        assertThat(componentsOf(WorkPatch.class))
                .containsExactlyInAnyOrderElementsOf(componentsOf(WorkUpdateRequest.class));
    }

    @Test
    @DisplayName("SubWorkPatch는 SubWorkUpdateRequest와 같은 필드 이름·타입을 가진다")
    void subWorkPatchMirrorsUpdateRequest() {
        assertThat(componentsOf(SubWorkPatch.class))
                .containsExactlyInAnyOrderElementsOf(componentsOf(SubWorkUpdateRequest.class));
    }

    /*
     * 비워 둔 필드는 현재 값을 유지한다 — 이 성질이 깨지면 부분 호출이 다른 값을 지운다(F2).
     * 상세 응답을 만들 수 없는 테스트라(엔티티가 필요하다) 여기서는 merge를 부르지 않고,
     * 실제 동작은 `WorkToolsIntegrationTest`가 REST 왕복으로 본다.
     */
    @Test
    @DisplayName("ContentPagePatch·ContentPostPatch는 저장 요청 record와 같은 필드 이름·타입을 가진다")
    void contentPatchesMirrorSaveRequests() {
        assertThat(componentsOf(ContentPagePatch.class))
                .containsExactlyInAnyOrderElementsOf(componentsOf(ContentPageSaveRequest.class));
        assertThat(componentsOf(ContentPostPatch.class))
                .containsExactlyInAnyOrderElementsOf(componentsOf(ContentPostSaveRequest.class));
    }

    @Test
    @DisplayName("patch record의 모든 필드는 nullable하다 — 원시 타입이 섞이면 «안 바꿈»을 표현할 수 없다")
    void everyPatchComponentIsNullable() {
        assertThat(rawTypes(WorkPatch.class)).noneMatch(Class::isPrimitive);
        assertThat(rawTypes(SubWorkPatch.class)).noneMatch(Class::isPrimitive);
        assertThat(rawTypes(ContentPagePatch.class)).noneMatch(Class::isPrimitive);
        assertThat(rawTypes(ContentPostPatch.class)).noneMatch(Class::isPrimitive);
    }

    @Test
    @DisplayName("patch는 상태·유형을 받지 않는다 — 전이 API가 따로 있다")
    void patchDoesNotCarryStateOrType() {
        List<String> names =
                Arrays.stream(SubWorkPatch.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();
        assertThat(names).doesNotContain("workStatus", "approvalStatus", "subWorkTypeId");
        // 업무 쪽은 itemType(업무 유형)을 받는다 — 하위 업무 유형과 달리 소급 규칙이 없다
        assertThat(componentsOf(WorkPatch.class)).contains("itemType:" + WorkType.class.getName());
        assertThat(componentsOf(WorkPatch.class))
                .contains("priority:" + OperationPriority.class.getName());
        assertThat(componentsOf(WorkPatch.class))
                .contains("startAt:" + OffsetDateTime.class.getName());
    }

    private static List<String> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(c -> c.getName() + ":" + c.getType().getName())
                .sorted()
                .toList();
    }

    private static List<Class<?>> rawTypes(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getType)
                .collect(java.util.stream.Collectors.toList());
    }
}
