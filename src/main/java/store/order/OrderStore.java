package store.order;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Transaction boundaries deliberately exclude HTTP and Redis calls. */
@Service
public class OrderStore {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final boolean messaging;

    public OrderStore(OrderRepository orders, OutboxRepository outbox, ObjectMapper json,
            @Value("${store.messaging.enabled}") boolean messaging) {
        this.orders = orders; this.outbox = outbox; this.json = json; this.messaging = messaging;
    }

    @Transactional
    public OrderOut create(OrderModel order) {
        orders.save(order);
        if (messaging) {
            var event = new OrderCreated(java.util.UUID.randomUUID().toString(), "OrderCreated", order.id,
                    order.accountId, order.date, "USD", order.total);
            outbox.save(new OutboxEvent(event.eventId(), json.writeValueAsString(event)));
        }
        return snapshot(order);
    }

    @Transactional(readOnly = true)
    public OrderOut get(String account, String id) {
        return snapshot(orders.findByIdAndAccountId(id, account)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")));
    }

    @Transactional(readOnly = true)
    public List<OrderOut.Summary> list(String account) {
        return orders.findByAccountIdOrderByDateDesc(account).stream()
                .map(o -> new OrderOut.Summary(o.getId(), o.getDate(), o.getTotal())).toList();
    }

    private OrderOut snapshot(OrderModel order) {
        var items = order.items.stream().map(i -> new OrderOut.Item(i.id,
                new OrderOut.Product(i.productId), i.quantity, i.total)).toList();
        return new OrderOut(order.id, order.date, "USD", items, order.total);
    }

    public record OrderCreated(String eventId, String type, String orderId, String accountId,
            java.time.OffsetDateTime createdAt, String currency, java.math.BigDecimal total) {}
}
