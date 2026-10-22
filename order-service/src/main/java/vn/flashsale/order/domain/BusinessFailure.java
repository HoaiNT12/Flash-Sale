package vn.flashsale.order.domain;
import lombok.Getter;
@Getter
public class BusinessFailure extends RuntimeException {
    private final String code;
    public BusinessFailure(String code, String message) { super(message); this.code = code; }
}
