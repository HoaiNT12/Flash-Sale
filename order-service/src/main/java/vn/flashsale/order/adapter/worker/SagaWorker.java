package vn.flashsale.order.adapter.worker;
import java.time.Duration;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import vn.flashsale.order.application.SagaProcessor;
import vn.flashsale.order.application.port.OrderRepository;
@Slf4j
public class SagaWorker {
    private final OrderRepository orders;
    private final SagaProcessor processor;
    private final Semaphore capacity;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;
    public SagaWorker(OrderRepository orders, SagaProcessor processor, int concurrency) {
        if (concurrency < 1 || concurrency > 256) throw new IllegalArgumentException("Worker concurrency must be 1..256");
        this.orders = orders; this.processor = processor; this.capacity = new Semaphore(concurrency);
    }
    @Scheduled(fixedDelayString = "${app.saga.poll-ms:200}")
    public void poll() {
        if (stopping) return;
        while (capacity.tryAcquire()) {
            try {
                var job = orders.claim(Duration.ofSeconds(30));
                if (job.isEmpty()) { capacity.release(); return; }
                workers.submit(() -> {
                    try { processor.process(job.get()); }
                    catch (Exception e) { log.error("Saga worker failed for {}; lease will recover", job.get().order().id(), e); }
                    finally { capacity.release(); }
                });
            } catch (Exception e) { capacity.release(); log.warn("Unable to claim saga work: {}", e.toString()); return; }
        }
    }
    @PreDestroy public void close() { stopping = true; workers.close(); }
}
