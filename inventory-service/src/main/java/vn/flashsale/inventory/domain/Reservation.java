package vn.flashsale.inventory.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import static vn.flashsale.inventory.domain.InventoryFailure.Reason.*;

/** One reservation per order; price is fixed when inventory is first acquired. */
public record Reservation(UUID orderId, UUID productId, Integer quantity,
                          BigDecimal unitPrice, String currency, Status status) {
    public enum Status { PENDING, RESERVED, CONFIRMED, CANCELLED }

    public static Reservation pending(UUID orderId) {
        return new Reservation(Objects.requireNonNull(orderId), null, null, null, null, Status.PENDING);
    }

    public static Reservation cancelled(UUID orderId) {
        return new Reservation(Objects.requireNonNull(orderId), null, null, null, null, Status.CANCELLED);
    }

    public Reservation reserve(UUID requestedProductId, int requestedQuantity, BigDecimal price, String requestedCurrency) {
        validateRequest(requestedProductId, requestedQuantity);
        if (status == Status.CANCELLED) {
            throw new InventoryFailure(RESERVATION_CANCELLED, "Order was cancelled; inventory cannot be acquired again");
        }
        if (status != Status.PENDING) {
            assertSameRequest(requestedProductId, requestedQuantity);
            return this;
        }
        Objects.requireNonNull(price, "Price must come from inventory");
        if (price.signum() <= 0) throw new IllegalArgumentException("Price must be positive");
        return new Reservation(orderId, requestedProductId, requestedQuantity, price,
                Objects.requireNonNull(requestedCurrency), Status.RESERVED);
    }

    public void assertSameRequest(UUID requestedProductId, int requestedQuantity) {
        validateRequest(requestedProductId, requestedQuantity);
        if (!Objects.equals(productId, requestedProductId) || !Objects.equals(quantity, requestedQuantity)) {
            throw new InventoryFailure(RESERVATION_CONFLICT, "Order already has a reservation for a different request");
        }
    }

    public Reservation confirm() {
        if (status == Status.CANCELLED) {
            throw new InventoryFailure(RESERVATION_CANCELLED, "Cancelled inventory cannot be confirmed");
        }
        if (status == Status.PENDING) {
            throw new InventoryFailure(RESERVATION_NOT_FOUND, "Order does not have reserved inventory");
        }
        return new Reservation(orderId, productId, quantity, unitPrice, currency, Status.CONFIRMED);
    }

    public boolean holdsInventory() {
        return status == Status.RESERVED || status == Status.CONFIRMED;
    }

    public Reservation release() {
        return new Reservation(orderId, productId, quantity, unitPrice, currency, Status.CANCELLED);
    }

    public static void validateRequest(UUID productId, int quantity) {
        Objects.requireNonNull(productId, "Product id is required");
        if (quantity <= 0) throw new InventoryFailure(INVALID_QUANTITY, "Quantity must be positive");
    }
}
