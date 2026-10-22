package vn.flashsale.auth.domain;

public final class AuthenticationFailure extends RuntimeException {
    private final String code;

    public AuthenticationFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
