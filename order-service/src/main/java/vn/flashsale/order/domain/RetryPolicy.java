package vn.flashsale.order.domain;
import java.time.Duration;
import java.time.Instant;
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {
    public boolean shouldCompensate(Order order, Instant now) {
        return !order.step().compensating() && (order.attempts() + 1 >= maxAttempts || !now.isBefore(order.deadline()));
    }
    public Duration delay(int attempt, double jitter) {
        if (jitter < 0 || jitter > 1) throw new IllegalArgumentException("jitter must be 0..1");
        long capped = Math.min(maxDelay.toMillis(), baseDelay.toMillis() * (1L << Math.min(16, Math.max(0, attempt))));
        return Duration.ofMillis((long) (capped * (0.5 + 0.5 * jitter)));
    }
}
