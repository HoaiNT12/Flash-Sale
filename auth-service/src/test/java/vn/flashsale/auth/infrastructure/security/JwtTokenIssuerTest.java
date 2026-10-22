package vn.flashsale.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import vn.flashsale.auth.adapter.web.AuthController;

class JwtTokenIssuerTest {
    @TempDir
    Path keyDirectory;

    @Test
    void signedJwtHasIssuerAudienceExpiryAndRestartStableSigningKey() throws Exception {
        RSAKey key = new PersistentRsaKeys(keyDirectory).loadOrCreate();
        var properties = new AuthProperties();
        var issuer = new JwtTokenIssuer(new AuthSecurityConfiguration().jwtEncoder(key), key, properties);
        var token = issuer.issue("alice", Set.of("shop"), "customer");
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        var jwt = decoder.decode(token.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:8081");
        assertThat(jwt.getAudience()).containsExactly("flashsale-api");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("shop");
        assertThat(jwt.getClaimAsString("token_kind")).isEqualTo("customer");
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds()).isEqualTo(900);
        assertThat(token.expiresIn()).isEqualTo(900);
        RSAKey reloaded = new PersistentRsaKeys(keyDirectory).loadOrCreate();
        assertThat(reloaded.getKeyID()).isEqualTo(key.getKeyID());
        assertThat(NimbusJwtDecoder.withPublicKey(reloaded.toRSAPublicKey()).build().decode(token.accessToken()).getSubject())
                .isEqualTo("alice");
    }

    @Test
    void publicJwksNeverContainsPrivateComponents() throws Exception {
        RSAKey key = new PersistentRsaKeys(keyDirectory).loadOrCreate();
        Map<String, Object> response = new AuthController(null, key).publicKeys();
        var parsed = JWKSet.parse(response).getKeys().getFirst().toRSAKey();
        assertThat(parsed.isPrivate()).isFalse();
        assertThat(parsed.toJSONObject()).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
        assertThat(parsed.getKeyID()).isEqualTo(key.getKeyID());
        assertThat(Files.readAllBytes(keyDirectory.resolve("private-key.pkcs8"))).isNotEmpty();
    }

    @Test
    void missingKeyComponentFailsInsteadOfSilentlyInvalidatingExistingTokens() throws Exception {
        new PersistentRsaKeys(keyDirectory).loadOrCreate();
        Files.delete(keyDirectory.resolve("public-key.der"));
        assertThatThrownBy(() -> new PersistentRsaKeys(keyDirectory).loadOrCreate()).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
