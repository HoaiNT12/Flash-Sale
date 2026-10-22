package vn.flashsale.payment.application.port;

import java.math.BigDecimal;
import java.util.Optional;

public interface WalletRepository {
    boolean debit(String customerId, BigDecimal amount);
    void refund(String customerId, BigDecimal amount);
    Optional<BigDecimal> balance(String customerId);
}
