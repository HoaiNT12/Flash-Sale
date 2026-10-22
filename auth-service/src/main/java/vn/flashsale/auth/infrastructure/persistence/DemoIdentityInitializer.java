package vn.flashsale.auth.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import vn.flashsale.auth.infrastructure.security.AuthProperties;

@Component
@RequiredArgsConstructor
public class DemoIdentityInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuthProperties properties;

    @Override
    public void run(ApplicationArguments arguments) {
        insertUser("alice", properties.getDemoPassword(), "shop");
        insertUser("bob", properties.getDemoPassword(), "shop");
        insertUser("poor", properties.getDemoPassword(), "shop");
        insertUser("admin", properties.getAdminPassword(), "shop admin");
        jdbc.update("INSERT INTO service_clients(client_id, secret_hash, enabled) VALUES (?, ?, true) ON CONFLICT (client_id) DO NOTHING",
                "order-service", encoder.encode(properties.getOrderServiceSecret()));
    }

    private void insertUser(String username, String password, String scopes) {
        jdbc.update("INSERT INTO accounts(username, password_hash, scopes, enabled) VALUES (?, ?, ?, true) ON CONFLICT (username) DO NOTHING",
                username, encoder.encode(password), scopes);
    }
}
