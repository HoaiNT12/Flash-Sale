package vn.flashsale.order.application.port;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import vn.flashsale.order.domain.*;
public interface OrderRepository {
    Order accept(String customerId, String idempotencyKey, UUID productId, int quantity);
    Optional<Order> find(UUID id, String customerId);
    Optional<SagaJob> claim(Duration lease);
    boolean advance(SagaJob job, BigDecimal unitPrice, BigDecimal amount, String currency);
    boolean retry(SagaJob job, Duration delay, String code);
    boolean compensate(SagaJob job, String code);
}
