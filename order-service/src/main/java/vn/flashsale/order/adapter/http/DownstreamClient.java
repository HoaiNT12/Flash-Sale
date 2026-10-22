package vn.flashsale.order.adapter.http;
import java.util.Map;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import vn.flashsale.order.domain.BusinessFailure;
@RequiredArgsConstructor
public class DownstreamClient {
    private final RestClient rest;
    private final ServiceTokenProvider tokens;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final ObjectMapper json;
    public <T> T post(String path, Object body, Class<T> responseType) {
        Supplier<T> call = () -> rest.post().uri(path).headers(headers -> headers.setBearerAuth(tokens.get()))
            .body(body).retrieve()
            .onStatus(status -> status.value() == 401 || status.value() == 403 || status.value() == 429,
                (request, response) -> { if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) tokens.invalidate(); throw new IllegalStateException("Downstream authorization or capacity unavailable"); })
            .onStatus(status -> status.is4xxClientError(), (request, response) -> {
                String code = "DOWNSTREAM_REJECTED";
                try { var error = json.readTree(response.getBody()); code = error.path("code").asText(code); }
                catch (Exception ignored) { /* Retain stable fallback code. */ }
                throw new BusinessFailure(code, "Checkout rejected by downstream service");
            }).body(responseType);
        // Fail-fast bulkhead admission occurs before breaker accounting. Business errors are ignored by the circuit.
        return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, call)).get();
    }
    public void post(String path) { post(path, Map.of(), Void.class); }
}
