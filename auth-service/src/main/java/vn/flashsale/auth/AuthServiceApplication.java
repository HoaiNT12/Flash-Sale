package vn.flashsale.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import vn.flashsale.auth.infrastructure.security.AuthProperties;
import vn.flashsale.common.ApiErrorHandler;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
@Import(ApiErrorHandler.class)
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
