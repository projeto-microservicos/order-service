package store.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;

/**
 * BOT-03/04 end to end against a real RabbitMQ (opt-in, like OrderInfrastructureTest): order + outbox, relay,
 * listener, idempotency and the DLQ. The context is closed afterwards so its listener cannot consume messages
 * meant for other tests.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INFRA_SMOKE", matches = "true")
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:eda;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "store.messaging.enabled=true", "store.messaging.worker-enabled=true", "store.messaging.poll-delay=200",
    "logging.level.root=WARN"})
class OrderEdaInfrastructureTest {
    @Autowired OrderStore orders;
    @Autowired OutboxRepository events;
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;

    int confirmations(String orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM orders.order_notifications WHERE order_id = ?", Integer.class, orderId);
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("timed out waiting").isBefore(deadline);
            Thread.sleep(100);
        }
    }

    @Test void anOrderIsConfirmedAsynchronouslyOnceAndPoisonGoesToTheDlq() throws Exception {
        var order = orders.create(new OrderModel(UUID.randomUUID().toString(),
                List.of(new OrderItem(UUID.randomUUID().toString(), 2, new BigDecimal("10.12")))));

        // The relay publishes the outbox row and the listener records the confirmation, with no call from the test.
        await(() -> confirmations(order.id()) == 1);
        assertThat(events.findAll()).allSatisfy(event -> assertThat(event.publishedAt).isNotNull());

        // Redelivery of the same event (same eventId) changes nothing.
        var payload = events.findAll().getFirst().payload;
        rabbit.convertAndSend(OrderMessaging.EXCHANGE, "created", json(payload));
        Thread.sleep(1500);
        assertThat(confirmations(order.id())).isEqualTo(1);

        // A poison message is rejected without requeue and dead-lettered.
        rabbit.send(OrderMessaging.EXCHANGE, "created", json("not json"));
        Message dead = rabbit.receive("orders.created.dlq", 10_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("not json");
    }

    static Message json(String body) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
