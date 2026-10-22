package vn.flashsale.payment.application.port;

import java.math.BigDecimal;
import java.util.UUID;
import vn.flashsale.payment.domain.Payment;

public interface PaymentUseCases {
    Payment charge(UUID orderId, String customerId, BigDecimal amount, String currency);
    Payment voidPayment(UUID orderId);
    Payment find(UUID orderId, String customerId, boolean admin);
    BigDecimal walletBalance(String customerId);
}
