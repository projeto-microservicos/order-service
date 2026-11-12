package store.order;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/orders")
public class OrderResource {
    private final OrderService service;
    public OrderResource(OrderService service) { this.service=service; }
    @PostMapping
    public ResponseEntity<OrderOut> create(@RequestHeader("id-account") String account,@Valid @RequestBody OrderIn in) {
        var out=service.create(account,in);
        return ResponseEntity.created(URI.create("/orders/"+out.id())).body(out);
    }
    @GetMapping
    public List<OrderOut.Summary> list(@RequestHeader("id-account") String account) { return service.list(account); }
    @GetMapping("/{id}")
    public OrderOut get(@RequestHeader("id-account") String account,@PathVariable String id,@RequestParam(defaultValue="USD") String currency) {
        return service.get(account,id,currency);
    }
}
