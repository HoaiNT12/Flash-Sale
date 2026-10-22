package vn.flashsale.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import vn.flashsale.auth.application.port.TokenIssuer;
import vn.flashsale.auth.domain.Account;
import vn.flashsale.auth.domain.ServiceClient;
import vn.flashsale.auth.domain.port.AccountRepository;
import vn.flashsale.auth.domain.port.ServiceClientRepository;
import vn.flashsale.auth.infrastructure.security.BcryptPasswordVerifier;
import vn.flashsale.auth.domain.AuthenticationFailure;

class AuthenticationServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ServiceClientRepository clients = mock(ServiceClientRepository.class);
    private final TokenIssuer tokens = mock(TokenIssuer.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuthenticationService service = new AuthenticationService(accounts, clients,
            new BcryptPasswordVerifier(encoder), tokens);

    @Test
    void customerLoginNeverGetsInternalScopeEvenWhenRepositoryContainsIt() {
        when(accounts.findByUsername("alice")).thenReturn(Optional.of(new Account("alice",
                encoder.encode("Demo@123"), Set.of("shop", "internal"), true)));
        when(tokens.issue("alice", Set.of("shop"), "customer")).thenReturn(new TokenResponse("jwt", "Bearer", 900));
        assertThat(service.login("alice", "Demo@123").accessToken()).isEqualTo("jwt");
        verify(tokens).issue("alice", Set.of("shop"), "customer");
    }

    @Test
    void machineTokenOnlyGetsInternalScope() {
        when(clients.findByClientId("order-service")).thenReturn(Optional.of(new ServiceClient("order-service",
                encoder.encode("order_dev_secret"), true)));
        service.serviceToken("order-service", "order_dev_secret");
        verify(tokens).issue("service:order-service", Set.of("internal"), "service");
        verify(accounts, never()).findByUsername(anyString());
    }

    @Test
    void disabledAccountOrWrongPasswordCannotIssueToken() {
        when(accounts.findByUsername("alice")).thenReturn(Optional.of(new Account("alice",
                encoder.encode("correct"), Set.of("shop"), false)));
        assertThatThrownBy(() -> service.login("alice", "correct")).isInstanceOfSatisfying(AuthenticationFailure.class,
                failure -> assertThat(failure.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        when(accounts.findByUsername("alice")).thenReturn(Optional.of(new Account("alice",
                encoder.encode("correct"), Set.of("shop"), true)));
        assertThatThrownBy(() -> service.login("alice", "wrong")).isInstanceOfSatisfying(AuthenticationFailure.class,
                failure -> assertThat(failure.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(tokens, never()).issue(anyString(), org.mockito.ArgumentMatchers.anySet(), anyString());
    }

    @Test
    void customerCredentialsCannotBeExchangedForServiceToken() {
        when(clients.findByClientId("alice")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.serviceToken("alice", "Demo@123")).isInstanceOfSatisfying(AuthenticationFailure.class,
                failure -> assertThat(failure.getCode()).isEqualTo("INVALID_CLIENT"));
        verify(tokens, never()).issue(anyString(), org.mockito.ArgumentMatchers.anySet(), anyString());
    }

    @Test
    void overlongUtf8PasswordReturnsInvalidCredentialsInsteadOfBcryptFailure() {
        when(accounts.findByUsername("alice")).thenReturn(Optional.of(new Account("alice",
                encoder.encode("correct"), Set.of("shop"), true)));
        assertThatThrownBy(() -> service.login("alice", "ấ".repeat(30))).isInstanceOfSatisfying(AuthenticationFailure.class,
                failure -> assertThat(failure.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(tokens, never()).issue(anyString(), org.mockito.ArgumentMatchers.anySet(), anyString());
    }
}
