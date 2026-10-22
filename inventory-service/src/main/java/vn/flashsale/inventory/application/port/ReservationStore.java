package vn.flashsale.inventory.application.port;

import java.util.UUID;
import vn.flashsale.inventory.domain.Reservation;

/** Each operation commits the reservation transition and stock change atomically. */
public interface ReservationStore {
    Reservation reserve(UUID orderId, UUID productId, int quantity);
    Reservation confirm(UUID orderId);
    Reservation release(UUID orderId);
}
