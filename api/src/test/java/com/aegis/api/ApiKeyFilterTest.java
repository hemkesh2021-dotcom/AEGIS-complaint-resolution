package com.aegis.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aegis.api.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The security gate: who gets in, who gets 401, and who gets throttled. */
class ApiKeyFilterTest {

    private static final String KEY = "test-key";

    /** A RateLimiter with no Redis — exercises the in-memory fallback path. */
    private static RateLimiter newLimiter() {
        return new RateLimiter(new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                throw new UnsupportedOperationException("no redis in this test");
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return null;
            }
        });
    }

    private static ApiKeyFilter filter(String key, int intake, int status, boolean trustProxy) {
        return new ApiKeyFilter(key, intake, status, trustProxy, newLimiter());
    }

    private MockHttpServletResponse run(ApiKeyFilter filter, String method, String uri, String key)
            throws Exception {
        return run(filter, method, uri, key, null);
    }

    private MockHttpServletResponse run(ApiKeyFilter filter, String method, String uri, String key,
                                        String xff) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        if (key != null) {
            req.addHeader("X-API-Key", key);
        }
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        // if the chain was invoked, the request passed the gate
        res.setHeader("X-Passed", chain.getRequest() != null ? "yes" : "no");
        return res;
    }

    @Test
    void operatorEndpointsRequireTheKey() throws Exception {
        ApiKeyFilter f = filter(KEY, 100, 100, false);
        assertEquals(401, run(f, "GET", "/api/complaints", null).getStatus());
        assertEquals(401, run(f, "GET", "/api/complaints", "wrong-key").getStatus());
        MockHttpServletResponse ok = run(f, "GET", "/api/complaints", KEY);
        assertEquals(200, ok.getStatus());
        assertEquals("yes", ok.getHeader("X-Passed"));
    }

    @Test
    void publicEndpointsNeedNoKey() throws Exception {
        ApiKeyFilter f = filter(KEY, 100, 100, false);
        assertEquals("yes", run(f, "POST", "/api/intake", null).getHeader("X-Passed"));
        assertEquals("yes", run(f, "GET", "/api/status/TRK-ABC", null).getHeader("X-Passed"));
        assertEquals("yes", run(f, "GET", "/health", null).getHeader("X-Passed"));
        assertEquals("yes", run(f, "GET", "/actuator/health/readiness", null).getHeader("X-Passed"));
    }

    @Test
    void metricsAreProtected_postToStatusIsProtected() throws Exception {
        ApiKeyFilter f = filter(KEY, 100, 100, false);
        assertEquals(401, run(f, "GET", "/actuator/metrics", null).getStatus());
        assertEquals(401, run(f, "POST", "/api/status/TRK-ABC", null).getStatus());
    }

    @Test
    void intakeIsRateLimitedPerIp() throws Exception {
        ApiKeyFilter f = filter(KEY, 3, 100, false);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, run(f, "POST", "/api/intake", null).getStatus(), "call " + (i + 1));
        }
        assertEquals(429, run(f, "POST", "/api/intake", null).getStatus(), "4th call must throttle");
    }

    @Test
    void statusLookupsAreRateLimited_tokenGuessingIsThrottled() throws Exception {
        ApiKeyFilter f = filter(KEY, 100, 2, false);
        assertEquals(200, run(f, "GET", "/api/status/TRK-1", null).getStatus());
        assertEquals(200, run(f, "GET", "/api/status/TRK-2", null).getStatus());
        assertEquals(429, run(f, "GET", "/api/status/TRK-3", null).getStatus());
    }

    @Test
    void spoofedForwardedForCannotDodgeTheLimit() throws Exception {
        // trust-proxy OFF (default): X-Forwarded-For is attacker-controlled noise
        ApiKeyFilter f = filter(KEY, 1, 100, false);
        assertEquals(200, run(f, "POST", "/api/intake", null, "1.1.1.1").getStatus());
        assertEquals(429, run(f, "POST", "/api/intake", null, "2.2.2.2").getStatus(),
                "new spoofed IP must NOT reset the bucket");
    }

    @Test
    void trustedProxyForwardedForIsHonored() throws Exception {
        ApiKeyFilter f = filter(KEY, 1, 100, true);
        assertEquals(200, run(f, "POST", "/api/intake", null, "1.1.1.1").getStatus());
        assertEquals(200, run(f, "POST", "/api/intake", null, "2.2.2.2").getStatus(),
                "distinct real client IPs get their own buckets behind a trusted proxy");
        assertEquals(429, run(f, "POST", "/api/intake", null, "1.1.1.1").getStatus());
    }

    @Test
    void missingServerKeyFailsClosedNotOpen() throws Exception {
        ApiKeyFilter f = filter("", 100, 100, false);
        assertEquals(503, run(f, "GET", "/api/complaints", "anything").getStatus());
    }

    @Test
    void corsPreflightPasses() throws Exception {
        ApiKeyFilter f = filter(KEY, 100, 100, false);
        assertEquals("yes", run(f, "OPTIONS", "/api/complaints", null).getHeader("X-Passed"));
    }
}
