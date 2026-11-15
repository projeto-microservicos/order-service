package store.order;

import java.time.Duration;
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Only immutable USD snapshots are cached. Ownership is part of every key. */
@Component
public class OrderCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final Duration ttl;

    public OrderCache(StringRedisTemplate redis, ObjectMapper json, MeterRegistry metrics,
            @Value("${store.cache.enabled}") boolean enabled, @Value("${store.cache.ttl}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Cache TTL must be positive");
        this.redis = redis; this.json = json; this.metrics = metrics; this.enabled = enabled; this.ttl = ttl;
    }

    public Optional<OrderOut> get(String account, String id) {
        if (!enabled) return Optional.empty();
        try {
            String value = redis.opsForValue().get(key(account, id));
            metrics.counter("orders.cache.requests", "result", value == null ? "miss" : "hit").increment();
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, OrderOut.class));
        } catch (RuntimeException e) {
            metrics.counter("orders.cache.errors").increment();
            return Optional.empty();
        }
    }

    public void put(String account, OrderOut order) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(account, order.id()), json.writeValueAsString(order), ttl);
        } catch (RuntimeException e) {
            // A cache outage must not turn a committed order into a failed HTTP response.
            metrics.counter("orders.cache.errors").increment();
        }
    }

    private String key(String account, String id) { return "orders:v1:" + account + ":" + id; }
}
