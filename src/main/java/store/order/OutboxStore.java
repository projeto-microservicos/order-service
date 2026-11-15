package store.order;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxStore {
    private final OutboxRepository repository;
    public OutboxStore(OutboxRepository repository) { this.repository = repository; }
    public record Delivery(String id, String payload, String token) {}

    @Transactional
    public Optional<Delivery> claim(String id) {
        var event = repository.lock(id).orElse(null);
        var now = Instant.now();
        if (event == null || event.publishedAt != null || event.availableAt.isAfter(now)) return Optional.empty();
        event.leaseToken = UUID.randomUUID().toString();
        event.availableAt = now.plusSeconds(30);
        event.attempts++;
        return Optional.of(new Delivery(event.id, event.payload, event.leaseToken));
    }

    @Transactional
    public void complete(Delivery delivery, boolean success) {
        repository.lock(delivery.id()).ifPresent(event -> {
            if (!delivery.token().equals(event.leaseToken)) return;
            if (success) event.publishedAt = Instant.now();
            else event.availableAt = Instant.now().plusSeconds(Math.min(300, 1L << Math.min(event.attempts, 8)));
            event.leaseToken = null;
        });
    }

    @Transactional
    public void cleanup() { repository.deletePublished(Instant.now().minus(7, ChronoUnit.DAYS)); }
}
