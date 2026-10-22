package vn.flashsale.payment.application;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import vn.flashsale.payment.application.port.PaymentLedger;
import vn.flashsale.payment.application.port.PaymentRepository;
import vn.flashsale.payment.application.port.PaymentUseCases;
import vn.flashsale.payment.application.port.WalletRepository;
import vn.flashsale.payment.domain.Money;
import vn.flashsale.payment.domain.Payment;
import vn.flashsale.payment.domain.PaymentFailure;
import static vn.flashsale.payment.domain.PaymentFailure.Reason.*;

/** Runs inside a transaction supplied by the infrastructure input adapter. */
@RequiredArgsConstructor
public class PaymentService implements PaymentUseCases {
    private final PaymentRepository payments;
    private final WalletRepository wallets;
    private final PaymentLedger ledger;

    @Override
    public Payment charge(UUID orderId, String customerId, BigDecimal amount, String currency) {
        Payment request;
        try {
            request = Payment.pending(orderId, customerId, new Money(amount, currency));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new PaymentFailure(INVALID_PAYMENT, exception.getMessage());
        }
        payments.ensurePending(orderId);
        Payment existing = payments.lock(orderId);
        if (!existing.matches(request)) {
            throw new PaymentFailure(PAYMENT_PAYLOAD_CONFLICT, "orderId was already used with a different payment payload");
        }
        if (existing.getStatus() == Payment.Status.VOIDED) {
            throw new PaymentFailure(PAYMENT_CANCELLED, "Payment was cancelled; late charge is forbidden");
        }
        if (existing.getStatus() == Payment.Status.CHARGED) {
            return existing;
        }
        if (!wallets.debit(customerId, request.getMoney().getAmount())) {
            throw new PaymentFailure(INSUFFICIENT_FUNDS, "Wallet is missing or has insufficient funds");
        }
        Payment charged = request.charged();
        ledger.append(charged, PaymentLedger.Event.DEBIT);
        payments.save(charged);
        return charged;
    }

    @Override
    public Payment voidPayment(UUID orderId) {
        payments.ensurePending(orderId);
        Payment existing = payments.lock(orderId);
        if (existing.getStatus() == Payment.Status.VOIDED) {
            return existing;
        }
        if (existing.getStatus() == Payment.Status.CHARGED) {
            wallets.refund(existing.getCustomerId(), existing.getMoney().getAmount());
            ledger.append(existing, PaymentLedger.Event.REFUND);
        }
        Payment cancelled = existing.voided();
        payments.save(cancelled);
        return cancelled;
    }

    @Override
    public Payment find(UUID orderId, String customerId, boolean admin) {
        Payment payment = payments.find(orderId)
                .orElseThrow(() -> new PaymentFailure(PAYMENT_NOT_FOUND, "Payment does not exist"));
        if (!admin && !customerId.equals(payment.getCustomerId())) {
            throw new PaymentFailure(PAYMENT_NOT_FOUND, "Payment does not exist");
        }
        return payment;
    }

    @Override
    public BigDecimal walletBalance(String customerId) {
        return wallets.balance(customerId)
                .orElseThrow(() -> new PaymentFailure(WALLET_NOT_FOUND, "Wallet does not exist"));
    }
}
