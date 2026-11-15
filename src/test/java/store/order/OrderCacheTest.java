package store.order;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCacheTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    OrderCache cache = new OrderCache(redis, JsonMapper.builder().build(), metrics, true, Duration.ofMinutes(5));
    OrderOut order = new OrderOut("order-id", OffsetDateTime.now(ZoneOffset.UTC), "USD", List.of(), new BigDecimal("10.12"));

    @BeforeEach void setup() { when(redis.opsForValue()).thenReturn(values); }

    @Test void roundTripsSnapshotWithTtlAndSeparatesAccounts() {
        cache.put("alice", order);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("orders:v1:alice:order-id"), json.capture(), eq(Duration.ofMinutes(5)));
        when(values.get("orders:v1:alice:order-id")).thenReturn(json.getValue());
        assertThat(cache.get("alice", "order-id")).contains(order);
        assertThat(cache.get("bob", "order-id")).isEmpty();
        assertThat(metrics.counter("orders.cache.requests", "result", "hit").count()).isEqualTo(1);
    }

    @Test void connectionFailureAndCorruptDataFallBackToDatabase() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get("alice", "order-id")).isEmpty();
        doThrow(new RedisConnectionFailureException("offline")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertThatCode(() -> cache.put("alice", order)).doesNotThrowAnyException();
        doReturn("broken json").when(values).get(anyString());
        assertThat(cache.get("alice", "order-id")).isEmpty();
        assertThat(metrics.counter("orders.cache.errors").count()).isEqualTo(3);
    }

    @Test void disabledCacheDoesNotContactRedis() {
        var disabled = new OrderCache(redis, JsonMapper.builder().build(), metrics, false, Duration.ofMinutes(5));
        disabled.put("alice", order);
        assertThat(disabled.get("alice", "order-id")).isEmpty();
        verifyNoInteractions(values);
    }
}
