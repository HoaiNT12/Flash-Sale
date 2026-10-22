package vn.flashsale.auth.application.port;

public interface PasswordVerifier {
    boolean matches(String password, String hash);
    String dummyHash();
}
