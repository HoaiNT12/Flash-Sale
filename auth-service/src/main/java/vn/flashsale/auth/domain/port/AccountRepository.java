package vn.flashsale.auth.domain.port;

import java.util.Optional;
import vn.flashsale.auth.domain.Account;

public interface AccountRepository {
    Optional<Account> findByUsername(String username);
}
