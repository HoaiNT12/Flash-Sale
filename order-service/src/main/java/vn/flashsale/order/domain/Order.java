package vn.flashsale.order.domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record Order(UUID id, String customerId, UUID productId, int quantity,
                    BigDecimal unitPrice, BigDecimal amount, String currency,
                    OrderStatus status, SagaStep step, int attempts, Instant deadline,
                    String failureCode, Instant createdAt, Instant updatedAt) {
    public Order {
        if (quantity < 1 || quantity > 5) throw new IllegalArgumentException("Quantity must be 1..5");
    }
}
