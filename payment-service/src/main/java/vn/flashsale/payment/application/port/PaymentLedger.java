package vn.flashsale.payment.application.port;

import vn.flashsale.payment.domain.Payment;

public interface PaymentLedger {
    enum Event { DEBIT, REFUND }
    void append(Payment payment, Event event);
}
