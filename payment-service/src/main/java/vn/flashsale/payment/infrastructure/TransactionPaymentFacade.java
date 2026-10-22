package vn.flashsale.payment.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import vn.flashsale.payment.application.PaymentService;
import vn.flashsale.payment.application.port.PaymentUseCases;
import vn.flashsale.payment.domain.Payment;

@RequiredArgsConstructor
public class TransactionPaymentFacade implements PaymentUseCases {
    private final PaymentService service;

    @Override
    @Transactional(timeout = 5)
    public Payment charge(UUID orderId, String customerId, BigDecimal amount, String currency) {
        return service.charge(orderId, customerId, amount, currency);
    }

    @Override
    @Transactional(timeout = 5)
    public Payment voidPayment(UUID orderId) {
        return service.voidPayment(orderId);
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public Payment find(UUID orderId, String customerId, boolean admin) {
        return service.find(orderId, customerId, admin);
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public BigDecimal walletBalance(String customerId) {
        return service.walletBalance(customerId);
    }
}
