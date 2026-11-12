package store.order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Entity @Table(name="orders")
public class OrderModel {
    @Id @Column(length=36) String id;
    @Column(name="account_id", nullable=false, length=36) String accountId;
    @Column(name="created_at", nullable=false) OffsetDateTime date;
    @Column(nullable=false, precision=19, scale=2) BigDecimal total;
    @ElementCollection
    @CollectionTable(name="order_items", joinColumns=@JoinColumn(name="order_id"))
    @OrderColumn(name="item_position")
    List<OrderItem> items=new ArrayList<>();
    protected OrderModel() {}
    OrderModel(String accountId, List<OrderItem> items) {
        this.id=UUID.randomUUID().toString(); this.accountId=accountId;
        this.date=OffsetDateTime.now(ZoneOffset.UTC); this.items=items;
        this.total=items.stream().map(i -> i.total).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
}
