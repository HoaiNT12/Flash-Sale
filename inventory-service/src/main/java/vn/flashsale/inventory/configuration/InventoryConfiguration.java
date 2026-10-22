package vn.flashsale.inventory.configuration;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.flashsale.inventory.adapter.out.jdbc.JdbcProductCatalog;
import vn.flashsale.inventory.adapter.out.jdbc.JdbcReservationStore;
import vn.flashsale.inventory.application.InventoryService;
import vn.flashsale.inventory.application.port.ProductCatalog;
import vn.flashsale.inventory.application.port.ReservationStore;

@Configuration(proxyBeanMethods = false)
public class InventoryConfiguration {
    @Bean(name = "primaryDataSource")
    @Primary
    DataSource primaryDataSource(DataSourceProperties properties) {
        HikariDataSource datasource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        datasource.setMaximumPoolSize(12);
        datasource.setMinimumIdle(2);
        datasource.setConnectionTimeout(2000);
        datasource.setPoolName("inventory-primary");
        datasource.addDataSourceProperty("connectTimeout", "2");
        datasource.addDataSourceProperty("socketTimeout", "5");
        datasource.addDataSourceProperty("cancelSignalTimeout", "2");
        datasource.addDataSourceProperty("options", "-c statement_timeout=3000 -c lock_timeout=2000");
        return datasource;
    }

    @Bean(name = "catalogDataSource")
    DataSource catalogDataSource(@Qualifier("primaryDataSource") DataSource primary,
                                 @Value("${app.catalog.jdbc-url:}") String jdbcUrl,
                                 @Value("${app.catalog.username:inventory}") String username,
                                 @Value("${app.catalog.password:inventory_dev}") String password) {
        if (jdbcUrl.isBlank()) return primary;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setReadOnly(true);
        config.setConnectionTimeout(2000);
        config.setPoolName("inventory-catalog");
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", "5");
        config.addDataSourceProperty("cancelSignalTimeout", "2");
        config.addDataSourceProperty("options", "-c statement_timeout=3000 -c lock_timeout=2000");
        return new HikariDataSource(config);
    }

    @Bean
    PlatformTransactionManager transactionManager(@Qualifier("primaryDataSource") DataSource primary) {
        return new DataSourceTransactionManager(primary);
    }

    @Bean
    ReservationStore reservationStore(@Qualifier("primaryDataSource") DataSource primary,
                                      PlatformTransactionManager transactionManager) {
        return new JdbcReservationStore(new JdbcTemplate(primary), new TransactionTemplate(transactionManager));
    }

    @Bean
    ProductCatalog productCatalog(@Qualifier("catalogDataSource") DataSource catalog) {
        return new JdbcProductCatalog(new JdbcTemplate(catalog));
    }

    @Bean
    InventoryService inventoryService(ReservationStore reservations, ProductCatalog catalog) {
        return new InventoryService(reservations, catalog);
    }
}
