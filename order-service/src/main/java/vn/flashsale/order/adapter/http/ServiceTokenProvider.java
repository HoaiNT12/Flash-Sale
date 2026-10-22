package vn.flashsale.order.adapter.http;
import java.time.Instant;
import java.util.Map;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.web.client.RestClient;
public class ServiceTokenProvider {
    private record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
    private final RestClient auth;
    private final CircuitBreaker breaker;
    private final String clientId;
    private final String clientSecret;
    private String token;
    private Instant expiresAt = Instant.EPOCH;
    public ServiceTokenProvider(RestClient auth, CircuitBreaker breaker, String clientId, String clientSecret) {
        this.auth = auth; this.breaker = breaker; this.clientId = clientId; this.clientSecret = clientSecret;
    }
    public synchronized String get() {
        if (token != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) return token;
        TokenResponse response = breaker.executeSupplier(() -> auth.post().uri("/api/auth/service-token")
            .body(Map.of("clientId", clientId, "clientSecret", clientSecret)).retrieve().body(TokenResponse.class));
        if (response == null || response.accessToken() == null || response.expiresIn() < 60) throw new IllegalStateException("Invalid service token response");
        token = response.accessToken(); expiresAt = Instant.now().plusSeconds(response.expiresIn());
        return token;
    }
    public synchronized void invalidate() { token = null; expiresAt = Instant.EPOCH; }
}
