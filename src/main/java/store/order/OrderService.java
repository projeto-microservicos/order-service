package store.order;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
    private final OrderRepository repository;
    private final StoreClients clients;
    public OrderService(OrderRepository repository, StoreClients clients) { this.repository=repository; this.clients=clients; }
    public OrderOut create(String account, OrderIn in) {
        // Resolve all products BEFORE opening the repository transaction. Never trust client totals.
        Map<String,BigDecimal> prices=new HashMap<>();
        List<OrderItem> items=in.items().stream().map(i -> new OrderItem(i.idProduct(),i.quantity(),
            prices.computeIfAbsent(i.idProduct(),id -> clients.price(id,account)))).toList();
        return view(repository.save(new OrderModel(account,items)),"USD",BigDecimal.ONE);
    }
    @Transactional(readOnly=true)
    public List<OrderOut.Summary> list(String account) {
        return repository.findByAccountIdOrderByDateDesc(account).stream()
            .map(o -> new OrderOut.Summary(o.id,o.date,o.total)).toList();
    }
    @Transactional(readOnly=true)
    public OrderOut get(String account,String id,String requestedCurrency) {
        OrderModel order=repository.findByIdAndAccountId(id,account)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
        String currency=requestedCurrency.toUpperCase(Locale.ROOT);
        if(!currency.matches("[A-Z]{3}")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid currency");
        BigDecimal rate=currency.equals("USD")?BigDecimal.ONE:clients.rate(currency,account);
        return view(order,currency,rate);
    }
    private OrderOut view(OrderModel o,String currency,BigDecimal rate) {
        int scale;
        try { scale=Currency.getInstance(currency).getDefaultFractionDigits(); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid currency"); }
        if(scale<0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid currency");
        final int digits=scale;
        var items=o.items.stream().map(i -> new OrderOut.Item(i.id,new OrderOut.Product(i.productId),i.quantity,
            i.total.multiply(rate).setScale(digits,RoundingMode.HALF_UP))).toList();
        // Sum rounded lines so the displayed total always reconciles with displayed items.
        var total=items.stream().map(OrderOut.Item::total).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(digits);
        return new OrderOut(o.id,o.date,currency,items,total);
    }
}
