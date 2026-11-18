package store.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BOT-03: the OrderCreated consumer, called directly (no broker): idempotency, poison messages, retries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:worker;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "store.messaging.enabled=true", "store.messaging.worker-enabled=true", "store.messaging.poll-delay=3600000",
    "spring.rabbitmq.dynamic=false", "spring.rabbitmq.listener.simple.auto-startup=false", "logging.level.root=WARN"})
class OrderConfirmationWorkerTest {
    @Autowired OrderConfirmationWorker worker;
    @Autowired OrderStore orders;
    @Autowired OutboxRepository events;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry metrics;
    @MockitoBean RabbitTemplate rabbit;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM orders.order_notifications");
        events.deleteAll();
        orderRepository.deleteAll();
    }

    static Message message(String body) {
        var properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    double consumed(String result) {
        var counter = metrics.find("orders.events.consumed").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The exact payload the outbox relay publishes for a new order. */
    String outboxPayload() {
        var order = orders.create(new OrderModel(UUID.randomUUID().toString(),
                List.of(new OrderItem(UUID.randomUUID().toString(), 2, new BigDecimal("10.12")))));
        assertThat(order.total()).isEqualByComparingTo("20.24");
        return events.findAll().getFirst().payload;
    }

    @Test void anOrderEventIsConfirmedOnceEvenWhenRedelivered() {
        String payload = outboxPayload();
        double processed = consumed("processed"), duplicate = consumed("duplicate");

        worker.onOrderCreated(message(payload));
        worker.onOrderCreated(message(payload)); // at-least-once: the broker may deliver it again

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM orders.order_notifications");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("currency", "USD");
        assertThat((BigDecimal) rows.getFirst().get("total")).isEqualByComparingTo("20.24");
        assertThat(rows.getFirst().get("order_id")).isEqualTo(orderRepository.findAll().getFirst().id);
        assertThat(consumed("processed") - processed).isEqualTo(1);
        assertThat(consumed("duplicate") - duplicate).isEqualTo(1);
    }

    @Test void poisonMessagesGoStraightToTheDeadLetterQueue() {
        for (String body : List.of("not json", "{}", "{\"type\":\"OrderCreated\",\"eventId\":\"e-1\"}",
                "{\"type\":\"Other\",\"eventId\":\"e\",\"orderId\":\"o\",\"accountId\":\"a\",\"total\":1,\"createdAt\":\"2026-11-17T12:00:00Z\"}")) {
            assertThatThrownBy(() -> worker.onOrderCreated(message(body)))
                    .as(body).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders.order_notifications", Integer.class)).isZero();
    }

    @Test void aDatabaseOutageIsRetriedThenDeadLetteredInsteadOfLoopingForever() throws Exception {
        JdbcTemplate down = mock(JdbcTemplate.class);
        when(down.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("db down"));
        var isolated = new OrderConfirmationWorker(json, down, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                new SimpleMeterRegistry(), Duration.ZERO, Duration.ZERO);

        assertThatThrownBy(() -> isolated.onOrderCreated(message(outboxPayload())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class).hasMessageContaining("DLQ");
        verify(down, times(OrderConfirmationWorker.ATTEMPTS)).update(anyString(), any(Object[].class));
    }
}
