package vn.flashsale.payment.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.flashsale.common.DemoFaults;
import vn.flashsale.payment.application.port.PaymentUseCases;
import vn.flashsale.payment.domain.Payment;

@RestController
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentUseCases payments;
    private final DemoFaults demoFaults;

    public record ChargeRequest(@NotNull UUID orderId, @NotBlank @Size(max = 100) String customerId,
                                @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                @NotBlank @Pattern(regexp = "VND") String currency) { }
    public record PaymentResponse(UUID orderId, String customerId, BigDecimal amount, String currency, String status) {
        static PaymentResponse from(Payment payment) {
            return new PaymentResponse(payment.getOrderId(), payment.getCustomerId(),
                    payment.getMoney() == null ? null : payment.getMoney().getAmount(),
                    payment.getMoney() == null ? null : payment.getMoney().getCurrency(), payment.getStatus().name());
        }
    }
    public record WalletResponse(String customerId, BigDecimal balance, String currency) { }

    @PostMapping("/internal/payments/charge")
    public PaymentResponse charge(@Valid @RequestBody ChargeRequest request) {
        demoFaults.beforeRequest();
        return PaymentResponse.from(payments.charge(request.orderId(), request.customerId(), request.amount(), request.currency()));
    }

    @PostMapping("/internal/payments/{orderId}/void")
    public PaymentResponse voidPayment(@PathVariable UUID orderId) {
        demoFaults.beforeRequest();
        return PaymentResponse.from(payments.voidPayment(orderId));
    }

    @GetMapping("/api/payments/{orderId}")
    public PaymentResponse find(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("SCOPE_admin"));
        return PaymentResponse.from(payments.find(orderId, jwt.getSubject(), admin));
    }

    @GetMapping("/api/wallets/me")
    public WalletResponse wallet(@AuthenticationPrincipal Jwt jwt) {
        return new WalletResponse(jwt.getSubject(), payments.walletBalance(jwt.getSubject()), "VND");
    }
}
