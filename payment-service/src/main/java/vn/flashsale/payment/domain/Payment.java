package vn.flashsale.payment.domain;

import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Aggregate root. A VOIDED record without a payload is a cancellation tombstone. */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class Payment {
    public enum Status { PENDING, CHARGED, VOIDED }

    private final UUID orderId;
    private final String customerId;
    private final Money money;
    private final Status status;

    public static Payment pending(UUID orderId, String customerId, Money money) {
        Objects.requireNonNull(orderId, "orderId is required");
        if (customerId == null || customerId.isBlank() || customerId.length() > 100) {
            throw new IllegalArgumentException("customerId must have 1 to 100 characters");
        }
        Objects.requireNonNull(money, "money is required");
        return new Payment(orderId, customerId, money, Status.PENDING);
    }

    public static Payment restore(UUID orderId, String customerId, Money money, Status status) {
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(status, "status is required");
        if ((customerId == null) != (money == null) || (status == Status.CHARGED && money == null)) {
            throw new IllegalArgumentException("Stored payment payload is incomplete");
        }
        return new Payment(orderId, customerId, money, status);
    }

    public static Payment cancellation(UUID orderId) {
        return restore(orderId, null, null, Status.VOIDED);
    }

    public boolean matches(Payment request) {
        return customerId == null || (customerId.equals(request.customerId) && money.equals(request.money));
    }

    public Payment charged() {
        if (status != Status.PENDING || money == null) {
            throw new IllegalStateException("Only a pending payment with a payload can be charged");
        }
        return new Payment(orderId, customerId, money, Status.CHARGED);
    }

    public Payment voided() {
        return new Payment(orderId, customerId, money, Status.VOIDED);
    }
}
