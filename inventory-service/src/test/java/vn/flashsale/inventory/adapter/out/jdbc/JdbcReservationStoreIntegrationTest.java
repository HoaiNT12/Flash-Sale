package vn.flashsale.inventory.adapter.out.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.flashsale.inventory.domain.InventoryFailure;
import vn.flashsale.inventory.domain.Reservation;
import static org.assertj.core.api.Assertions.*;

/** Exercises real PostgreSQL row locks, unique constraints, and transaction rollback. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class JdbcReservationStoreIntegrationTest {
    private static final UUID PRODUCT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcReservationStore store;

    @BeforeAll
    static void connect() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(12);
        config.setConnectionTimeout(15000);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcReservationStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @AfterAll
    static void disconnect() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void resetInventory() {
        jdbc.update("DELETE FROM reservations");
        jdbc.update("""
                UPDATE products SET available_stock = 100, unit_price = 99000,
                    sale_starts_at = CURRENT_TIMESTAMP - INTERVAL '1 day',
                    sale_ends_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE id = ?
                """, PRODUCT);
    }

    @Test
    void concurrentBuyersCannotOversellTheLastHundredUnits() throws Exception {
        List<Callable<Boolean>> buyers = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            buyers.add(() -> {
                try {
                    store.reserve(UUID.randomUUID(), PRODUCT, 1);
                    return true;
                } catch (InventoryFailure failure) {
                    assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.OUT_OF_STOCK);
                    return false;
                }
            });
        }
        List<Boolean> outcomes = concurrently(buyers);
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(100);
        assertThat(stock()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE status = 'RESERVED'", Integer.class))
                .isEqualTo(100);
    }

    @Test
    void concurrentRetriesOnlyAcquireOnceAndKeepTheServerPrice() throws Exception {
        UUID order = UUID.randomUUID();
        List<Callable<Reservation>> retries = new ArrayList<>();
        for (int i = 0; i < 64; i++) retries.add(() -> store.reserve(order, PRODUCT, 2));
        List<Reservation> responses = concurrently(retries);
        assertThat(stock()).isEqualTo(98);
        assertThat(responses).allSatisfy(reservation -> assertThat(reservation.unitPrice()).isEqualByComparingTo("99000"));
        jdbc.update("UPDATE products SET unit_price = 199000 WHERE id = ?", PRODUCT);
        assertThat(store.reserve(order, PRODUCT, 2).unitPrice()).isEqualByComparingTo("99000");
        assertThatThrownBy(() -> store.reserve(order, PRODUCT, 3)).isInstanceOf(InventoryFailure.class);
        assertThat(stock()).isEqualTo(98);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateCompensationRestoresConfirmedStockExactlyOnce() throws Exception {
        UUID order = UUID.randomUUID();
        store.reserve(order, PRODUCT, 3);
        assertThat(store.confirm(order).status()).isEqualTo(Reservation.Status.CONFIRMED);
        assertThat(store.confirm(order).status()).isEqualTo(Reservation.Status.CONFIRMED);
        List<Callable<Reservation>> releases = new ArrayList<>();
        for (int i = 0; i < 32; i++) releases.add(() -> store.release(order));
        assertThat(concurrently(releases)).allSatisfy(reservation ->
                assertThat(reservation.status()).isEqualTo(Reservation.Status.CANCELLED));
        assertThat(stock()).isEqualTo(100);
        assertThatThrownBy(() -> store.confirm(order)).isInstanceOf(InventoryFailure.class);
    }

    @Test
    void releaseBeforeDelayedReservePersistsACancellationTombstone() {
        UUID order = UUID.randomUUID();
        assertThat(store.release(order).status()).isEqualTo(Reservation.Status.CANCELLED);
        assertThatThrownBy(() -> store.reserve(order, PRODUCT, 1)).isInstanceOfSatisfying(InventoryFailure.class,
                failure -> assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.RESERVATION_CANCELLED));
        assertThatThrownBy(() -> store.confirm(order)).isInstanceOf(InventoryFailure.class);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void compensationRacingReserveAlwaysEndsCancelledWithoutStockLeakage() throws Exception {
        List<UUID> orders = new ArrayList<>();
        List<Callable<Void>> racingRequests = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            UUID order = UUID.randomUUID();
            orders.add(order);
            racingRequests.add(() -> {
                try {
                    store.reserve(order, PRODUCT, 1);
                } catch (InventoryFailure failure) {
                    assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.RESERVATION_CANCELLED);
                }
                return null;
            });
            racingRequests.add(() -> { store.release(order); return null; });
        }
        concurrently(racingRequests);
        assertThat(stock()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE status = 'CANCELLED'", Integer.class))
                .isEqualTo(orders.size());
    }

    @Test
    void failedAcquisitionRollsBackItsPlaceholderAndCanBeRetriedAfterRestock() {
        UUID order = UUID.randomUUID();
        jdbc.update("UPDATE products SET available_stock = 0 WHERE id = ?", PRODUCT);
        assertThatThrownBy(() -> store.reserve(order, PRODUCT, 1)).isInstanceOfSatisfying(InventoryFailure.class,
                failure -> assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.OUT_OF_STOCK));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isZero();
        jdbc.update("UPDATE products SET available_stock = 1 WHERE id = ?", PRODUCT);
        assertThat(store.reserve(order, PRODUCT, 1).status()).isEqualTo(Reservation.Status.RESERVED);
        assertThat(stock()).isZero();
    }

    @Test
    void expiredSaleDoesNotConsumeStock() {
        jdbc.update("""
                UPDATE products SET sale_starts_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                    sale_ends_at = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE id = ?
                """, PRODUCT);
        assertThatThrownBy(() -> store.reserve(UUID.randomUUID(), PRODUCT, 1)).isInstanceOfSatisfying(InventoryFailure.class,
                failure -> assertThat(failure.getReason()).isEqualTo(InventoryFailure.Reason.SALE_CLOSED));
        assertThat(stock()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isZero();
    }

    @Test
    void persistenceFailureAfterStockUpdateRollsBackBothWrites() {
        jdbc.execute("""
                CREATE FUNCTION reject_reservation_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated storage failure'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_reservation BEFORE UPDATE ON reservations
                FOR EACH ROW EXECUTE FUNCTION reject_reservation_update()
                """);
        try {
            assertThatThrownBy(() -> store.reserve(UUID.randomUUID(), PRODUCT, 1)).isInstanceOf(RuntimeException.class);
            assertThat(stock()).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_reservation ON reservations");
            jdbc.execute("DROP FUNCTION reject_reservation_update()");
        }
    }

    private int stock() {
        return jdbc.queryForObject("SELECT available_stock FROM products WHERE id = ?", Integer.class, PRODUCT);
    }

    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<T> results = new ArrayList<>();
            for (Future<T> future : executor.invokeAll(tasks)) results.add(future.get());
            return results;
        }
    }
}
