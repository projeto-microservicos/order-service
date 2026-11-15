package store.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:outbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "store.messaging.enabled=true",
    "store.messaging.poll-delay=3600000", "spring.rabbitmq.dynamic=false", "logging.level.root=WARN"})
class OrderOutboxTest {
    @Autowired OrderStore orders;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository events;
    @Autowired OutboxStore leases;
    @Autowired OrderMessaging relay;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean RabbitTemplate rabbit;

    @BeforeEach void clean() { events.deleteAll(); orderRepository.deleteAll(); }

    OrderModel order() {
        return new OrderModel(java.util.UUID.randomUUID().toString(),
                List.of(new OrderItem(java.util.UUID.randomUUID().toString(), 2, new BigDecimal("10.12"))));
    }
    OutboxEvent event() { return events.findAll().getFirst(); }

    void acknowledge(boolean routed) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (!routed) correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", OrderMessaging.EXCHANGE, "created"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test void orderAndEventCommitAndRollbackTogether() {
        var order = order();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orders.create(order);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(orderRepository.findById(order.id)).isEmpty();
        assertThat(events.count()).isZero();
        orders.create(order());
        assertThat(events.count()).isEqualTo(1);
        assertThat(event().payload).contains("OrderCreated", "20.24");
    }

    @Test void brokerFailureRetriesSameEventAndConfirmedDeliveryIsNotRepeated() {
        orders.create(order());
        String id = event().id;
        doThrow(new AmqpConnectException(new java.net.ConnectException("offline")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        relay.publishPending();
        var failed = event();
        assertThat(failed.publishedAt).isNull();
        assertThat(failed.attempts).isEqualTo(1);
        assertThat(failed.availableAt).isAfter(Instant.now());
        failed.availableAt = Instant.now().minusSeconds(1); events.saveAndFlush(failed);
        acknowledge(true);
        relay.publishPending(); relay.publishPending();
        assertThat(event().id).isEqualTo(id);
        assertThat(event().publishedAt).isNotNull();
        verify(rabbit, times(2)).send(eq(OrderMessaging.EXCHANGE), eq("created"), any(Message.class), any(CorrelationData.class));
    }

    @Test void unroutableMessageIsNotMarkedPublished() {
        orders.create(order()); acknowledge(false);
        relay.publishPending();
        assertThat(event().publishedAt).isNull();
    }

    @Test void replicasRespectLeasesAndStaleOwnersCannotAcknowledge() {
        orders.create(order());
        var first = leases.claim(event().id).orElseThrow();
        assertThat(leases.claim(first.id())).isEmpty();
        var expired = event(); expired.availableAt = Instant.now().minusSeconds(1); events.saveAndFlush(expired);
        var replacement = leases.claim(first.id()).orElseThrow();
        leases.complete(first, true);
        assertThat(event().publishedAt).isNull();
        leases.complete(replacement, true);
        assertThat(event().publishedAt).isNotNull();
    }
}
