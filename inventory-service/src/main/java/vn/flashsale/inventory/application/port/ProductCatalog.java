package vn.flashsale.inventory.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.flashsale.inventory.domain.Product;

/** Catalog is a read model and may be eventually consistent when backed by a replica. */
public interface ProductCatalog {
    List<Product> list();
    Optional<Product> find(UUID productId);
}
