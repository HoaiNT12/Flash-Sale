package vn.flashsale.common;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Loopback-only JWKS server: these tests need neither the auth service nor an external network. */
final class OfflineJwtFixture implements AutoCloseable {
    static final String ISSUER = "https://issuer.flashsale.test";
    static final String AUDIENCE = "flashsale-api";
    final RSAKey trustedKey;
    private final HttpServer server;

    OfflineJwtFixture() throws Exception {
        trustedKey = new RSAKeyGenerator(2048).keyID("trusted-test-key").generate();
        byte[] jwks = new JWKSet(trustedKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var body = exchange.getResponseBody()) { body.write(jwks); }
        });
        server.start();
    }

    String jwksUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"; }

    String token(String scopes) throws Exception {
        return signed(trustedKey, trustedKey.getKeyID(), ISSUER, List.of(AUDIENCE),
                Instant.now().plusSeconds(300), scopes);
    }

    String signed(RSAKey signingKey, String keyId, String issuer, List<String> audiences,
                  Instant expiresAt, String scopes) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("alice")
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiresAt)).claim("scope", scopes);
        if (audiences != null) claims.audience(audiences);
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims.build());
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }

    @Override
    public void close() { server.stop(0); }
}
