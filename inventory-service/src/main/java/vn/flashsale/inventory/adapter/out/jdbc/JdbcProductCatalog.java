package vn.flashsale.inventory.adapter.out.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.flashsale.inventory.application.port.ProductCatalog;
import vn.flashsale.inventory.domain.Product;

@RequiredArgsConstructor
public final class JdbcProductCatalog implements ProductCatalog {
    private final JdbcTemplate catalogJdbc;

    @Override
    public List<Product> list() {
        return catalogJdbc.query("SELECT * FROM products ORDER BY name, id", JdbcProductCatalog::map);
    }

    @Override
    public Optional<Product> find(UUID productId) {
        return catalogJdbc.query("SELECT * FROM products WHERE id = ?", JdbcProductCatalog::map, productId)
                .stream().findFirst();
    }

    static Product map(ResultSet rs, int rowNumber) throws SQLException {
        return new Product(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getBigDecimal("unit_price"), rs.getString("currency"), rs.getInt("available_stock"),
                rs.getTimestamp("sale_starts_at").toInstant(), rs.getTimestamp("sale_ends_at").toInstant());
    }
}
