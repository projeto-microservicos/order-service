package store.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {
    @Query("select e.id from OutboxEvent e where e.publishedAt is null and e.availableAt <= :now order by e.createdAt")
    List<String> pending(Instant now, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> lock(String id);

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :before")
    int deletePublished(Instant before);
}
