package vn.flashsale.auth.infrastructure.persistence;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.flashsale.auth.domain.Account;
import vn.flashsale.auth.domain.ServiceClient;
import vn.flashsale.auth.domain.port.AccountRepository;
import vn.flashsale.auth.domain.port.ServiceClientRepository;

@Repository
@RequiredArgsConstructor
public class JdbcIdentityRepository implements AccountRepository, ServiceClientRepository {
    private final JdbcTemplate jdbc;

    @Override
    public Optional<Account> findByUsername(String username) {
        return jdbc.query("SELECT username, password_hash, scopes, enabled FROM accounts WHERE username = ?",
                (row, index) -> new Account(row.getString("username"), row.getString("password_hash"),
                        Arrays.stream(row.getString("scopes").split(" ")).filter(value -> !value.isBlank())
                                .collect(Collectors.toUnmodifiableSet()), row.getBoolean("enabled")), username)
                .stream().findFirst();
    }

    @Override
    public Optional<ServiceClient> findByClientId(String clientId) {
        return jdbc.query("SELECT client_id, secret_hash, enabled FROM service_clients WHERE client_id = ?",
                (row, index) -> new ServiceClient(row.getString("client_id"), row.getString("secret_hash"),
                        row.getBoolean("enabled")), clientId).stream().findFirst();
    }
}
