package store.order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Embeddable
public class OrderItem {
    @Column(name="item_id", nullable=false, length=36) String id;
    @Column(name="product_id", nullable=false, length=36) String productId;
    @Column(nullable=false) int quantity;
    @Column(name="unit_price", nullable=false, precision=19, scale=2) BigDecimal unitPrice;
    @Column(nullable=false, precision=19, scale=2) BigDecimal total;
    protected OrderItem() {}
    OrderItem(String productId, int quantity, BigDecimal unitPrice) {
        this.id=UUID.randomUUID().toString(); this.productId=productId;
        this.quantity=quantity; this.unitPrice=unitPrice;
        this.total=unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
