package vn.flashsale.order.adapter.persistence;
import java.math.BigDecimal;
import java.time.Duration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.flashsale.common.ApiException;
import vn.flashsale.order.application.port.OrderRepository;
import vn.flashsale.order.domain.*;
@RequiredArgsConstructor
public class JdbcOrderRepository implements OrderRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int maxPending;
    private final int deadlineSeconds;
    @Override public Order accept(String customer, String key, UUID product, int quantity) {
        if (key == null || key.length() < 8 || key.length() > 128 || !key.matches("[A-Za-z0-9_.:-]+"))
            throw new ApiException(400, "INVALID_IDEMPOTENCY_KEY", "Use 8..128 ASCII letters, digits, dot, colon, dash or underscore");
        if (quantity < 1 || quantity > 5) throw new ApiException(400, "INVALID_QUANTITY", "Quantity must be 1..5");
        return tx.execute(ignored -> {
            // DB-wide admission lock also serializes duplicate creates across order-service replicas.
            jdbc.execute("SELECT pg_advisory_xact_lock(73001)");
            var existing = jdbc.query("SELECT * FROM orders WHERE customer_id=? AND idempotency_key=?", this::map, customer, key);
            if (!existing.isEmpty()) {
                var order = existing.getFirst();
                if (!order.productId().equals(product) || order.quantity() != quantity)
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Key already used for a different checkout");
                return order;
            }
            Long pending = jdbc.queryForObject("SELECT count(*) FROM orders WHERE step <> 'FINISHED'", Long.class);
            if (pending != null && pending >= maxPending)
                throw new ApiException(429, "QUEUE_FULL", "Checkout queue full; retry later with the same key");
            UUID id = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO orders(id,customer_id,idempotency_key,product_id,quantity,status,step,deadline)
                VALUES (?,?,?,?,?,'ACCEPTED','RESERVE',now() + (? * interval '1 second'))
                """, id, customer, key, product, quantity, deadlineSeconds);
            history(id, SagaStep.RESERVE, "ACCEPTED", null);
            return jdbc.queryForObject("SELECT * FROM orders WHERE id=?", this::map, id);
        });
    }
    @Override public Optional<Order> find(UUID id, String customer) {
        return jdbc.query("SELECT * FROM orders WHERE id=? AND customer_id=?", this::map, id, customer).stream().findFirst();
    }
    @Override public Optional<SagaJob> claim(Duration lease) {
        return tx.execute(ignored -> {
            var due = jdbc.query("""
                SELECT * FROM orders WHERE step <> 'FINISHED' AND next_attempt <= now()
                  AND (lease_until IS NULL OR lease_until < now())
                ORDER BY next_attempt,created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, this::map);
            if (due.isEmpty()) return Optional.empty();
            var order = due.getFirst();
            UUID token = UUID.randomUUID();
            jdbc.update("UPDATE orders SET lease_token=?,lease_until=now() + (? * interval '1 millisecond') WHERE id=?", token, lease.toMillis(), order.id());
            return Optional.of(new SagaJob(order, token));
        });
    }
    @Override public boolean advance(SagaJob job, BigDecimal price, BigDecimal amount, String currency) {
        var order = job.order(); var next = order.step().next();
        var status = next != SagaStep.FINISHED ? order.status() : order.step() == SagaStep.CONFIRM ? OrderStatus.COMPLETED : OrderStatus.REJECTED;
        return tx.execute(ignored -> {
            int changed = jdbc.update("""
                UPDATE orders SET step=?,status=?,unit_price=?,amount=?,currency=?,attempts=0,
                failure_code=?,next_attempt=now(),lease_token=NULL,lease_until=NULL,updated_at=now()
                WHERE id=? AND lease_token=? AND lease_until > now() AND step=?
                """, next.name(), status.name(), price, amount, currency,
                order.step().compensating() ? order.failureCode() : null, order.id(), job.leaseToken(), order.step().name());
            if (changed == 1) history(order.id(), order.step(), "SUCCEEDED", null);
            return changed == 1;
        });
    }
    @Override public boolean retry(SagaJob job, Duration delay, String code) {
        return tx.execute(ignored -> {
            int changed = jdbc.update("""
                UPDATE orders SET attempts=LEAST(attempts+1,1000000),next_attempt=now() + (? * interval '1 millisecond'),
                failure_code=CASE WHEN status='COMPENSATING' THEN failure_code ELSE ? END,
                lease_token=NULL,lease_until=NULL,updated_at=now()
                WHERE id=? AND lease_token=? AND lease_until > now() AND step=?
                """, delay.toMillis(), code, job.order().id(), job.leaseToken(), job.order().step().name());
            if (changed == 1) history(job.order().id(), job.order().step(), "RETRY_SCHEDULED", code);
            return changed == 1;
        });
    }
    @Override public boolean compensate(SagaJob job, String code) {
        return tx.execute(ignored -> {
            int changed = jdbc.update("""
                UPDATE orders SET status='COMPENSATING',step='VOID_PAYMENT',attempts=0,failure_code=?,
                next_attempt=now(),lease_token=NULL,lease_until=NULL,updated_at=now()
                WHERE id=? AND lease_token=? AND lease_until > now() AND step=?
                """, code, job.order().id(), job.leaseToken(), job.order().step().name());
            if (changed == 1) history(job.order().id(), job.order().step(), "COMPENSATION_STARTED", code);
            return changed == 1;
        });
    }
    private void history(UUID id, SagaStep step, String event, String detail) {
        jdbc.update("INSERT INTO order_history(order_id,step,event,detail) VALUES(?,?,?,?)", id, step.name(), event, detail);
    }
    private Order map(ResultSet rs, int row) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getString("customer_id"), rs.getObject("product_id", UUID.class),
            rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"), rs.getString("currency"),
            OrderStatus.valueOf(rs.getString("status")), SagaStep.valueOf(rs.getString("step")), rs.getInt("attempts"),
            rs.getTimestamp("deadline").toInstant(), rs.getString("failure_code"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
