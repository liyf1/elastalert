package alertmanager.elastalert;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LocalAlertCache implements AlertCache {
    private final Cache<String, AlertEntry> cache;

    public LocalAlertCache() {
        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(10000)
                .build();
    }

    @Override
    public Optional<AlertEntry> get(String alertId) {
        return Optional.ofNullable(cache.getIfPresent(alertId));
    }

    @Override
    public void put(String alertId, AlertEntry entry, Duration ttl) {
        cache.put(alertId, entry);
    }

    @Override
    public boolean exists(String alertId) {
        return cache.getIfPresent(alertId) != null;
    }

    @Override
    public void delete(String alertId) {
        cache.invalidate(alertId);
    }

    @Override
    public void cleanup() {
        cache.cleanUp();
    }

    @Override
    public void shutdown() {
        cache.invalidateAll();
    }
}