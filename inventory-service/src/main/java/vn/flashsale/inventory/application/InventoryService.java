package vn.flashsale.inventory.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import vn.flashsale.inventory.application.port.ProductCatalog;
import vn.flashsale.inventory.application.port.ReservationStore;
import vn.flashsale.inventory.domain.InventoryFailure;
import vn.flashsale.inventory.domain.Product;
import vn.flashsale.inventory.domain.Reservation;

@RequiredArgsConstructor
public final class InventoryService {
    private final ReservationStore reservations;
    private final ProductCatalog catalog;

    public List<Product> products() { return catalog.list(); }

    public Product product(UUID productId) {
        return catalog.find(productId).orElseThrow(() -> new InventoryFailure(
                InventoryFailure.Reason.PRODUCT_NOT_FOUND, "Product was not found"));
    }

    public Reservation reserve(UUID orderId, UUID productId, int quantity) {
        Objects.requireNonNull(orderId, "Order id is required");
        Reservation.validateRequest(productId, quantity);
        return reservations.reserve(orderId, productId, quantity);
    }

    public Reservation confirm(UUID orderId) { return reservations.confirm(Objects.requireNonNull(orderId)); }
    public Reservation release(UUID orderId) { return reservations.release(Objects.requireNonNull(orderId)); }
}
