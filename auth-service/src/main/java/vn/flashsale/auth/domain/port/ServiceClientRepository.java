package vn.flashsale.auth.domain.port;

import java.util.Optional;
import vn.flashsale.auth.domain.ServiceClient;

public interface ServiceClientRepository {
    Optional<ServiceClient> findByClientId(String clientId);
}
