package org.sscc.ssccopsserver.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 채팅 클라이언트 자리의 스텁 (#403).
 *
 * <p><b>{@link AssistantStubConfig}와 나눠 둔다.</b> 저장소 스텁은 색인·코퍼스 테스트가 함께 쓰는데, 거기에 {@code
 * ChatClient}까지 넣으면 «키가 없는 서버에는 채팅 클라이언트 빈이 없다»를 지키는 {@code AssistantWiringTest}가 깨진다 — 그 사실이 규정
 * 도우미와 무관한 환경이 정상적으로 뜨는 근거다(#396).
 *
 * <p>운영에서 이 자리를 채우는 것은 {@code AssistantConfig.assistantChatClient}이고 그 빈은 Gemini 키가 있을 때만 선다.
 * {@code test} 프로필에는 경쟁할 빈이 없다 — {@code @Primary}가 필요 없는 이유다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AssistantChatStubConfig {

    @Bean
    StubChatModel stubChatModel() {
        return new StubChatModel();
    }

    @Bean
    ChatClient assistantChatClient(StubChatModel stubChatModel) {
        return ChatClient.builder(stubChatModel).build();
    }
}
