package vn.flashsale.payment.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {
    @Test
    void moneyNormalizesScaleWithoutRounding() {
        assertThat(new Money(new BigDecimal("100"), "VND"))
                .isEqualTo(new Money(new BigDecimal("100.00"), "VND"));
        assertThatThrownBy(() -> new Money(new BigDecimal("100.001"), "VND"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ZERO, "VND"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelledPaymentCannotTransitionBackToCharged() {
        Payment pending = Payment.pending(UUID.randomUUID(), "alice", new Money(new BigDecimal("100"), "VND"));
        assertThat(pending.charged().voided().getStatus()).isEqualTo(Payment.Status.VOIDED);
        assertThatThrownBy(() -> pending.voided().charged()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Payment.cancellation(UUID.randomUUID()).charged()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void idempotencyComparesOwnerAndAmount() {
        UUID order = UUID.randomUUID();
        Payment existing = Payment.pending(order, "alice", new Money(new BigDecimal("100"), "VND")).charged();
        assertThat(existing.matches(Payment.pending(order, "alice", new Money(new BigDecimal("100.00"), "VND")))).isTrue();
        assertThat(existing.matches(Payment.pending(order, "bob", new Money(new BigDecimal("100"), "VND")))).isFalse();
        assertThat(existing.matches(Payment.pending(order, "alice", new Money(new BigDecimal("101"), "VND")))).isFalse();
    }
}
