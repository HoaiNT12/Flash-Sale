package vn.flashsale.common;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

class ResourceJwtValidationTest {
    private static OfflineJwtFixture fixture;
    private static JwtDecoder decoder;

    @BeforeAll
    static void configure() throws Exception {
        fixture = new OfflineJwtFixture();
        decoder = new ResourceSecurityConfiguration().jwtDecoder(fixture.jwksUrl(), OfflineJwtFixture.ISSUER,
                OfflineJwtFixture.AUDIENCE);
    }

    @AfterAll
    static void close() { if (fixture != null) fixture.close(); }

    @Test
    void acceptsExpectedIssuerAudienceAndValidRsaSignature() throws Exception {
        var jwt = decoder.decode(fixture.token("shop"));
        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(jwt.getIssuer().toString()).isEqualTo(OfflineJwtFixture.ISSUER);
        assertThat(jwt.getAudience()).contains(OfflineJwtFixture.AUDIENCE);
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void acceptsAudienceListContainingTheConfiguredAudience() throws Exception {
        String token = fixture.signed(fixture.trustedKey, fixture.trustedKey.getKeyID(), OfflineJwtFixture.ISSUER,
                List.of("another-api", OfflineJwtFixture.AUDIENCE), Instant.now().plusSeconds(300), "shop");
        assertThat(decoder.decode(token).getAudience()).containsExactly("another-api", OfflineJwtFixture.AUDIENCE);
    }

    @Test
    void rejectsWrongIssuerEvenWhenSignatureAndAudienceAreValid() throws Exception {
        String token = fixture.signed(fixture.trustedKey, fixture.trustedKey.getKeyID(), "https://other-issuer.test",
                List.of(OfflineJwtFixture.AUDIENCE), Instant.now().plusSeconds(300), "shop");
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongAudienceEvenWhenSignatureAndIssuerAreValid() throws Exception {
        String token = fixture.signed(fixture.trustedKey, fixture.trustedKey.getKeyID(), OfflineJwtFixture.ISSUER,
                List.of("another-api"), Instant.now().plusSeconds(300), "shop");
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMissingAudienceAsAnInvalidJwt() throws Exception {
        String token = fixture.signed(fixture.trustedKey, fixture.trustedKey.getKeyID(), OfflineJwtFixture.ISSUER,
                null, Instant.now().plusSeconds(300), "shop");
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredTokenBeyondThePermittedClockSkew() throws Exception {
        String token = fixture.signed(fixture.trustedKey, fixture.trustedKey.getKeyID(), OfflineJwtFixture.ISSUER,
                List.of(OfflineJwtFixture.AUDIENCE), Instant.now().minusSeconds(120), "shop");
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSignatureFromAnotherPrivateKeyUsingTheTrustedKeyId() throws Exception {
        var attackerKey = new RSAKeyGenerator(2048).keyID("attacker-key").generate();
        String token = fixture.signed(attackerKey, fixture.trustedKey.getKeyID(), OfflineJwtFixture.ISSUER,
                List.of(OfflineJwtFixture.AUDIENCE), Instant.now().plusSeconds(300), "shop");
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
