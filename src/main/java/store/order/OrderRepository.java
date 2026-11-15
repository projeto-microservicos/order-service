package store.order;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
public interface OrderRepository extends JpaRepository<OrderModel,String> {
    interface Summary {
        String getId();
        java.time.OffsetDateTime getDate();
        java.math.BigDecimal getTotal();
    }
    List<Summary> findByAccountIdOrderByDateDesc(String accountId);
    Optional<OrderModel> findByIdAndAccountId(String id,String accountId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderModel o where o.id = :id and o.accountId = :account")
    Optional<OrderModel> lockOwned(String id, String account);
}
