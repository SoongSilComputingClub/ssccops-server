package org.sscc.ssccopsserver.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * issuer·audience 검증(#383 · ADR-0026)을 **실제 NimbusJwtDecoder**에 끼워 본다.
 *
 * <p>네트워크 JWKS 대신 로컬에서 만든 EC(P-256) 키를 JWK 소스로 넣고 그 키로 서명한다 — SecurityConfig가 쓰는 것과 같은 검증기({@link
 * SupabaseJwtValidators#create})를 같은 종류의 디코더에 세팅하므로, 프로덕션 경로에서 갈리는 것은 키를 어디서 가져오느냐뿐이다. 통합 테스트의 스텁
 * {@code jwtDecoder}(ADR-0009)는 이 검증기를 지나지 않으므로 여기서만 검증할 수 있다.
 */
class SupabaseJwtValidatorsTest {

    private static final String ISSUER = "https://example.supabase.co/auth/v1";

    private static ECKey signingKey;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void setUpDecoder() throws Exception {
        signingKey = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
        // withJwkSetUri가 내부에서 만드는 것과 같은 프로세서 — 키 소스만 네트워크 대신 메모리다
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(
                        JWSAlgorithm.ES256,
                        new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()))));
        decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(SupabaseJwtValidators.create(ISSUER));
    }

    @Test
    @DisplayName("issuer·audience가 맞는 토큰은 통과한다")
    void acceptsMatchingIssuerAndAudience() throws Exception {
        String token = sign(ISSUER, List.of("authenticated"));

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).contains("authenticated");
    }

    @Test
    @DisplayName("issuer가 다르면 거부한다 — 서명이 맞아도")
    void rejectsDifferentIssuer() throws Exception {
        String token = sign("https://other.supabase.co/auth/v1", List.of("authenticated"));

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    @Test
    @DisplayName("aud가 없으면 거부한다")
    void rejectsMissingAudience() throws Exception {
        String token = sign(ISSUER, null);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("aud");
    }

    @Test
    @DisplayName("aud에 authenticated가 없으면 거부한다 — 다른 값만 있을 때")
    void rejectsAudienceWithoutAuthenticated() throws Exception {
        String token = sign(ISSUER, List.of("anon"));

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("aud");
    }

    private static String sign(String issuer, List<String> audience) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims =
                new JWTClaimsSet.Builder()
                        .subject(UUID.randomUUID().toString())
                        .issuer(issuer)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300)));
        if (audience != null) {
            claims.audience(audience);
        }
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(signingKey.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        jwt.sign(new ECDSASigner(signingKey));
        return jwt.serialize();
    }
}
