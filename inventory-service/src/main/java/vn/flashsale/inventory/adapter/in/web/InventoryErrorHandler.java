package vn.flashsale.inventory.adapter.in.web;

import java.time.Instant;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.flashsale.inventory.domain.InventoryFailure;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InventoryErrorHandler {
    @ExceptionHandler(InventoryFailure.class)
    ResponseEntity<FailureResponse> domainFailure(InventoryFailure failure) {
        int status = switch (failure.getReason()) {
            case PRODUCT_NOT_FOUND, RESERVATION_NOT_FOUND -> 404;
            case INVALID_QUANTITY -> 400;
            default -> 409;
        };
        return ResponseEntity.status(status).body(new FailureResponse(status, failure.getReason().name(),
                failure.getMessage(), Instant.now()));
    }

    @ExceptionHandler({CannotAcquireLockException.class, QueryTimeoutException.class, CannotGetJdbcConnectionException.class})
    ResponseEntity<FailureResponse> capacityFailure(Exception failure) {
        return ResponseEntity.status(503).header("Retry-After", "1").body(
                new FailureResponse(503, "INVENTORY_BUSY", "Inventory is busy; retry the same order id", Instant.now()));
    }

    public record FailureResponse(int status, String code, String message, Instant timestamp) {}
}
