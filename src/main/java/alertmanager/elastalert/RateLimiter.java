package alertmanager.elastalert;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单的令牌桶限流器实现
 */
public class RateLimiter {
    private final double permitsPerSecond;
    private final AtomicInteger tokens;
    private volatile Instant lastRefillTime;

    public RateLimiter(double permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        this.tokens = new AtomicInteger(0);
        this.lastRefillTime = Instant.now();
    }

    public boolean tryAcquire() {
        refillTokens();
        return tokens.decrementAndGet() >= 0;
    }

    private void refillTokens() {
        Instant now = Instant.now();
        long secondsSinceLastRefill = now.getEpochSecond() - lastRefillTime.getEpochSecond();

        if (secondsSinceLastRefill > 0) {
            int newTokens = (int) (secondsSinceLastRefill * permitsPerSecond);
            tokens.addAndGet(newTokens);
            lastRefillTime = now;
        }
    }

    public static RateLimiter create(double permitsPerSecond) {
        return new RateLimiter(permitsPerSecond);
    }
}