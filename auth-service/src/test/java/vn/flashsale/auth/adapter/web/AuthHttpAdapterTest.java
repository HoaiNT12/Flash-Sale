package vn.flashsale.auth.adapter.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.flashsale.auth.application.AuthenticationService;
import vn.flashsale.auth.application.TokenResponse;
import vn.flashsale.auth.domain.AuthenticationFailure;
import vn.flashsale.common.ApiErrorHandler;

class AuthHttpAdapterTest {
    @Test
    void domainCredentialFailureMapsTo401BeforeCommonFallbackAdvice() throws Exception {
        var authentication = mock(AuthenticationService.class);
        when(authentication.login("alice", "wrong"))
                .thenThrow(new AuthenticationFailure("INVALID_CREDENTIALS", "Invalid username or password"));
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(authentication, null))
                .setControllerAdvice(new AuthErrorHandler(), new ApiErrorHandler()).build();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void tokenResponseKeepsAccessTokenContractForServiceClients() throws Exception {
        var authentication = mock(AuthenticationService.class);
        when(authentication.serviceToken("order-service", "secret"))
                .thenReturn(new TokenResponse("signed.jwt", "Bearer", 900));
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(authentication, null)).build();
        mvc.perform(post("/api/auth/service-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"order-service\",\"clientSecret\":\"secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("signed.jwt"))
                .andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.expiresIn").value(900));
    }
}
