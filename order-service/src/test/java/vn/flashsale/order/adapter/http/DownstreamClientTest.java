package vn.flashsale.order.adapter.http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.*;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import vn.flashsale.order.domain.BusinessFailure;
import static org.assertj.core.api.Assertions.*;
class DownstreamClientTest {
    private HttpServer server;
    private DownstreamClient http;
    private CircuitBreaker circuit;
    private int status;
    private int calls;
    @BeforeEach void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/service-token", exchange -> {
            byte[] bytes = "{\"accessToken\":\"test\",\"tokenType\":\"Bearer\",\"expiresIn\":900}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/command", exchange -> {
            calls++;
            byte[] bytes = "{\"code\":\"OUT_OF_STOCK\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        var tokens = new ServiceTokenProvider(rest, CircuitBreaker.ofDefaults("auth"), "order-service", "secret");
        circuit = CircuitBreaker.of("inventory", CircuitBreakerConfig.custom().minimumNumberOfCalls(4).slidingWindowSize(4)
            .failureRateThreshold(50).ignoreExceptions(BusinessFailure.class).build());
        http = new DownstreamClient(rest, tokens, circuit, Bulkhead.ofDefaults("inventory"), new ObjectMapper());
    }
    @AfterEach void close() { server.stop(0); }
    @Test void businessRejectionsDoNotOpenCircuit() {
        status = 409;
        for (int i = 0; i < 12; i++) assertThatThrownBy(() -> http.post("/command", Map.of(), Map.class)).isInstanceOf(BusinessFailure.class);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
    }
    @Test void overloadOpensCircuitAndFailsFastThenHalfOpenCanRecover() {
        status = 503;
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> http.post("/command", Map.of(), Map.class)).isInstanceOf(RuntimeException.class);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> http.post("/command", Map.of(), Map.class)).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).isEqualTo(4);
        circuit.transitionToHalfOpenState(); status = 200;
        for (int i = 0; i < 10; i++) http.post("/command", Map.of(), Map.class);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void capacityErrorsAreTechnicalFailures() {
        status = 429;
        assertThatThrownBy(() -> http.post("/command", Map.of(), Map.class)).isInstanceOf(IllegalStateException.class);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }
}
