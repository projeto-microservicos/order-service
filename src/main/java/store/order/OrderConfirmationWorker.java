package store.order;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * BOT-03: consumer of {@code OrderCreated} (queue {@code orders.created}), the asynchronous half of the EDA flow.
 *
 * <p>For every new order it records the order confirmation and "sends" it to the customer (a log line stands for
 * the e-mail; the project has no mail server). {@code POST /orders} never waits for this: the order and its event
 * are committed together (outbox), and the relay publishes later.
 *
 * <ul>
 *   <li><b>Idempotent:</b> delivery is at-least-once, so the eventId is the primary key of
 *       {@code orders.order_notifications}; a redelivered event changes nothing and is acknowledged.</li>
 *   <li><b>Ack after commit:</b> the container acknowledges only when this method returns, after the transaction.</li>
 *   <li><b>Poison message</b> (not JSON, missing fields): rejected without requeue, so it goes to the DLQ
 *       {@code orders.created.dlq} at once.</li>
 *   <li><b>Transient failure</b> (database down): {@value #ATTEMPTS} attempts with backoff, then the DLQ, from where
 *       it can be moved back once the database is fine. Never an endless requeue loop.</li>
 * </ul>
 * Enabled by {@code ORDER_WORKER_ENABLED=true}, together with {@code MESSAGING_ENABLED=true}.
 */
@Component
@ConditionalOnExpression("${store.messaging.enabled:false} and ${store.messaging.worker-enabled:false}")
public class OrderConfirmationWorker {
    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationWorker.class);
    static final int ATTEMPTS = 3;
    static final String INSERT = """
            INSERT INTO orders.order_notifications
                (event_id, order_id, account_id, currency, total, ordered_at, processed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING"""; // the primary key (event_id) is the only constraint

    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final Duration delay;
    private final Duration backoff;

    public OrderConfirmationWorker(ObjectMapper json, JdbcTemplate jdbc, TransactionTemplate transaction,
            MeterRegistry metrics, @Value("${store.messaging.worker-delay:0s}") Duration delay,
            @Value("${store.messaging.worker-backoff:1s}") Duration backoff) {
        this.json = json; this.jdbc = jdbc; this.transaction = transaction; this.metrics = metrics;
        this.delay = delay; this.backoff = backoff;
    }

    @RabbitListener(id = "order-confirmations", queues = OrderMessaging.QUEUE)
    public void onOrderCreated(Message message) {
        OrderStore.OrderCreated event = parse(message);
        // Demo knob (ORDER_WORKER_DELAY): a slow consumer must not slow POST /orders down.
        sleep(delay);
        for (int attempt = 1; ; attempt++) {
            try {
                Boolean created = transaction.execute(status -> jdbc.update(INSERT, event.eventId(), event.orderId(),
                        event.accountId(), event.currency(), event.total(), Timestamp.from(event.createdAt().toInstant()),
                        Timestamp.from(Instant.now())) == 1);
                if (Boolean.TRUE.equals(created)) {
                    log.info("Order {} confirmed: confirmation sent to account {} (total {} {})",
                            event.orderId(), event.accountId(), event.total(), event.currency());
                    count("processed");
                } else {
                    log.info("Order event {} already processed; ignoring the redelivery", event.eventId());
                    count("duplicate");
                }
                return;
            } catch (TransientDataAccessException | DataAccessResourceFailureException e) {
                if (attempt >= ATTEMPTS) {
                    count("dead-lettered");
                    throw new AmqpRejectAndDontRequeueException("order event " + event.eventId()
                            + " failed " + ATTEMPTS + " times; sent to the DLQ", e);
                }
                log.warn("Order event {} failed (attempt {} of {}): {}", event.eventId(), attempt, ATTEMPTS,
                        e.getClass().getSimpleName());
                sleep(backoff.multipliedBy(attempt));
            }
        }
    }

    private OrderStore.OrderCreated parse(Message message) {
        try {
            var event = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), OrderStore.OrderCreated.class);
            if (event.eventId() == null || event.orderId() == null || event.accountId() == null
                    || event.total() == null || event.createdAt() == null || !"OrderCreated".equals(event.type())) {
                throw new IllegalArgumentException("incomplete OrderCreated");
            }
            return event.currency() == null ? new OrderStore.OrderCreated(event.eventId(), event.type(), event.orderId(),
                    event.accountId(), event.createdAt(), "USD", event.total()) : event;
        } catch (JacksonException | IllegalArgumentException e) {
            count("rejected");
            log.warn("Rejecting an invalid order event (message id {}) to the DLQ",
                    message.getMessageProperties().getMessageId());
            throw new AmqpRejectAndDontRequeueException("invalid OrderCreated event", e);
        }
    }

    private void count(String result) {
        metrics.counter("orders.events.consumed", "result", result).increment();
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) return;
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            // Pod shutting down: the event was not processed, so it goes back to the queue (not to the DLQ).
            Thread.currentThread().interrupt();
            throw new ImmediateRequeueAmqpException("interrupted before processing", e);
        }
    }
}
