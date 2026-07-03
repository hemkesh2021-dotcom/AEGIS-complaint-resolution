package com.aegis.api.service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Fixed-window rate limiter, distributed when Redis is available.
 *
 * <p>An in-memory limiter breaks the moment there is more than one API
 * instance (each replica keeps its own counters), so production uses Redis
 * INCR + EXPIRE per (key, minute-window). If Redis is missing or down we fall
 * back to the local in-memory window — degraded but never open.
 */
@Service
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ConcurrentHashMap<String, long[]> local = new ConcurrentHashMap<>();
    private volatile boolean redisWarned = false;

    public RateLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** @return true if this call is within {@code perMinute} for {@code key}. */
    public boolean allow(String key, int perMinute) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                long window = System.currentTimeMillis() / 60_000L;
                String k = "aegis:rl:" + key + ":" + window;
                Long n = redis.opsForValue().increment(k);
                if (n != null) {
                    if (n == 1L) {
                        redis.expire(k, Duration.ofSeconds(65));
                    }
                    return n <= perMinute;
                }
            } catch (Exception e) {
                if (!redisWarned) {
                    redisWarned = true;
                    log.warn("Redis unavailable ({}); rate limiting falls back to in-memory.",
                            e.getMessage());
                }
            }
        }
        return allowLocal(key, perMinute);
    }

    private boolean allowLocal(String key, int perMinute) {
        long now = System.currentTimeMillis();
        long[] b = local.computeIfAbsent(key, k -> new long[]{now, 0});
        synchronized (b) {
            if (now - b[0] > 60_000L) {
                b[0] = now;
                b[1] = 0;
            }
            b[1]++;
            return b[1] <= perMinute;
        }
    }
}
