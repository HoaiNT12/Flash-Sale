package vn.flashsale.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationRateLimitFilterTest {
    @Test
    void authenticationBurstGets429WhileHealthRemainsAccessible() throws Exception {
        var filter = new AuthenticationRateLimitFilter();
        var admitted = new AtomicInteger();
        for (int index = 0; index < 31; index++) {
            var request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("127.0.0.1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> admitted.incrementAndGet());
            if (index == 30) {
                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            }
        }
        assertThat(admitted).hasValue(30);
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> admitted.incrementAndGet());
        assertThat(admitted).hasValue(31);
    }
}
