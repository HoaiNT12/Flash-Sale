package vn.flashsale.order.domain;
public enum SagaStep {
    RESERVE, PAY, CONFIRM, VOID_PAYMENT, RELEASE, FINISHED;
    public boolean compensating() { return this == VOID_PAYMENT || this == RELEASE; }
    public SagaStep next() {
        return switch (this) {
            case RESERVE -> PAY;
            case PAY -> CONFIRM;
            case CONFIRM, RELEASE -> FINISHED;
            case VOID_PAYMENT -> RELEASE;
            case FINISHED -> throw new IllegalStateException("Saga already finished");
        };
    }
}
