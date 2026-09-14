package org.sscc.ssccopsserver.domain.assistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.extern.slf4j.Slf4j;

/*
 * 규정 도우미 기능 플래그 — `ssccops.assistant.enabled`, 기본 **false** (#396 · #404에서 옮겨 왔다).
 *
 * ── 왜 배선(#396)과 함께 서는가 ────────────────────────────────
 *
 * 원래 레이트 리밋 이슈(#404)의 항목이었는데 순서상 그쪽이 마지막이다. **머지가 곧 dev
 * 배포이므로**(Coolify가 develop 푸시를 자동 배포한다, #202) 플래그가 거기 있으면 색인
 * 워커(#400)가 **스위치 없이 dev에 먼저 뜬다.** 그 시점에 `PENDING` 행이 하나라도 있으면 무료
 * 임베딩 쿼터를 태우기 시작하는데, 끄는 방법이 revert뿐이다.
 *
 * ── 무엇을 닫는가 ────────────────────────────────────────────
 *
 * **질의·적재·워커를 함께 닫는다.** 질의만 닫으면 워커가 계속 임베딩을 부르는데, 끄는 이유가
 * 대개 쿼터다. 지금은 그 세 경로가 아직 없으므로 이 클래스를 부르는 곳도 없다 — 스위치가 먼저
 * 서 있어야 그것들이 «꺼진 채로» 들어올 수 있고, 그것이 이 이슈로 당겨 온 이유다.
 *
 * ── 왜 부팅을 세우지 않는가 ───────────────────────────────────
 *
 * 값이 없는 것이 정상 상태다 — `AppPublicBaseUrl`(#216)이 «비어 있을 정당한 이유가 어느 환경에도
 * 없다»로 부팅을 세운 것과 갈리는 지점이다. 설정 파일(`application-dev.yaml`·`-prod.yaml`)에
 * 이 키를 두지 않는 것은 회원 하드 삭제(#361)와 같은 판단이며, 켜는 곳은 Coolify 대시보드의
 * 환경변수 `SSCCOPS_ASSISTANT_ENABLED=true` 하나다(#202 — 환경변수의 정본은 Coolify다).
 *
 * **Gemini 키가 없는 것과는 다른 상태다.** 그쪽은 켜 두고 설정이 덜 된 것이라 503
 * (`ASSISTANT_UNAVAILABLE`)이고, 이쪽은 «이 서버에 규정 도우미가 없다»라 404다.
 */
@Slf4j
@Component
public class AssistantFeature {

    private final boolean enabled;

    public AssistantFeature(@Value("${ssccops.assistant.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        log.info("규정 도우미(RAG) 기능 플래그: {}", enabled ? "켜짐" : "꺼짐");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 꺼져 있으면 404 {@code ASSISTANT_DISABLED}로 끊는다.
     *
     * <p><b>없는 자원의 404와 코드를 나눈 이유</b>는 회원 하드 삭제의 {@code FEATURE_DISABLED}와 같다(#361) — 웹의 플래그와 서버의
     * 플래그가 갈렸을 때 이것이 그냥 {@code NOT_FOUND}로 오면 화면은 «문서가 사라졌다»로 읽는다.
     */
    public void requireEnabled() {
        if (!enabled) {
            throw new GeneralException(AssistantErrorCode.ASSISTANT_DISABLED);
        }
    }
}
