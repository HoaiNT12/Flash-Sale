package vn.flashsale.order.application;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.flashsale.order.application.port.*;
import vn.flashsale.order.domain.*;
import static org.mockito.Mockito.*;
class SagaProcessorTest {
    private final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    private final OrderRepository orders = mock(OrderRepository.class);
    private final InventoryPort inventory = mock(InventoryPort.class);
    private final PaymentPort payment = mock(PaymentPort.class);
    private SagaProcessor processor(int attempts) {
        return new SagaProcessor(orders, inventory, payment, new RetryPolicy(attempts, Duration.ofMillis(500), Duration.ofSeconds(30)), Clock.fixed(now, ZoneOffset.UTC));
    }
    private SagaJob job(SagaStep step, int attempts, Instant deadline) {
        return new SagaJob(new Order(UUID.randomUUID(), "alice", UUID.randomUUID(), 2, new BigDecimal("99000"), new BigDecimal("198000"), "VND",
            step.compensating() ? OrderStatus.COMPENSATING : OrderStatus.ACCEPTED, step, attempts, deadline, "DECLINED", now, now), UUID.randomUUID());
    }
    @Test void totalUsesAuthoritativeInventoryQuote() {
        var job = job(SagaStep.RESERVE, 0, now.plusSeconds(120));
        when(inventory.reserve(job.order().id(), job.order().productId(), 2)).thenReturn(new InventoryPort.Reservation(new BigDecimal("12345.00"), "VND"));
        processor(8).process(job);
        verify(orders).advance(job, new BigDecimal("12345.00"), new BigDecimal("24690.00"), "VND");
        verifyNoInteractions(payment);
    }
    @Test void outOfStockStartsCompensationWithoutCharging() {
        var job = job(SagaStep.RESERVE, 0, now.plusSeconds(120));
        when(inventory.reserve(any(), any(), anyInt())).thenThrow(new BusinessFailure("OUT_OF_STOCK", "Out of stock"));
        processor(8).process(job);
        verify(orders).compensate(job, "OUT_OF_STOCK"); verifyNoInteractions(payment);
    }
    @Test void timeoutSchedulesDurableRetryWithoutImmediateSecondCall() {
        var job = job(SagaStep.PAY, 0, now.plusSeconds(120));
        doThrow(new IllegalStateException("Lost response")).when(payment).charge(any(), anyString(), any(), anyString());
        processor(8).process(job);
        verify(payment, times(1)).charge(job.order().id(), "alice", new BigDecimal("198000"), "VND");
        verify(orders).retry(eq(job), argThat(delay -> delay.toMillis() >= 250 && delay.toMillis() <= 500), eq("DOWNSTREAM_UNAVAILABLE"));
        verify(orders, never()).compensate(any(), anyString());
    }
    @Test void uncertainPaymentAtRetryLimitMustVoidBeforeReleasingStock() {
        var job = job(SagaStep.PAY, 7, now.plusSeconds(120));
        doThrow(new IllegalStateException("Wallet debited but response lost")).when(payment).charge(any(), anyString(), any(), anyString());
        processor(8).process(job);
        verify(orders).compensate(job, "DOWNSTREAM_UNAVAILABLE");
        verify(inventory, never()).release(any());
        var compensating = new SagaJob(new Order(job.order().id(), "alice", job.order().productId(), 2, job.order().unitPrice(), job.order().amount(), "VND",
            OrderStatus.COMPENSATING, SagaStep.VOID_PAYMENT, 0, now.minusSeconds(1), "DOWNSTREAM_UNAVAILABLE", now, now), UUID.randomUUID());
        processor(8).process(compensating);
        verify(payment).voidPayment(job.order().id());
        verify(orders).advance(compensating, job.order().unitPrice(), job.order().amount(), "VND");
    }
    @Test void compensationKeepsRetryingAfterForwardDeadlineAndLimit() {
        var job = job(SagaStep.VOID_PAYMENT, 100, now.minusSeconds(500));
        doThrow(new IllegalStateException("Network unavailable")).when(payment).voidPayment(any());
        processor(8).process(job);
        verify(orders).retry(eq(job), argThat(delay -> delay.toSeconds() >= 15 && delay.toSeconds() <= 30), anyString());
        verify(orders, never()).compensate(any(), anyString()); verify(inventory, never()).release(any());
    }
    @Test void expiredCheckoutDoesNotStartNewForwardSideEffect() {
        var job = job(SagaStep.RESERVE, 0, now);
        processor(8).process(job);
        verify(orders).compensate(job, "CHECKOUT_DEADLINE"); verifyNoInteractions(inventory, payment);
    }
}
