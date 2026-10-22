package vn.flashsale.auth.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded local limiter. A production gateway must enforce a shared cluster-wide limit. */
public final class AuthenticationRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_KEYS = 2048;
    private static final long WINDOW_MILLIS = 60_000;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !("/api/auth/login".equals(request.getRequestURI())
                || "/api/auth/service-token".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        int limit = request.getRequestURI().endsWith("service-token") ? 120 : 30;
        Window window;
        // Admission is synchronized so attackers cannot grow the map beyond MAX_KEYS.
        synchronized (windows) {
            window = windows.get(key);
            if (window == null) {
                if (windows.size() >= MAX_KEYS) {
                    windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > 5 * WINDOW_MILLIS);
                }
                if (windows.size() < MAX_KEYS) {
                    window = new Window(now);
                    windows.put(key, window);
                }
            }
        }
        if (window == null || !window.admit(now, limit)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"code\":\"AUTH_RATE_LIMITED\",\"message\":\"Too many authentication attempts\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        private volatile long startedAt;
        private int requests;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }

        private synchronized boolean admit(long now, int limit) {
            if (now - startedAt >= WINDOW_MILLIS) {
                startedAt = now;
                requests = 0;
            }
            return ++requests <= limit;
        }
    }
}
