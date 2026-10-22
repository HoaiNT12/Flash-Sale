package vn.flashsale.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.flashsale.payment.domain.PaymentFailure;
import vn.flashsale.payment.application.port.PaymentLedger;
import vn.flashsale.payment.application.port.PaymentRepository;
import vn.flashsale.payment.application.port.WalletRepository;
import vn.flashsale.payment.domain.Money;
import vn.flashsale.payment.domain.Payment;

class PaymentServiceTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final PaymentLedger ledger = mock(PaymentLedger.class);
    private PaymentService service;
    private final UUID orderId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("50000.00");

    @BeforeEach
    void setUp() { service = new PaymentService(payments, wallets, ledger); }

    @Test
    void duplicateChargeReturnsExistingWithoutSecondDebit() {
        Payment charged = Payment.pending(orderId, "alice", new Money(amount, "VND")).charged();
        when(payments.lock(orderId)).thenReturn(charged);
        assertThat(service.charge(orderId, "alice", amount, "VND")).isSameAs(charged);
        verifyNoInteractions(wallets, ledger);
    }

    @Test
    void cancelBeforeChargeRejectsLateRequestWithoutDebit() {
        when(payments.lock(orderId)).thenReturn(Payment.cancellation(orderId));
        assertThatThrownBy(() -> service.charge(orderId, "alice", amount, "VND"))
                .isInstanceOf(PaymentFailure.class).hasMessageContaining("late charge")
                .extracting(failure -> ((PaymentFailure) failure).getReason()).isEqualTo(PaymentFailure.Reason.PAYMENT_CANCELLED);
        verifyNoInteractions(wallets, ledger);
    }

    @Test
    void duplicateCancellationCannotRefundAgain() {
        when(payments.lock(orderId)).thenReturn(Payment.pending(orderId, "alice", new Money(amount, "VND")).charged().voided());
        assertThat(service.voidPayment(orderId).getStatus()).isEqualTo(Payment.Status.VOIDED);
        verifyNoInteractions(wallets, ledger);
    }

    @Test
    void mismatchedPayloadCannotReuseOrderId() {
        when(payments.lock(orderId)).thenReturn(Payment.pending(orderId, "alice", new Money(amount, "VND")).charged());
        assertThatThrownBy(() -> service.charge(orderId, "bob", amount, "VND"))
                .isInstanceOf(PaymentFailure.class).hasMessageContaining("different payment payload")
                .extracting(failure -> ((PaymentFailure) failure).getReason()).isEqualTo(PaymentFailure.Reason.PAYMENT_PAYLOAD_CONFLICT);
        verifyNoInteractions(wallets, ledger);
    }
}
