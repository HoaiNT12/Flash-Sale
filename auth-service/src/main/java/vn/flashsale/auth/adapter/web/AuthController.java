package vn.flashsale.auth.adapter.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.flashsale.auth.application.AuthenticationService;
import vn.flashsale.auth.application.TokenResponse;

@RestController
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationService authentication;
    private final RSAKey signingKey;

    @PostMapping("/api/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authentication.login(request.username(), request.password());
    }

    @PostMapping("/api/auth/service-token")
    public TokenResponse serviceToken(@Valid @RequestBody ServiceTokenRequest request) {
        return authentication.serviceToken(request.clientId(), request.clientSecret());
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> publicKeys() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
                               @NotBlank @Size(max = 128) String password) { }
    public record ServiceTokenRequest(@NotBlank @Size(max = 64) String clientId,
                                      @NotBlank @Size(max = 128) String clientSecret) { }
}
