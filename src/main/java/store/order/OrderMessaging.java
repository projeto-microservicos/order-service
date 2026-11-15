package store.order;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "store.messaging.enabled", havingValue = "true")
public class OrderMessaging {
    private static final Logger log = LoggerFactory.getLogger(OrderMessaging.class);
    static final String EXCHANGE = "store.orders";
    static final String QUEUE = "orders.created";
    private final OutboxRepository repository;
    private final OutboxStore store;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;

    public OrderMessaging(OutboxRepository repository, OutboxStore store, RabbitTemplate rabbit, MeterRegistry metrics) {
        this.repository = repository; this.store = store; this.rabbit = rabbit; this.metrics = metrics;
    }

    @Bean
    Declarables orderQueues() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("dead").build();
        var dead = QueueBuilder.durable("orders.created.dlq").build();
        return new Declarables(exchange, queue, dead,
                BindingBuilder.bind(queue).to(exchange).with("created"),
                BindingBuilder.bind(dead).to(exchange).with("dead"));
    }

    @Scheduled(fixedDelayString = "${store.messaging.poll-delay}", initialDelayString = "${store.messaging.poll-delay}")
    public void publishPending() {
        for (String id : repository.pending(Instant.now(), PageRequest.of(0, 20))) {
            store.claim(id).ifPresent(this::publish);
        }
    }

    private void publish(OutboxStore.Delivery delivery) {
        boolean success = false;
        try {
            var properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(delivery.id());
            var correlation = new CorrelationData(delivery.id());
            rabbit.send(EXCHANGE, "created", new Message(delivery.payload().getBytes(StandardCharsets.UTF_8), properties), correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            success = confirm.ack() && correlation.getReturned() == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Order event {} will be retried ({})", delivery.id(), e.getClass().getSimpleName());
        }
        // Publisher confirms happen outside the database transaction; leases coordinate replicas.
        store.complete(delivery, success);
        metrics.counter("orders.outbox.deliveries", "result", success ? "published" : "retry").increment();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void cleanup() { store.cleanup(); }
}
