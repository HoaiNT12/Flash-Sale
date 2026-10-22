package vn.flashsale.order.application.port;
import java.math.BigDecimal;
import java.util.UUID;
public interface InventoryPort {
    record Reservation(BigDecimal unitPrice, String currency) {}
    Reservation reserve(UUID orderId, UUID productId, int quantity);
    void confirm(UUID orderId);
    void release(UUID orderId);
}
