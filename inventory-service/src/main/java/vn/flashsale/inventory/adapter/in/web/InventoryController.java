package vn.flashsale.inventory.adapter.in.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.flashsale.common.DemoFaults;
import vn.flashsale.inventory.application.InventoryService;
import vn.flashsale.inventory.domain.Product;
import vn.flashsale.inventory.domain.Reservation;

@RestController
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService inventory;
    private final DemoFaults demoFaults;

    @GetMapping("/api/products")
    public List<Product> products() { return inventory.products(); }

    @GetMapping("/api/products/{productId}")
    public Product product(@PathVariable UUID productId) { return inventory.product(productId); }

    @PostMapping("/internal/reservations")
    public ReservationResponse reserve(@Valid @RequestBody ReserveRequest request) {
        // Fault injection runs before acquiring a transaction or holding a DB lock.
        demoFaults.beforeRequest();
        return ReservationResponse.from(inventory.reserve(request.orderId(), request.productId(), request.quantity()));
    }

    @PostMapping("/internal/reservations/{orderId}/confirm")
    public ReservationResponse confirm(@PathVariable UUID orderId) {
        demoFaults.beforeRequest();
        return ReservationResponse.from(inventory.confirm(orderId));
    }

    @PostMapping("/internal/reservations/{orderId}/release")
    public ReservationResponse release(@PathVariable UUID orderId) {
        return ReservationResponse.from(inventory.release(orderId));
    }

    public record ReserveRequest(@NotNull UUID orderId, @NotNull UUID productId,
                                 @Min(1) @Max(10000) int quantity) {}

    public record ReservationResponse(UUID orderId, UUID productId, Integer quantity,
                                      BigDecimal unitPrice, String currency, Reservation.Status status) {
        static ReservationResponse from(Reservation reservation) {
            return new ReservationResponse(reservation.orderId(), reservation.productId(), reservation.quantity(),
                    reservation.unitPrice(), reservation.currency(), reservation.status());
        }
    }
}
