package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 기능 플래그 (#396 · #404에서 옮겨 왔다).
 *
 * **기본값이 꺼짐이라는 사실이 이 클래스의 전부다.** 머지가 곧 dev 배포라(#202) 색인
 * 워커(#400)가 스위치 없이 먼저 뜨면 `PENDING` 행 하나로 무료 임베딩 쿼터를 태우기 시작하고,
 * 끄는 방법이 revert뿐이다. 기본값이 바뀌면 여기서 잡힌다.
 *
 * 스프링 컨텍스트를 띄우지 않는다 — 값은 생성자 인자 하나이고, 설정 파일이 아니라 배포 환경변수
 * (`SSCCOPS_ASSISTANT_ENABLED`)가 그것을 준다.
 */
class AssistantFeatureTest {

    @Test
    void offByDefaultRejectsWithItsOwnCode() {
        AssistantFeature feature = new AssistantFeature(false);

        assertThat(feature.isEnabled()).isFalse();
        assertThatThrownBy(feature::requireEnabled)
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .as("없는 자원의 404와 코드를 나눠야 화면이 «문서가 사라졌다»로 읽지 않는다")
                .isEqualTo(AssistantErrorCode.ASSISTANT_DISABLED);
    }

    @Test
    void enabledLetsCallersThrough() {
        AssistantFeature feature = new AssistantFeature(true);

        assertThat(feature.isEnabled()).isTrue();
        assertThatCode(feature::requireEnabled).doesNotThrowAnyException();
    }
}
