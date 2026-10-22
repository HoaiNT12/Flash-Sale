package vn.flashsale.order.application;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import vn.flashsale.order.application.port.*;
import vn.flashsale.order.domain.*;
@RequiredArgsConstructor
public class SagaProcessor {
    private final OrderRepository orders;
    private final InventoryPort inventory;
    private final PaymentPort payment;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    public void process(SagaJob job) {
        var order = job.order();
        if (!order.step().compensating() && !clock.instant().isBefore(order.deadline())) {
            orders.compensate(job, "CHECKOUT_DEADLINE"); return;
        }
        try {
            switch (order.step()) {
                case RESERVE -> {
                    var reservation = inventory.reserve(order.id(), order.productId(), order.quantity());
                    if (reservation.unitPrice() == null || reservation.unitPrice().signum() <= 0 || !"VND".equals(reservation.currency()))
                        throw new BusinessFailure("INVALID_QUOTE", "Inventory returned invalid price");
                    orders.advance(job, reservation.unitPrice(), reservation.unitPrice().multiply(java.math.BigDecimal.valueOf(order.quantity())), reservation.currency());
                    return;
                }
                case PAY -> payment.charge(order.id(), order.customerId(), order.amount(), order.currency());
                case CONFIRM -> inventory.confirm(order.id());
                case VOID_PAYMENT -> payment.voidPayment(order.id());
                case RELEASE -> inventory.release(order.id());
                case FINISHED -> { return; }
            }
            orders.advance(job, order.unitPrice(), order.amount(), order.currency());
        } catch (BusinessFailure e) {
            if (order.step().compensating()) scheduleRetry(job, e.getCode());
            else orders.compensate(job, e.getCode());
        } catch (RuntimeException e) {
            // Includes timeouts / OPEN circuit / exhausted bulkhead / uncertain HTTP result.
            if (retryPolicy.shouldCompensate(order, clock.instant())) orders.compensate(job, "DOWNSTREAM_UNAVAILABLE");
            else scheduleRetry(job, "DOWNSTREAM_UNAVAILABLE");
        }
    }
    private void scheduleRetry(SagaJob job, String code) {
        orders.retry(job, retryPolicy.delay(job.order().attempts(), ThreadLocalRandom.current().nextDouble()), code);
    }
}
