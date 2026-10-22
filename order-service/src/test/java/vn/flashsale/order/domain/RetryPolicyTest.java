package vn.flashsale.order.domain;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RetryPolicyTest {
    @Test void exponentialDelayIsCappedAndJitterSpreadsRetries() {
        var policy = new RetryPolicy(8, Duration.ofMillis(500), Duration.ofSeconds(30));
        assertThat(policy.delay(0, 0)).isEqualTo(Duration.ofMillis(250));
        assertThat(policy.delay(1, 1)).isEqualTo(Duration.ofMillis(1000));
        assertThat(policy.delay(1000000, 1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delay(1000000, 0)).isEqualTo(Duration.ofSeconds(15));
        assertThatThrownBy(() -> policy.delay(1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
