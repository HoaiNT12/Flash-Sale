package vn.flashsale.payment.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.flashsale.payment.domain.Payment;

public interface PaymentRepository {
    void ensurePending(UUID orderId);
    Payment lock(UUID orderId);
    void save(Payment payment);
    Optional<Payment> find(UUID orderId);
}
