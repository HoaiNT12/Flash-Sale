package vn.flashsale.inventory.adapter.out.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.flashsale.inventory.application.port.ReservationStore;
import vn.flashsale.inventory.domain.InventoryFailure;
import vn.flashsale.inventory.domain.Product;
import vn.flashsale.inventory.domain.Reservation;
import static vn.flashsale.inventory.domain.InventoryFailure.Reason.*;

/**
 * Primary database only. The order row serializes retries and compensation;
 * the conditional product update serializes competing purchases for the SKU.
 */
@RequiredArgsConstructor
public final class JdbcReservationStore implements ReservationStore {
    private final JdbcTemplate primaryJdbc;
    private final TransactionTemplate transactions;

    @Override
    public Reservation reserve(UUID orderId, UUID productId, int quantity) {
        Reservation.validateRequest(productId, quantity);
        return Objects.requireNonNull(transactions.execute(tx -> {
            setTimeouts();
            primaryJdbc.update("""
                    INSERT INTO reservations (order_id, status) VALUES (?, 'PENDING')
                    ON CONFLICT (order_id) DO NOTHING
                    """, orderId);
            Reservation current = locked(orderId);
            if (current.status() == Reservation.Status.CANCELLED) {
                throw new InventoryFailure(RESERVATION_CANCELLED, "Order has already been cancelled");
            }
            if (current.status() != Reservation.Status.PENDING) {
                current.assertSameRequest(productId, quantity);
                return current;
            }

            // Never read availability or price from the catalog replica for a purchase.
            Product acquired = primaryJdbc.query("""
                    UPDATE products SET available_stock = available_stock - ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND available_stock >= ?
                      AND sale_starts_at <= statement_timestamp() AND sale_ends_at > statement_timestamp()
                    RETURNING *
                    """, JdbcProductCatalog::map, quantity, productId, quantity).stream().findFirst()
                    .orElseThrow(() -> acquisitionFailure(productId));
            Reservation reserved = current.reserve(productId, quantity, acquired.unitPrice(), acquired.currency());
            save(reserved);
            return reserved;
        }));
    }

    @Override
    public Reservation confirm(UUID orderId) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            setTimeouts();
            Reservation confirmed = locked(orderId).confirm();
            save(confirmed);
            return confirmed;
        }));
    }

    @Override
    public Reservation release(UUID orderId) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            setTimeouts();
            // Tombstone blocks a delayed reserve that arrives after compensation.
            primaryJdbc.update("""
                    INSERT INTO reservations (order_id, status) VALUES (?, 'CANCELLED')
                    ON CONFLICT (order_id) DO NOTHING
                    """, orderId);
            Reservation current = locked(orderId);
            if (current.holdsInventory()) {
                int changed = primaryJdbc.update("""
                        UPDATE products SET available_stock = available_stock + ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, current.quantity(), current.productId());
                if (changed != 1) throw new IllegalStateException("Reserved product no longer exists");
            }
            Reservation released = current.release();
            save(released);
            return released;
        }));
    }

    private void setTimeouts() {
        primaryJdbc.execute("SET LOCAL lock_timeout = '2000ms'");
        primaryJdbc.execute("SET LOCAL statement_timeout = '3000ms'");
    }

    private Reservation locked(UUID orderId) {
        return primaryJdbc.query("SELECT * FROM reservations WHERE order_id = ? FOR UPDATE",
                        JdbcReservationStore::map, orderId).stream().findFirst()
                .orElseThrow(() -> new InventoryFailure(RESERVATION_NOT_FOUND, "Order has no inventory reservation"));
    }

    private InventoryFailure acquisitionFailure(UUID productId) {
        Product product = primaryJdbc.query("SELECT * FROM products WHERE id = ?", JdbcProductCatalog::map, productId)
                .stream().findFirst().orElse(null);
        if (product == null) return new InventoryFailure(PRODUCT_NOT_FOUND, "Product was not found");
        Instant databaseNow = primaryJdbc.queryForObject("SELECT statement_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
        if (!product.isOnSale(Objects.requireNonNull(databaseNow))) {
            return new InventoryFailure(SALE_CLOSED, "Product is outside its flash sale window");
        }
        return new InventoryFailure(OUT_OF_STOCK, "Insufficient stock for this request");
    }

    private void save(Reservation reservation) {
        primaryJdbc.update("""
                UPDATE reservations SET product_id = ?, quantity = ?, unit_price = ?, currency = ?,
                    status = ?, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?
                """, reservation.productId(), reservation.quantity(), reservation.unitPrice(), reservation.currency(),
                reservation.status().name(), reservation.orderId());
    }

    private static Reservation map(ResultSet rs, int rowNumber) throws SQLException {
        return new Reservation(rs.getObject("order_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("quantity", Integer.class), rs.getBigDecimal("unit_price"), rs.getString("currency"),
                Reservation.Status.valueOf(rs.getString("status")));
    }
}
