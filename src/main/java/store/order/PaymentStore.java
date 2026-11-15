package store.order;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentStore {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    public PaymentStore(OrderRepository orders, PaymentRepository payments) { this.orders = orders; this.payments = payments; }
    public record Attempt(String orderId, String attemptId, Instant startedAt, String intentId, BigDecimal total) {}

    @Transactional
    public Attempt reserve(String account, String id, boolean create) {
        // Serialize reservations across replicas without holding a lock during a provider call.
        var order = orders.lockOwned(id, account)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        var payment = payments.findById(id).orElseGet(() -> {
            if (!create) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not started");
            return payments.save(new OrderPayment(id));
        });
        return new Attempt(id, payment.attemptId, payment.startedAt, payment.intentId, order.total);
    }

    @Transactional
    public void attach(String account, Attempt attempt, String intentId) {
        orders.lockOwned(attempt.orderId(), account).orElseThrow();
        var payment = payments.findById(attempt.orderId()).orElseThrow();
        if (!payment.attemptId.equals(attempt.attemptId()) || (payment.intentId != null && !payment.intentId.equals(intentId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment requires reconciliation");
        }
        payment.intentId = intentId;
    }
}
