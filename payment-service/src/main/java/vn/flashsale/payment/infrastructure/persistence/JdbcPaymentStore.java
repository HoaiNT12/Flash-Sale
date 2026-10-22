package vn.flashsale.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.flashsale.payment.application.port.PaymentLedger;
import vn.flashsale.payment.application.port.PaymentRepository;
import vn.flashsale.payment.application.port.WalletRepository;
import vn.flashsale.payment.domain.Money;
import vn.flashsale.payment.domain.Payment;

@Repository
@RequiredArgsConstructor
public class JdbcPaymentStore implements PaymentRepository, WalletRepository, PaymentLedger {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Payment> PAYMENT_MAPPER = JdbcPaymentStore::map;

    @Override
    public void ensurePending(UUID orderId) {
        // ON CONFLICT waits for a concurrent transaction owning this order ID to finish.
        jdbc.update("INSERT INTO payments(order_id, status) VALUES (?, 'PENDING') ON CONFLICT (order_id) DO NOTHING", orderId);
    }

    @Override
    public Payment lock(UUID orderId) {
        return jdbc.queryForObject("SELECT * FROM payments WHERE order_id = ? FOR UPDATE", PAYMENT_MAPPER, orderId);
    }

    @Override
    public void save(Payment payment) {
        jdbc.update("""
                UPDATE payments SET customer_id = ?, amount = ?, currency = ?, status = ?, updated_at = clock_timestamp()
                WHERE order_id = ?
                """, payment.getCustomerId(), payment.getMoney() == null ? null : payment.getMoney().getAmount(),
                payment.getMoney() == null ? null : payment.getMoney().getCurrency(), payment.getStatus().name(), payment.getOrderId());
    }

    @Override
    public Optional<Payment> find(UUID orderId) {
        return jdbc.query("SELECT * FROM payments WHERE order_id = ?", PAYMENT_MAPPER, orderId).stream().findFirst();
    }

    @Override
    public boolean debit(String customerId, BigDecimal amount) {
        // The predicate and decrement are one SQL statement: concurrent orders cannot overdraw.
        return jdbc.update("""
                UPDATE wallets SET balance = balance - ?, updated_at = clock_timestamp()
                WHERE customer_id = ? AND balance >= ?
                """, amount, customerId, amount) == 1;
    }

    @Override
    public void refund(String customerId, BigDecimal amount) {
        int updated = jdbc.update("UPDATE wallets SET balance = balance + ?, updated_at = clock_timestamp() WHERE customer_id = ?", amount, customerId);
        if (updated != 1) {
            throw new IllegalStateException("Cannot refund a missing wallet");
        }
    }

    @Override
    public Optional<BigDecimal> balance(String customerId) {
        return jdbc.query("SELECT balance FROM wallets WHERE customer_id = ?", (rs, row) -> rs.getBigDecimal("balance"), customerId)
                .stream().findFirst();
    }

    @Override
    public void append(Payment payment, Event event) {
        // The unique constraint is a second line of defense; any duplicate rolls back the whole wallet transaction.
        jdbc.update("""
                INSERT INTO payment_ledger(order_id, customer_id, event_type, amount, currency)
                VALUES (?, ?, ?, ?, ?)
                """, payment.getOrderId(), payment.getCustomerId(), event.name(), payment.getMoney().getAmount(), payment.getMoney().getCurrency());
    }

    private static Payment map(ResultSet rs, int row) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        return Payment.restore(rs.getObject("order_id", UUID.class), rs.getString("customer_id"),
                amount == null ? null : new Money(amount, rs.getString("currency")), Payment.Status.valueOf(rs.getString("status")));
    }
}
