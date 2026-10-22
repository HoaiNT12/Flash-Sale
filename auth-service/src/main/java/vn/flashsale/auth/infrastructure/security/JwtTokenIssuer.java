package vn.flashsale.auth.infrastructure.security;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;
import vn.flashsale.auth.application.TokenResponse;
import vn.flashsale.auth.application.port.TokenIssuer;

@Component
@RequiredArgsConstructor
public class JwtTokenIssuer implements TokenIssuer {
    private final JwtEncoder encoder;
    private final RSAKey key;
    private final AuthProperties properties;

    @Override
    public TokenResponse issue(String subject, Set<String> scopes, String tokenKind) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(properties.getIssuer()).subject(subject)
                .audience(List.of(properties.getAudience())).issuedAt(now).expiresAt(now.plus(properties.getTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("scope", scopes.stream().sorted().collect(Collectors.joining(" ")))
                .claim("token_kind", tokenKind).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        String jwt = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(jwt, "Bearer", properties.getTokenTtl().toSeconds());
    }
}
