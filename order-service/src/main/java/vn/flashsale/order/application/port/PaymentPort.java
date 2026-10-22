package vn.flashsale.order.application.port;
import java.math.BigDecimal;
import java.util.UUID;
public interface PaymentPort {
    void charge(UUID orderId, String customerId, BigDecimal amount, String currency);
    void voidPayment(UUID orderId);
}
