package vn.flashsale.auth.infrastructure.security;

import java.nio.file.Path;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private String issuer = "http://localhost:8081";
    private String audience = "flashsale-api";
    private Duration tokenTtl = Duration.ofMinutes(15);
    private Path keyDirectory = Path.of(".local/auth-keys");
    private String demoPassword = "Demo@123";
    private String adminPassword = "Admin@123";
    private String orderServiceSecret = "order_dev_secret";
}
