package vn.flashsale.order.adapter.http;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import vn.flashsale.order.application.port.PaymentPort;
@RequiredArgsConstructor
public class HttpPaymentAdapter implements PaymentPort {
    private record Response(String status) {}
    private final DownstreamClient http;
    @Override public void charge(UUID order, String customer, BigDecimal amount, String currency) {
        var response = http.post("/internal/payments/charge", Map.of("orderId", order, "customerId", customer, "amount", amount, "currency", currency), Response.class);
        if (response == null || !"CHARGED".equals(response.status())) throw new IllegalStateException("Invalid payment response");
    }
    @Override public void voidPayment(UUID order) { http.post("/internal/payments/" + order + "/void"); }
}
