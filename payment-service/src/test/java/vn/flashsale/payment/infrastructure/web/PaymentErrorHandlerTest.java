package vn.flashsale.payment.infrastructure.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.flashsale.common.ApiErrorHandler;
import vn.flashsale.common.DemoFaults;
import vn.flashsale.payment.application.port.PaymentUseCases;
import vn.flashsale.payment.domain.PaymentFailure;

class PaymentErrorHandlerTest {
    @ParameterizedTest
    @MethodSource("failures")
    void businessFailuresKeepTheirStableHttpContractAheadOfGenericAdvice(PaymentFailure.Reason reason, int expectedStatus) throws Exception {
        var payments = mock(PaymentUseCases.class);
        when(payments.charge(any(), anyString(), any(), anyString()))
                .thenThrow(new PaymentFailure(reason, "Example failure"));
        var mvc = MockMvcBuilders.standaloneSetup(new PaymentController(payments, new DemoFaults(false)))
                .setControllerAdvice(new ApiErrorHandler(), new PaymentErrorHandler()).build();
        mvc.perform(post("/internal/payments/charge").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId":"11111111-1111-1111-1111-111111111111","customerId":"alice","amount":50000,"currency":"VND"}
                """))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(reason.name()))
                .andExpect(jsonPath("$.message").value("Example failure"));
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(PaymentFailure.Reason.INVALID_PAYMENT, 400),
                Arguments.of(PaymentFailure.Reason.PAYMENT_PAYLOAD_CONFLICT, 409),
                Arguments.of(PaymentFailure.Reason.PAYMENT_CANCELLED, 409),
                Arguments.of(PaymentFailure.Reason.INSUFFICIENT_FUNDS, 409),
                Arguments.of(PaymentFailure.Reason.PAYMENT_NOT_FOUND, 404),
                Arguments.of(PaymentFailure.Reason.WALLET_NOT_FOUND, 404));
    }
}
