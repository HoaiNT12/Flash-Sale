package vn.flashsale.auth.adapter.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.flashsale.auth.domain.AuthenticationFailure;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthErrorHandler {
    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<?> authenticationFailure(AuthenticationFailure failure) {
        return ResponseEntity.status(401).body(Map.of("code", failure.getCode(), "message", failure.getMessage(),
                "timestamp", Instant.now().toString()));
    }
}
