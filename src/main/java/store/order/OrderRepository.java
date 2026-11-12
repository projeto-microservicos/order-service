package store.order;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrderRepository extends JpaRepository<OrderModel,String> {
    List<OrderModel> findByAccountIdOrderByDateDesc(String accountId);
    Optional<OrderModel> findByIdAndAccountId(String id,String accountId);
}
