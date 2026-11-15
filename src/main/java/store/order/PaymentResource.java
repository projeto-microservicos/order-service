package store.order;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders/{id}/payment")
public class PaymentResource {
    private final PaymentService payments;
    private final AccountIdentity identity;
    public PaymentResource(PaymentService payments, AccountIdentity identity) { this.payments = payments; this.identity = identity; }

    @PostMapping
    public ResponseEntity<PaymentService.PaymentOut> create(@RequestHeader(value = "id-account", required = false) String account,
            @PathVariable String id) {
        return response(payments.payment(identity.resolve(account), id, true));
    }

    @GetMapping
    public ResponseEntity<PaymentService.PaymentOut> get(@RequestHeader(value = "id-account", required = false) String account,
            @PathVariable String id) {
        return response(payments.payment(identity.resolve(account), id, false));
    }

    private ResponseEntity<PaymentService.PaymentOut> response(PaymentService.PaymentOut payment) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payment);
    }
}
