package vn.flashsale.order.domain;
import java.util.UUID;
public record SagaJob(Order order, UUID leaseToken) {}
