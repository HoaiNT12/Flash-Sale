package vn.flashsale.payment.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.flashsale.payment.domain.PaymentFailure;
import vn.flashsale.payment.application.PaymentService;
import vn.flashsale.payment.domain.Payment;
import vn.flashsale.payment.infrastructure.persistence.JdbcPaymentStore;

/** Uses real PostgreSQL row locks, unique constraints and transaction rollback. */
@Testcontainers(disabledWithoutDocker = true)
class PaymentConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static PaymentService service;

    @BeforeAll
    static void configure() {
        var datasource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(datasource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        transaction.setTimeout(10);
        var store = new JdbcPaymentStore(jdbc);
        service = new PaymentService(store, store, store);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM payment_ledger");
        jdbc.update("DELETE FROM payments");
        jdbc.update("UPDATE wallets SET balance = CASE WHEN customer_id = 'poor' THEN 0 ELSE 100000000 END");
    }

    @Test
    void fortyConcurrentRetriesDebitAndRefundOnce() throws Exception {
        UUID order = UUID.randomUUID();
        var amount = new BigDecimal("50000.00");
        List<Payment> charges = parallel(40, () -> tx(() -> service.charge(order, "alice", amount, "VND")));
        assertThat(charges).allMatch(payment -> payment.getStatus() == Payment.Status.CHARGED);
        assertThat(balance("alice")).isEqualByComparingTo("99950000.00");
        assertThat(events(order, "DEBIT")).isEqualTo(1);

        List<Payment> refunds = parallel(40, () -> tx(() -> service.voidPayment(order)));
        assertThat(refunds).allMatch(payment -> payment.getStatus() == Payment.Status.VOIDED);
        assertThat(balance("alice")).isEqualByComparingTo("100000000.00");
        assertThat(events(order, "REFUND")).isEqualTo(1);
    }

    @Test
    void cancellationTombstonePreventsChargeAfterTimeout() {
        UUID order = UUID.randomUUID();
        assertThat(tx(() -> service.voidPayment(order)).getStatus()).isEqualTo(Payment.Status.VOIDED);
        assertThatThrownBy(() -> tx(() -> service.charge(order, "alice", new BigDecimal("100"), "VND")))
                .isInstanceOf(PaymentFailure.class).hasMessageContaining("late charge")
                .extracting(failure -> ((PaymentFailure) failure).getReason()).isEqualTo(PaymentFailure.Reason.PAYMENT_CANCELLED);
        assertThat(balance("alice")).isEqualByComparingTo("100000000.00");
        assertThat(events(order, "DEBIT")).isZero();
    }

    @Test
    void insufficientFundsRollsBackPlaceholderAndCanBeRetriedAfterFunding() {
        UUID order = UUID.randomUUID();
        assertThatThrownBy(() -> tx(() -> service.charge(order, "poor", new BigDecimal("100"), "VND")))
                .isInstanceOf(PaymentFailure.class)
                .extracting(failure -> ((PaymentFailure) failure).getReason()).isEqualTo(PaymentFailure.Reason.INSUFFICIENT_FUNDS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class, order)).isZero();
        jdbc.update("UPDATE wallets SET balance = 100 WHERE customer_id = 'poor'");
        assertThat(tx(() -> service.charge(order, "poor", new BigDecimal("100"), "VND")).getStatus()).isEqualTo(Payment.Status.CHARGED);
        assertThat(balance("poor")).isEqualByComparingTo("0");
    }

    @Test
    void differentOrdersCannotOverdrawTheSameWallet() throws Exception {
        jdbc.update("UPDATE wallets SET balance = 100 WHERE customer_id = 'alice'");
        List<Boolean> outcomes = parallel(20, () -> {
            try {
                tx(() -> service.charge(UUID.randomUUID(), "alice", new BigDecimal("30"), "VND"));
                return true;
            } catch (PaymentFailure exception) {
                assertThat(exception.getReason()).isEqualTo(PaymentFailure.Reason.INSUFFICIENT_FUNDS);
                assertThat(exception.getMessage()).contains("insufficient funds");
                return false;
            }
        });
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
        assertThat(balance("alice")).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_ledger", Long.class)).isEqualTo(3);
    }

    @Test
    void chargeRacingWithCancellationEndsVoidedWithOriginalBalance() throws Exception {
        for (int iteration = 0; iteration < 15; iteration++) {
            UUID order = UUID.randomUUID();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var charge = executor.submit(() -> {
                    try {
                        return tx(() -> service.charge(order, "alice", new BigDecimal("100"), "VND"));
                    } catch (PaymentFailure exception) {
                        assertThat(exception.getReason()).isEqualTo(PaymentFailure.Reason.PAYMENT_CANCELLED);
                        assertThat(exception.getMessage()).contains("late charge");
                        return null;
                    }
                });
                var cancel = executor.submit(() -> tx(() -> service.voidPayment(order)));
                charge.get();
                cancel.get();
            }
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, order)).isEqualTo("VOIDED");
            assertThat(balance("alice")).isEqualByComparingTo("100000000.00");
            assertThat(events(order, "DEBIT")).isEqualTo(events(order, "REFUND"));
        }
    }

    private static <T> T tx(Supplier<T> operation) {
        return transaction.execute(status -> operation.get());
    }

    private static <T> List<T> parallel(int count, Supplier<T> operation) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int index = 0; index < count; index++) tasks.add(operation::get);
            List<T> values = new ArrayList<>();
            for (var result : executor.invokeAll(tasks)) values.add(result.get());
            return values;
        }
    }

    private static BigDecimal balance(String customer) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE customer_id = ?", BigDecimal.class, customer);
    }

    private static long events(UUID order, String event) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_ledger WHERE order_id = ? AND event_type = ?", Long.class, order, event);
    }
}
