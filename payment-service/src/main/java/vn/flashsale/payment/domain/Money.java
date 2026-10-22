package vn.flashsale.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
public final class Money {
    private final BigDecimal amount;
    private final String currency;

    public Money(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        if (!"VND".equals(currency)) {
            throw new IllegalArgumentException("Only VND is supported");
        }
        try {
            this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Payment amount supports at most two decimal places", exception);
        }
        if (this.amount.precision() > 19) {
            throw new IllegalArgumentException("Payment amount exceeds numeric(19,2)");
        }
        this.currency = currency;
    }
}
