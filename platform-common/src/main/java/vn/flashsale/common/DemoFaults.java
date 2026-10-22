package vn.flashsale.common;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class DemoFaults {
    public record Settings(int delayMs, boolean fail) {
        public Settings { if (delayMs < 0 || delayMs > 10000) throw new IllegalArgumentException("delayMs must be 0..10000"); }
    }
    private final boolean enabled;
    private final AtomicReference<Settings> settings = new AtomicReference<>(new Settings(0, false));
    public DemoFaults(@Value("${demo.enabled:false}") boolean enabled) { this.enabled = enabled; }
    public Settings get() { return settings.get(); }
    public void configure(Settings settings) { this.settings.set(settings); }
    public void beforeRequest() {
        if (!enabled) return;
        var value = settings.get();
        try { Thread.sleep(value.delayMs()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(503, "INTERRUPTED", "Request interrupted"); }
        if (value.fail()) throw new ApiException(503, "DEMO_OVERLOAD", "Simulated downstream overload");
    }
}
