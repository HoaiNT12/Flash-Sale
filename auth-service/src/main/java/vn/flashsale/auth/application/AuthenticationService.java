package vn.flashsale.auth.application;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import vn.flashsale.auth.application.port.PasswordVerifier;
import vn.flashsale.auth.application.port.TokenIssuer;
import vn.flashsale.auth.domain.Account;
import vn.flashsale.auth.domain.ServiceClient;
import vn.flashsale.auth.domain.port.AccountRepository;
import vn.flashsale.auth.domain.port.ServiceClientRepository;
import vn.flashsale.auth.domain.AuthenticationFailure;

@RequiredArgsConstructor
public class AuthenticationService {
    private final AccountRepository accounts;
    private final ServiceClientRepository clients;
    private final PasswordVerifier passwords;
    private final TokenIssuer tokens;

    public TokenResponse login(String username, String password) {
        Account account = accounts.findByUsername(username).orElse(null);
        boolean verified = passwords.matches(password, account == null ? passwords.dummyHash() : account.passwordHash());
        if (account == null || !verified || !account.enabled()) {
            throw new AuthenticationFailure("INVALID_CREDENTIALS", "Invalid username or password");
        }
        // A customer can never acquire inter-service privileges through the login endpoint.
        Set<String> scopes = account.scopes().stream().filter(scope -> scope.equals("shop") || scope.equals("admin"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return tokens.issue(account.username(), scopes, "customer");
    }

    public TokenResponse serviceToken(String clientId, String clientSecret) {
        ServiceClient client = clients.findByClientId(clientId).orElse(null);
        boolean verified = passwords.matches(clientSecret, client == null ? passwords.dummyHash() : client.secretHash());
        if (client == null || !verified || !client.enabled()) {
            throw new AuthenticationFailure("INVALID_CLIENT", "Invalid service credentials");
        }
        return tokens.issue("service:" + client.clientId(), Set.of("internal"), "service");
    }
}
