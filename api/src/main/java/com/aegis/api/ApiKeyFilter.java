package com.aegis.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lightweight security gate.
 *
 * <ul>
 *   <li><b>Public:</b> POST /api/intake, GET /api/status/**, /health, /actuator/health/**,
 *       and CORS pre-flight (OPTIONS).</li>
 *   <li><b>Protected</b> (require a valid {@code X-API-Key}): everything else — the operator
 *       endpoints and /actuator/metrics.</li>
 *   <li>The public intake endpoint is rate-limited per client IP to curb spam and LLM-cost abuse.</li>
 * </ul>
 *
 * <p>This is a pragmatic gate for a single-tenant deployment. For production, replace with real
 * user auth (OAuth2 / JWT), put it behind TLS, and add a WAF.
 */
@Component
@Order(2)
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final String apiKey;
    private final int intakePerMinute;
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    public ApiKeyFilter(@Value("${aegis.api-key:}") String apiKey,
                        @Value("${aegis.intake-rate-per-minute:20}") int intakePerMinute) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.intakePerMinute = intakePerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String method = req.getMethod();
        String path = req.getRequestURI();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(req, res);
            return;
        }

        boolean publicPath =
                ("POST".equalsIgnoreCase(method) && path.equals("/api/intake"))
                        || ("GET".equalsIgnoreCase(method) && path.startsWith("/api/status/"))
                        || path.equals("/health")
                        || path.startsWith("/actuator/health");

        if (publicPath) {
            if (path.equals("/api/intake") && !allow(clientIp(req))) {
                deny(res, 429, "{\"error\":\"rate limit exceeded\"}");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // Protected endpoints require a valid API key.
        if (apiKey.isEmpty()) {
            log.error("aegis.api-key not configured — refusing protected request to {}", path);
            deny(res, 503, "{\"error\":\"server not configured\"}");
            return;
        }
        String provided = req.getHeader("X-API-Key");
        if (provided == null || !constantTimeEquals(provided, apiKey)) {
            deny(res, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean allow(String ip) {
        long now = System.currentTimeMillis();
        long[] b = buckets.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (b) {
            if (now - b[0] > 60_000L) {
                b[0] = now;
                b[1] = 0;
            }
            b[1]++;
            return b[1] <= intakePerMinute;
        }
    }

    private static void deny(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setHeader("Access-Control-Allow-Origin", "*"); // error bodies carry no data
        res.setContentType("application/json");
        res.getWriter().write(body);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
