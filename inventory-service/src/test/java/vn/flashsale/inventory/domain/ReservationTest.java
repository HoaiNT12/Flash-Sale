package vn.flashsale.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReservationTest {
    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void retriedReservationKeepsItsOriginalPriceEvenWhenTheCatalogChanges() {
        Reservation first = Reservation.pending(orderId).reserve(productId, 2, new BigDecimal("99000"), "VND");
        Reservation retried = first.reserve(productId, 2, new BigDecimal("199000"), "VND");
        assertThat(retried).isSameAs(first);
        assertThat(retried.unitPrice()).isEqualByComparingTo("99000");
    }

    @Test
    void orderIdCannotBeReusedForADifferentProductOrQuantity() {
        Reservation first = Reservation.pending(orderId).reserve(productId, 2, BigDecimal.ONE, "VND");
        assertThatThrownBy(() -> first.reserve(productId, 3, BigDecimal.ONE, "VND"))
                .isInstanceOfSatisfying(InventoryFailure.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.RESERVATION_CONFLICT));
        assertThatThrownBy(() -> first.reserve(UUID.randomUUID(), 2, BigDecimal.ONE, "VND"))
                .isInstanceOf(InventoryFailure.class);
    }

    @Test
    void cancellationIsTerminalAndPreventsLateReserveAndConfirm() {
        Reservation tombstone = Reservation.cancelled(orderId);
        assertThatThrownBy(() -> tombstone.reserve(productId, 1, BigDecimal.ONE, "VND"))
                .isInstanceOfSatisfying(InventoryFailure.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.RESERVATION_CANCELLED));
        assertThatThrownBy(tombstone::confirm).isInstanceOf(InventoryFailure.class);
        assertThat(tombstone.release()).isEqualTo(tombstone);
        assertThat(tombstone.holdsInventory()).isFalse();
    }

    @Test
    void confirmedStockRemainsHeldUntilCompensationReleasesIt() {
        Reservation confirmed = Reservation.pending(orderId).reserve(productId, 1, BigDecimal.ONE, "VND").confirm();
        assertThat(confirmed.confirm()).isEqualTo(confirmed);
        assertThat(confirmed.holdsInventory()).isTrue();
        assertThat(confirmed.release().holdsInventory()).isFalse();
    }

    @Test
    void nonPositiveQuantityCannotAcquireStock() {
        assertThatThrownBy(() -> Reservation.pending(orderId).reserve(productId, 0, BigDecimal.ONE, "VND"))
                .isInstanceOfSatisfying(InventoryFailure.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.INVALID_QUANTITY));
    }

    @Test
    void zeroPriceCannotEnterTheCatalogOrBeReservedForTheWalletPaymentFlow() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new Product(productId, "Free Keyboard", BigDecimal.ZERO, "VND", 1,
                now, now.plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reservation.pending(orderId).reserve(productId, 1, BigDecimal.ZERO, "VND"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flashSaleIncludesStartAndExcludesEnd() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plusSeconds(60);
        Product product = new Product(productId, "Keyboard", BigDecimal.ONE, "VND", 1, start, end);
        assertThat(product.isOnSale(start.minusNanos(1))).isFalse();
        assertThat(product.isOnSale(start)).isTrue();
        assertThat(product.isOnSale(end.minusNanos(1))).isTrue();
        assertThat(product.isOnSale(end)).isFalse();
    }
}
