package org.sscc.ssccopsserver.domain.academicprogram.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;

/*
 * 신청자 목록 DTO가 폼 응답 요약과 갈리지 않는지 (#198).
 *
 * 이 응답은 FormResponseSummaryResponse의 값을 그대로 옮기고 참가 상태만 얹은 모양이라,
 * 폼 쪽에 필드가 하나 늘면(실제로 #196이 responseTitle을 늘렸다) 여기에도 함께 늘어야 한다 —
 * 빠져도 컴파일은 통과하고 목록만 조용히 그 값을 잃는다. 폼 도메인의 DTO를 감싸지 않고
 * 평평하게 옮긴 대가라, 그 대가를 갚는 자리를 테스트로 둔다.
 *
 * 반대 방향(모집 전용 필드가 폼으로 새는 것)은 검사하지 않는다 — eventPtcpId·ptcpSttsCd는
 * 이 DTO에만 있어야 하는 값이고, 그것이 이 record를 따로 둔 이유다.
 */
class RecruitmentApplicationResponseTest {

    @Test
    void carriesEveryFieldOfTheFormResponseSummary() {
        Map<String, Class<?>> recruitment = componentsOf(RecruitmentApplicationResponse.class);

        for (RecordComponent component : FormResponseSummaryResponse.class.getRecordComponents()) {
            assertThat(recruitment)
                    .as(
                            "FormResponseSummaryResponse.%s가 신청자 목록에서 빠졌다"
                                    + " (RecruitmentApplicationResponse에 함께 더할 것)",
                            component.getName())
                    .containsEntry(component.getName(), component.getType());
        }
    }

    private static Map<String, Class<?>> componentsOf(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));
    }
}
