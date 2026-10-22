package vn.flashsale.auth.application.port;

import java.util.Set;
import vn.flashsale.auth.application.TokenResponse;

public interface TokenIssuer {
    TokenResponse issue(String subject, Set<String> scopes, String tokenKind);
}
