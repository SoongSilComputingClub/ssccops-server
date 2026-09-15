package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.google.genai.Client;

/*
 * Gemini 호출에 시간 상한을 거는 빈이 **키가 있을 때만 서는가** (#403).
 *
 * **이 빈이 있어야 하는 이유는 SDK의 기본값이 「무한」이기 때문이다** — google-genai 1.37.0이
 * OkHttp에 `connectTimeout(0)`을 걸고 read·write도 같다. 상한이 없으면 질의 한 건이 톰캣 요청
 * 스레드를 영영 붙들 수 있고, 스타터에는 그 값을 줄 프로퍼티가 없다.
 *
 * 값 자체(20초)는 SDK가 OkHttp의 `callTimeout`으로 감춰 두어 밖에서 되읽을 수 없다. 그래서 여기서
 * 보는 것은 **조건**이다 — 키가 없는 환경(테스트·기여자 로컬·아직 키를 넣지 않은 배포)에서 이
 * 빈이 서면 키 없는 클라이언트를 만들려다 부팅이 깨진다.
 */
class GeminiClientConfigTest {

    /*
     * 변환 서비스를 손으로 붙인다 — 문자열 → `Duration` 변환은 `SpringApplication`이 등록하는
     * `ApplicationConversionService`가 해 주는데 이 러너에는 그것이 없다. 실제 부팅에는 있으므로
     * 이것은 테스트 하네스의 차이이지 설정의 제약이 아니다(`ssccops.assistant.indexing.poll-interval`
     * 도 같은 모양이다).
     *
     * ⚠️ **상한 값도 손으로 준다** (#439). 기본값이 `application.yaml`로 옮겨 가며 `@Value`에서
     * 빠졌는데 이 러너는 그 파일을 읽지 않는다 — 같은 하네스 차이다. 운영 기본값(PT20S)과 다른
     * 값을 주는 것은 이 테스트가 보는 것이 **조건**이지 값이 아니기 때문이며(값 자체는 SDK가
     * OkHttp의 `callTimeout`으로 감춰 두어 밖에서 되읽을 수도 없다), 같은 숫자를 적으면 기본값이
     * 여기에도 한 벌 생긴다.
     */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(
                            context ->
                                    context.getBeanFactory()
                                            .setConversionService(
                                                    ApplicationConversionService
                                                            .getSharedInstance()))
                    .withPropertyValues("ssccops.assistant.gemini.call-timeout=PT7S")
                    .withUserConfiguration(GeminiClientConfig.class);

    /* 키가 있는 환경 — `spring.ai.model.chat`이 아예 없다(EPP가 아무것도 넣지 않았다) */
    @Test
    void wiresTheClientWhenTheChatModelIsOn() {
        runner.withPropertyValues("spring.ai.google.genai.api-key=test-key")
                .run(context -> assertThat(context).hasSingleBean(Client.class));
    }

    /* 키가 없는 환경 — EPP가 `none`을 넣어 두었다. 여기서 빈이 서면 키 없이 만들어지려다 깨진다 */
    @Test
    void wiresNothingWhenTheChatModelIsOff() {
        runner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> assertThat(context).doesNotHaveBean(Client.class));
    }
}
