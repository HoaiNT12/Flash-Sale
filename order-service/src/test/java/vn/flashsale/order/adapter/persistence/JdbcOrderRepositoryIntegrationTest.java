package vn.flashsale.order.adapter.persistence;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import vn.flashsale.common.ApiException;
import vn.flashsale.order.domain.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers(disabledWithoutDocker = true)
class JdbcOrderRepositoryIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private JdbcOrderRepository orders;
    @BeforeEach void setUp() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate(); jdbc = new JdbcTemplate(ds);
        jdbc.execute("TRUNCATE order_history,orders RESTART IDENTITY CASCADE");
        orders = new JdbcOrderRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), 100, 120);
    }
    @Test void duplicateConcurrentCheckoutProducesOneOrderAndRejectsPayloadReuse() throws Exception {
        UUID product = UUID.randomUUID(); Set<UUID> ids = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) futures.add(executor.submit(() -> ids.add(orders.accept("alice", "checkout-123", product, 1).id())));
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertThat(ids).hasSize(1);
        assertThatThrownBy(() -> orders.accept("alice", "checkout-123", product, 2)).isInstanceOf(ApiException.class);
        assertThat(orders.find(ids.iterator().next(), "bob")).isEmpty();
    }
    @Test void expiredLeaseIsReclaimedAndStaleOwnerCannotAdvance() {
        var accepted = orders.accept("alice", "lease-test", UUID.randomUUID(), 1);
        var stale = orders.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(orders.claim(Duration.ofSeconds(30))).isEmpty();
        jdbc.update("UPDATE orders SET lease_until=now()-interval '1 second' WHERE id=?", accepted.id());
        var current = orders.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(current.leaseToken()).isNotEqualTo(stale.leaseToken());
        assertThat(orders.compensate(stale, "STALE_WORKER")).isFalse();
        assertThat(orders.compensate(current, "CHECKOUT_DEADLINE")).isTrue();
        assertThat(orders.find(accepted.id(), "alice").orElseThrow().step()).isEqualTo(SagaStep.VOID_PAYMENT);
    }
    @Test void boundedAdmissionStillReturnsAnExistingOrderWhenQueueIsFull() {
        var small = new JdbcOrderRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), 1, 120);
        UUID product = UUID.randomUUID(); var first = small.accept("alice", "first-key", product, 1);
        assertThat(small.accept("alice", "first-key", product, 1).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> small.accept("bob", "second-key", product, 1))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(429));
    }
}
