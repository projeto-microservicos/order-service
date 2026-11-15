package store.order;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
    private final OrderStore store;
    private final OrderCache cache;
    private final StoreClients clients;
    public OrderService(OrderStore store, OrderCache cache, StoreClients clients) {
        this.store=store; this.cache=cache; this.clients=clients;
    }
    public OrderOut create(String account, OrderIn in) {
        // Resolve all products BEFORE opening the repository transaction. Never trust client totals.
        Map<String,BigDecimal> prices=new HashMap<>();
        List<OrderItem> items=in.items().stream().map(i -> new OrderItem(i.idProduct(),i.quantity(),
            prices.computeIfAbsent(i.idProduct(),id -> clients.price(id,account)))).toList();
        var order=view(store.create(new OrderModel(account,items)),"USD",BigDecimal.ONE,2);
        cache.put(account,order);
        return order;
    }
    public List<OrderOut.Summary> list(String account) {
        return store.list(account);
    }
    public OrderOut get(String account,String id,String requestedCurrency) {
        String currency=requestedCurrency.toUpperCase(Locale.ROOT);
        if(!currency.matches("[A-Z]{3}")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Invalid currency");
        int digits;
        try { digits=Currency.getInstance(currency).getDefaultFractionDigits(); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Invalid currency"); }
        if(digits<0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Invalid currency");
        OrderOut order=cache.get(account,id).orElseGet(() -> {
            var snapshot=store.get(account,id);
            cache.put(account,snapshot);
            return snapshot;
        });
        BigDecimal rate=currency.equals("USD")?BigDecimal.ONE:clients.rate(currency,account);
        return view(order,currency,rate,digits);
    }
    private OrderOut view(OrderOut o,String currency,BigDecimal rate,int digits) {
        var items=o.items().stream().map(i -> new OrderOut.Item(i.id(),i.product(),i.quantity(),
            i.total().multiply(rate).setScale(digits,RoundingMode.HALF_UP))).toList();
        // Sum rounded lines so the displayed total always reconciles with displayed items.
        var total=items.stream().map(OrderOut.Item::total).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(digits);
        return new OrderOut(o.id(),o.date(),currency,items,total);
    }
}
