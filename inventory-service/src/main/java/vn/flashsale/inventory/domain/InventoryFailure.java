package vn.flashsale.inventory.domain;

import lombok.Getter;

/** Domain failures remain independent of HTTP and persistence frameworks. */
@Getter
public final class InventoryFailure extends RuntimeException {
    private final Reason reason;

    public InventoryFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public enum Reason {
        INVALID_QUANTITY, PRODUCT_NOT_FOUND, RESERVATION_NOT_FOUND, OUT_OF_STOCK,
        SALE_CLOSED, RESERVATION_CANCELLED, RESERVATION_CONFLICT
    }
}
