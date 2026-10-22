package vn.flashsale.payment.domain;

import java.util.Objects;
import lombok.Getter;

/** A business failure independent of transport, Spring and shared HTTP types. */
@Getter
public final class PaymentFailure extends RuntimeException {
    public enum Reason {
        INVALID_PAYMENT, PAYMENT_PAYLOAD_CONFLICT, PAYMENT_CANCELLED,
        INSUFFICIENT_FUNDS, PAYMENT_NOT_FOUND, WALLET_NOT_FOUND
    }

    private final Reason reason;

    public PaymentFailure(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }
}
