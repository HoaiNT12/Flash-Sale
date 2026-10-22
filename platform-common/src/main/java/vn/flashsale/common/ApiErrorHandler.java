package vn.flashsale.common;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@Slf4j
@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException e) { return error(e.getStatus(), e.getCode(), e.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class,
        MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e) { return error(400, "INVALID_REQUEST", "Invalid request fields"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) { log.error("Unhandled request failure", e); return error(503, "TEMPORARILY_UNAVAILABLE", "Retry later using the same idempotency key"); }
    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "timestamp", Instant.now().toString()));
    }
}
