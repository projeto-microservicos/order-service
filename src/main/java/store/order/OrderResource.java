package store.order;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/orders")
public class OrderResource {
    private final OrderService service;
    private final AccountIdentity identity;
    public OrderResource(OrderService service, AccountIdentity identity) { this.service=service; this.identity=identity; }
    @PostMapping
    public ResponseEntity<OrderOut> create(@RequestHeader(value="id-account",required=false) String account,@Valid @RequestBody OrderIn in) {
        var out=service.create(identity.resolve(account),in);
        return ResponseEntity.created(URI.create("/orders/"+out.id())).body(out);
    }
    @GetMapping
    public List<OrderOut.Summary> list(@RequestHeader(value="id-account",required=false) String account) { return service.list(identity.resolve(account)); }
    @GetMapping("/{id}")
    public OrderOut get(@RequestHeader(value="id-account",required=false) String account,@PathVariable String id,@RequestParam(defaultValue="USD") String currency) {
        return service.get(identity.resolve(account),id,currency);
    }
    /** Probe target (compose healthcheck, Kubernetes): 200 while the process serves HTTP; no token, no identity, nothing internal. */
    @GetMapping("/health-check")
    public ResponseEntity<Void> healthCheck() { return ResponseEntity.ok().build(); }
}
