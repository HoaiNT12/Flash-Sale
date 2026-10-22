package vn.flashsale.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Product(UUID id, String name, BigDecimal unitPrice, String currency,
                      int availableStock, Instant saleStartsAt, Instant saleEndsAt) {
    public Product {
        Objects.requireNonNull(unitPrice, "Unit price is required");
        if (unitPrice.signum() <= 0) throw new IllegalArgumentException("Unit price must be positive");
    }

    public boolean isOnSale(Instant instant) {
        return !instant.isBefore(saleStartsAt) && instant.isBefore(saleEndsAt);
    }
}
