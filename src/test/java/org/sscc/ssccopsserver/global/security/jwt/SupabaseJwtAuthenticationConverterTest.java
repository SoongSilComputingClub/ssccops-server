package org.sscc.ssccopsserver.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
class SupabaseJwtAuthenticationConverterTest {

    @Mock private MemberService memberService;

    @InjectMocks private SupabaseJwtAuthenticationConverter converter;

    @Test
    void convertsValidJwtToAuthenticatedTokenWithMemberPrincipal() {
        UUID spbUserId = UUID.randomUUID();
        MemberEntity member = mockMember();
        given(memberService.findOrProvisionBySpbUserId(spbUserId, "test@sscc.org"))
                .willReturn(member);

        var token = converter.convert(jwt(spbUserId.toString(), "test@sscc.org"));

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getPrincipal()).isEqualTo(member);
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void rejectsJwtWithNonUuidSubject() {
        assertThatThrownBy(() -> converter.convert(jwt("not-a-uuid", "test@sscc.org")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    private MemberEntity mockMember() {
        return Mockito.mock(MemberEntity.class);
    }

    private Jwt jwt(String subject, String email) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("email", email)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
