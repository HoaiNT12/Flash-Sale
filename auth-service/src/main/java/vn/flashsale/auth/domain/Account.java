package vn.flashsale.auth.domain;

import java.util.Set;

public record Account(String username, String passwordHash, Set<String> scopes, boolean enabled) {
    public Account {
        scopes = Set.copyOf(scopes);
    }
}
