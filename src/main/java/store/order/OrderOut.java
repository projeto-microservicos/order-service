package store.order;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
public record OrderOut(String id, OffsetDateTime date, String currency, List<Item> items, BigDecimal total) {
    public record Product(String id) {}
    public record Item(String id, Product product, int quantity, BigDecimal total) {}
    public record Summary(String id, OffsetDateTime date, BigDecimal total) {}
}
