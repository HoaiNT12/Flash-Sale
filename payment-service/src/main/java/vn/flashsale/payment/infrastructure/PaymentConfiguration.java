package vn.flashsale.payment.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.flashsale.payment.application.PaymentService;
import vn.flashsale.payment.application.port.PaymentLedger;
import vn.flashsale.payment.application.port.PaymentRepository;
import vn.flashsale.payment.application.port.PaymentUseCases;
import vn.flashsale.payment.application.port.WalletRepository;

@Configuration
public class PaymentConfiguration {
    @Bean
    public PaymentUseCases paymentUseCases(PaymentRepository payments, WalletRepository wallets, PaymentLedger ledger) {
        return new TransactionPaymentFacade(new PaymentService(payments, wallets, ledger));
    }
}
