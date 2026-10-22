package vn.flashsale.order.adapter.web;
import org.junit.jupiter.api.*;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.flashsale.common.ApiErrorHandler;
import vn.flashsale.order.application.port.OrderRepository;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class OrderRequestValidationTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders)).setControllerAdvice(new ApiErrorHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    @Test void missingIdempotencyHeaderIsPermanentClientError() throws Exception {
        mvc.perform(post("/api/orders").contentType("application/json")
            .content("{\"productId\":\"11111111-1111-1111-1111-111111111111\",\"quantity\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(orders);
    }
    @Test void malformedOrderIdentifierIsPermanentClientError() throws Exception {
        mvc.perform(get("/api/orders/not-a-uuid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(orders);
    }
}
