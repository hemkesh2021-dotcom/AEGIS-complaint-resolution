package com.aegis.api;

import com.aegis.api.service.OidcAuth;
import com.aegis.api.service.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
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

    /** Request attributes set for downstream authorization (maker-checker, audit). */
    public static final String ATTR_ACTOR = "aegis.actor";
    public static final String ATTR_ROLES = "aegis.roles";
    public static final String ROLE_OPERATOR = "operator";
    public static final String ROLE_SUPERVISOR = "supervisor";

    private final String apiKey;
    private final int intakePerMinute;
    private final int statusPerMinute;
    private final boolean trustProxy;
    private final RateLimiter rateLimiter;
    private final OidcAuth oidc;

    public ApiKeyFilter(@Value("${aegis.api-key:}") String apiKey,
                        @Value("${aegis.intake-rate-per-minute:20}") int intakePerMinute,
                        @Value("${aegis.status-rate-per-minute:60}") int statusPerMinute,
                        @Value("${aegis.trust-proxy:false}") boolean trustProxy,
                        RateLimiter rateLimiter, OidcAuth oidc) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.intakePerMinute = intakePerMinute;
        this.statusPerMinute = statusPerMinute;
        this.trustProxy = trustProxy;
        this.rateLimiter = rateLimiter;
        this.oidc = oidc;
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
            // Both public endpoints are rate-limited: intake (LLM-cost abuse) and
            // status (tracking-token guessing / enumeration).
            if (path.equals("/api/intake")
                    && !rateLimiter.allow("in:" + clientIp(req), intakePerMinute)) {
                deny(res, 429, "{\"error\":\"rate limit exceeded\"}");
                return;
            }
            if (path.startsWith("/api/status/")
                    && !rateLimiter.allow("st:" + clientIp(req), statusPerMinute)) {
                deny(res, 429, "{\"error\":\"rate limit exceeded\"}");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // Protected endpoints: OIDC bearer token (real identity + roles) OR the
        // API key (break-glass/dev — full access, actor recorded as "api-key").
        String authz = req.getHeader("Authorization");
        if (authz != null && authz.regionMatches(true, 0, "Bearer ", 0, 7)) {
            OidcAuth.Principal p = oidc.authenticate(authz.substring(7).trim());
            if (p == null) {
                deny(res, 401, "{\"error\":\"invalid or expired token\"}");
                return;
            }
            if (!p.roles().contains(ROLE_OPERATOR)) {
                deny(res, 403, "{\"error\":\"operator role required\"}");
                return;
            }
            req.setAttribute(ATTR_ACTOR, p.username());
            req.setAttribute(ATTR_ROLES, p.roles());
            chain.doFilter(req, res);
            return;
        }

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
        req.setAttribute(ATTR_ACTOR, "api-key");
        req.setAttribute(ATTR_ROLES, Set.of(ROLE_OPERATOR, ROLE_SUPERVISOR));
        chain.doFilter(req, res);
    }

    private static void deny(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setHeader("Access-Control-Allow-Origin", "*"); // error bodies carry no data
        res.setContentType("application/json");
        res.getWriter().write(body);
    }

    /**
     * X-Forwarded-For is attacker-controlled unless a proxy we operate sets it —
     * honoring it blindly lets clients spoof fresh IPs and dodge the rate limit.
     * Only trusted when {@code aegis.trust-proxy=true} (i.e., behind our LB).
     */
    private String clientIp(HttpServletRequest req) {
        if (trustProxy) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return req.getRemoteAddr();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
