package org.sscc.ssccopsserver.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

@ExtendWith(MockitoExtension.class)
class SupabaseJwtAuthenticationConverterTest {

    @Mock private MemberService memberService;

    @InjectMocks private SupabaseJwtAuthenticationConverter converter;

    @Test
    void signedUpUserCarriesLinkedMember() {
        UUID authUserId = UUID.randomUUID();
        MemberEntity member = Mockito.mock(MemberEntity.class);
        given(memberService.findByAuthUserId(authUserId)).willReturn(Optional.of(member));

        var token = converter.convert(jwt(authUserId.toString()));

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getAuthorities()).isEmpty();
        assertThat(token.getName()).isEqualTo(authUserId.toString());

        AuthenticatedUser principal = (AuthenticatedUser) token.getPrincipal();
        assertThat(principal.isSignedUp()).isTrue();
        assertThat(principal.member()).isEqualTo(member);
    }

    // 로그인만 하고 아직 가입하지 않은 사용자도 인증 자체는 성립해야 한다
    @Test
    void unregisteredUserIsAuthenticatedWithoutMember() {
        UUID authUserId = UUID.randomUUID();
        given(memberService.findByAuthUserId(authUserId)).willReturn(Optional.empty());

        var token = converter.convert(jwt(authUserId.toString()));

        assertThat(token.isAuthenticated()).isTrue();

        AuthenticatedUser principal = (AuthenticatedUser) token.getPrincipal();
        assertThat(principal.isSignedUp()).isFalse();
        assertThat(principal.member()).isNull();
    }

    @Test
    void extractsProfileClaimsForSignupPrefill() {
        UUID authUserId = UUID.randomUUID();
        given(memberService.findByAuthUserId(authUserId)).willReturn(Optional.empty());

        var token = converter.convert(jwt(authUserId.toString()));

        AuthenticatedUser principal = (AuthenticatedUser) token.getPrincipal();
        assertThat(principal.authUserId()).isEqualTo(authUserId);
        assertThat(principal.email()).isEqualTo("test@sscc.org");
        assertThat(principal.name()).isEqualTo("김도현");
        assertThat(principal.provider()).isEqualTo("google");
    }

    // 메타데이터 클레임이 없거나 구조가 달라도 인증은 깨지지 않는다
    @Test
    void missingMetadataClaimsLeaveProfileNull() {
        UUID authUserId = UUID.randomUUID();
        given(memberService.findByAuthUserId(authUserId)).willReturn(Optional.empty());

        Jwt bareJwt =
                Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject(authUserId.toString())
                        .claim("email", "test@sscc.org")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();

        AuthenticatedUser principal = (AuthenticatedUser) converter.convert(bareJwt).getPrincipal();

        assertThat(principal.name()).isNull();
        assertThat(principal.provider()).isNull();
    }

    @Test
    void rejectsJwtWithNonUuidSubject() {
        assertThatThrownBy(() -> converter.convert(jwt("not-a-uuid")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("email", "test@sscc.org")
                .claim("user_metadata", Map.of("full_name", "김도현"))
                .claim("app_metadata", Map.of("provider", "google"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
