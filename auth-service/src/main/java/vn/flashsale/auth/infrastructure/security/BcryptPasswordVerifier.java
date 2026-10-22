package vn.flashsale.auth.infrastructure.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import vn.flashsale.auth.application.port.PasswordVerifier;

@Component
public class BcryptPasswordVerifier implements PasswordVerifier {
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public BcryptPasswordVerifier(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    @Override
    public boolean matches(String password, String hash) {
        if (password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return encoder.matches(password, hash);
    }

    @Override
    public String dummyHash() {
        return dummyHash;
    }
}
