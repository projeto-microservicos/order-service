package store.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

/** Opt-in check against actual Redis/RabbitMQ instances, separate from the offline build. */
@EnabledIfEnvironmentVariable(named = "RUN_INFRA_SMOKE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:infra;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "store.cache.enabled=true",
    "store.messaging.enabled=true", "store.messaging.poll-delay=3600000", "logging.level.root=WARN"})
class OrderInfrastructureTest {
    @Autowired OrderStore orders;
    @Autowired OrderCache cache;
    @Autowired OrderMessaging relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired OutboxRepository events;

    @Test void realCacheAndBrokerRoundTrip() {
        String account = UUID.randomUUID().toString();
        var order = orders.create(new OrderModel(account,
                List.of(new OrderItem(UUID.randomUUID().toString(), 2, new BigDecimal("10.12")))));
        cache.put(account, order);
        assertThat(cache.get(account, order.id())).contains(order);
        assertThat(cache.get(UUID.randomUUID().toString(), order.id())).isEmpty();
        relay.publishPending();
        assertThat(events.findAll()).allSatisfy(event -> assertThat(event.publishedAt).isNotNull());
        var message = rabbit.receive(OrderMessaging.QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains(order.id(), "OrderCreated", "20.24");
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(events.findAll().getFirst().id);
    }
}
