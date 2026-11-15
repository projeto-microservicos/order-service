package store.order;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_payments")
public class OrderPayment {
    @Id @Column(name = "order_id", length = 36) String orderId;
    @Column(name = "attempt_id", nullable = false, length = 36) String attemptId;
    @Column(name = "started_at", nullable = false) Instant startedAt;
    @Column(name = "intent_id") String intentId;
    protected OrderPayment() {}
    OrderPayment(String orderId) {
        this.orderId = orderId; this.attemptId = UUID.randomUUID().toString(); this.startedAt = Instant.now();
    }
}
