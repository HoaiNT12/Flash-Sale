package vn.flashsale.order.adapter.web;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.flashsale.common.ApiException;
import vn.flashsale.order.application.port.OrderRepository;
import vn.flashsale.order.domain.Order;
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    public record CheckoutRequest(@NotNull UUID productId, @Min(1) @Max(5) int quantity) {}
    private final OrderRepository orders;
    @PostMapping public ResponseEntity<Order> checkout(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CheckoutRequest request) {
        var order = orders.accept(jwt.getSubject(), key, request.productId(), request.quantity());
        return ResponseEntity.accepted().header("Location", "/api/orders/" + order.id()).header("Retry-After", "1").body(order);
    }
    @GetMapping("/{id}") public Order get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.find(id, jwt.getSubject()).orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Order not found"));
    }
}
