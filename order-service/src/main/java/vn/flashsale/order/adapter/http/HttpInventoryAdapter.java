package vn.flashsale.order.adapter.http;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import vn.flashsale.order.application.port.InventoryPort;
@RequiredArgsConstructor
public class HttpInventoryAdapter implements InventoryPort {
    private record Response(BigDecimal unitPrice, String currency, String status) {}
    private final DownstreamClient http;
    @Override public Reservation reserve(UUID order, UUID product, int quantity) {
        var response = http.post("/internal/reservations", Map.of("orderId", order, "productId", product, "quantity", quantity), Response.class);
        if (response == null || !("RESERVED".equals(response.status()) || "CONFIRMED".equals(response.status())))
            throw new IllegalStateException("Invalid inventory response");
        return new Reservation(response.unitPrice(), response.currency());
    }
    @Override public void confirm(UUID order) { http.post("/internal/reservations/" + order + "/confirm"); }
    @Override public void release(UUID order) { http.post("/internal/reservations/" + order + "/release"); }
}
