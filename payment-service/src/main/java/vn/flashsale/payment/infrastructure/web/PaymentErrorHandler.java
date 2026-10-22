package vn.flashsale.payment.infrastructure.web;

import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.flashsale.payment.domain.PaymentFailure;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class PaymentErrorHandler {
    @ExceptionHandler(PaymentFailure.class)
    public ResponseEntity<FailureResponse> business(PaymentFailure failure) {
        int status = switch (failure.getReason()) {
            case INVALID_PAYMENT -> 400;
            case PAYMENT_PAYLOAD_CONFLICT, PAYMENT_CANCELLED, INSUFFICIENT_FUNDS -> 409;
            case PAYMENT_NOT_FOUND, WALLET_NOT_FOUND -> 404;
        };
        return ResponseEntity.status(status).body(new FailureResponse(
                failure.getReason().name(), failure.getMessage(), Instant.now()));
    }

    public record FailureResponse(String code, String message, Instant timestamp) { }
}
