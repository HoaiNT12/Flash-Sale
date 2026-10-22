package vn.flashsale.order.config;
import java.net.http.HttpClient;
import java.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import vn.flashsale.order.adapter.http.*;
import vn.flashsale.order.adapter.persistence.JdbcOrderRepository;
import vn.flashsale.order.adapter.worker.SagaWorker;
import vn.flashsale.order.application.SagaProcessor;
import vn.flashsale.order.application.port.*;
import vn.flashsale.order.domain.RetryPolicy;
@Configuration
public class OrderConfiguration {
    @Bean OrderRepository orderRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions,
        @Value("${app.saga.max-pending:1000}") int pending, @Value("${app.saga.deadline-seconds:120}") int deadline) {
        return new JdbcOrderRepository(jdbc, new TransactionTemplate(transactions), pending, deadline);
    }
    @Bean ServiceTokenProvider serviceTokens(CircuitBreakerRegistry breakers,
        @Value("${AUTH_BASE_URL:http://localhost:8081}") String url,
        @Value("${SERVICE_CLIENT_ID:order-service}") String client,
        @Value("${SERVICE_CLIENT_SECRET:order_dev_secret}") String secret) {
        return new ServiceTokenProvider(rest(url), breakers.circuitBreaker("auth"), client, secret);
    }
    @Bean InventoryPort inventoryPort(ServiceTokenProvider tokens, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
        ObjectMapper json, @Value("${INVENTORY_BASE_URL:http://localhost:8082}") String url) {
        return new HttpInventoryAdapter(new DownstreamClient(rest(url), tokens, breakers.circuitBreaker("inventory"), bulkheads.bulkhead("inventory"), json));
    }
    @Bean PaymentPort paymentPort(ServiceTokenProvider tokens, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
        ObjectMapper json, @Value("${PAYMENT_BASE_URL:http://localhost:8083}") String url) {
        return new HttpPaymentAdapter(new DownstreamClient(rest(url), tokens, breakers.circuitBreaker("payment"), bulkheads.bulkhead("payment"), json));
    }
    @Bean SagaProcessor sagaProcessor(OrderRepository orders, InventoryPort inventory, PaymentPort payment,
        @Value("${app.saga.max-attempts:8}") int attempts) {
        return new SagaProcessor(orders, inventory, payment, new RetryPolicy(attempts, Duration.ofMillis(500), Duration.ofSeconds(30)), Clock.systemUTC());
    }
    @Bean SagaWorker sagaWorker(OrderRepository orders, SagaProcessor processor, @Value("${app.saga.concurrency:16}") int concurrency) {
        return new SagaWorker(orders, processor, concurrency);
    }
    private RestClient rest(String url) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
