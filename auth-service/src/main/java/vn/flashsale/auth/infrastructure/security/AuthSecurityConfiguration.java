package vn.flashsale.auth.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vn.flashsale.auth.application.AuthenticationService;
import vn.flashsale.auth.application.port.PasswordVerifier;
import vn.flashsale.auth.application.port.TokenIssuer;
import vn.flashsale.auth.domain.port.AccountRepository;
import vn.flashsale.auth.domain.port.ServiceClientRepository;

@Configuration
public class AuthSecurityConfiguration {
    @Bean
    AuthenticationService authenticationService(AccountRepository accounts, ServiceClientRepository clients,
                                                PasswordVerifier passwords, TokenIssuer tokens) {
        return new AuthenticationService(accounts, clients, passwords, tokens);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    RSAKey signingKey(AuthProperties properties) {
        if (properties.getTokenTtl().isZero() || properties.getTokenTtl().isNegative()
                || properties.getTokenTtl().compareTo(java.time.Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Auth token TTL must be between zero and one hour");
        }
        return new PersistentRsaKeys(properties.getKeyDirectory()).loadOrCreate();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(key));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    SecurityFilterChain authSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/api/auth/service-token", "/.well-known/jwks.json",
                                "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(new AuthenticationRateLimitFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
