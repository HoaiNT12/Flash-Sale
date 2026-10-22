package vn.flashsale.common;

import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real signed bearer tokens traverse the resource security chain; no mocked JWT authentication. */
class ResourceAuthorizationTest {
    private static OfflineJwtFixture fixture;
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    @BeforeAll
    static void configure() throws Exception {
        fixture = new OfflineJwtFixture();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-security", Map.of(
                "app.security.jwk-set-uri", fixture.jwksUrl(), "app.security.issuer", OfflineJwtFixture.ISSUER,
                "app.security.audience", OfflineJwtFixture.AUDIENCE)));
        context.register(TestWebConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
        if (fixture != null) fixture.close();
    }

    @Test
    void anonymousCanReachHealthButCannotReachCustomerApis() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerScopeCannotInvokeInternalOrDemoEndpoints() throws Exception {
        String bearer = "Bearer " + fixture.token("shop");
        mvc.perform(get("/api/probe").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/internal/probe").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/demo/probe").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    @Test
    void adminCanConfigureDemoButCannotBypassInternalScope() throws Exception {
        String bearer = "Bearer " + fixture.token("shop admin");
        mvc.perform(post("/demo/probe").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/internal/probe").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    @Test
    void internalScopeDoesNotGrantCustomerApiAccess() throws Exception {
        String bearer = "Bearer " + fixture.token("internal");
        mvc.perform(post("/internal/probe").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/probe").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    @Test
    void invalidSignatureIsRejectedBeforeControllerRuns() throws Exception {
        String tampered = fixture.token("shop");
        int signatureStart = tampered.lastIndexOf('.') + 1;
        char replacement = tampered.charAt(signatureStart) == 'A' ? 'B' : 'A';
        tampered = tampered.substring(0, signatureStart) + replacement + tampered.substring(signatureStart + 1);
        mvc.perform(get("/api/probe").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAudienceReturnsUnauthorizedThroughTheResourceFilter() throws Exception {
        String token = fixture.signed(fixture.trustedKey, fixture.trustedKey.getKeyID(), OfflineJwtFixture.ISSUER,
                null, Instant.now().plusSeconds(300), "shop");
        mvc.perform(get("/api/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ResourceSecurityConfiguration.class, ProbeController.class})
    static class TestWebConfiguration {}

    @RestController
    static class ProbeController {
        @GetMapping({"/api/probe", "/actuator/health"})
        public Map<String, String> read() { return Map.of("result", "ok"); }
        @PostMapping({"/internal/probe", "/demo/probe"})
        public Map<String, String> write() { return Map.of("result", "ok"); }
    }
}
