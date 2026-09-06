package org.sscc.ssccopsserver.domain.operation.service;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/*
 * 공유 토큰을 만드는 유일한 자리 (ssccops#200 · ADR-0016).
 *
 * **이 클래스가 예측 가능한 값을 내면 기능 전체의 근거가 무너진다.** 공개 메타
 * API(/public/v1/sub-works/{id}/meta)를 기각한 이유가 "식별자가 연속 정수라 1부터 훑으면
 * 업무 제목이 전부 수집된다"였고, 토큰은 그 공격면 자체를 없애려고 있는 것이다. 그러므로
 * java.util.Random이 아니라 SecureRandom이며, 그 차이가 이 클래스의 존재 이유다.
 *
 * 256비트를 URL-safe Base64(패딩 없음)로 낸다 — 43자다. URL에 그대로 박히므로 인코딩이
 * 필요한 문자가 나오면 안 되고(그래서 표준 Base64가 아니다), 사람이 옮겨 적는 값이 아니라
 * 짧게 만들 이유도 없다. 만료가 없어 한 번 나간 토큰이 영영 유효하다는 것(ADR-0016)이
 * 길이를 넉넉히 두는 근거다.
 */
@Component
public class ShareTokenGenerator {

    /** 256비트. URL-safe Base64로 43자가 되며 컬럼 길이(64)에 여유를 두고 들어간다 */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
