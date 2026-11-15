package store.order;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_outbox")
public class OutboxEvent {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, columnDefinition = "TEXT") String payload;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "available_at", nullable = false) Instant availableAt;
    @Column(name = "published_at") Instant publishedAt;
    @Column(name = "lease_token", length = 36) String leaseToken;
    @Column(nullable = false) int attempts;

    protected OutboxEvent() {}
    OutboxEvent(String id, String payload) {
        this.id = id; this.payload = payload;
        this.createdAt = Instant.now(); this.availableAt = createdAt;
    }
}
