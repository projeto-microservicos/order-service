package store.order;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {
    private final PaymentStore store;
    private final RestClient stripe;
    private final boolean enabled;

    public PaymentService(PaymentStore store, @Value("${store.payments.enabled}") boolean enabled,
            @Value("${store.payments.secret-key}") String key, @Value("${store.payments.base-url}") String baseUrl,
            @Value("${store.payments.api-version}") String version) {
        if (enabled && key.isBlank()) throw new IllegalArgumentException("Stripe secret key is required when payments are enabled");
        this.store = store; this.enabled = enabled;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.stripe = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key).defaultHeader("Stripe-Version", version).build();
    }

    public record PaymentOut(String id, String clientSecret, String status, long amount, String currency) {
        @Override public String toString() { return "PaymentOut[id=" + id + ", status=" + status + "]"; }
    }
    public record StripeIntent(String id, String client_secret, String status, long amount, String currency) {
        @Override public String toString() { return "StripeIntent[id=" + id + ", status=" + status + "]"; }
    }

    public PaymentOut payment(String account, String id, boolean create) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payments are disabled");
        var attempt = store.reserve(account, id, create);
        long cents;
        try { cents = attempt.total().movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw invalidAmount(); }
        if (cents < 50 || cents > 99_999_999) throw invalidAmount();
        if (attempt.intentId() == null && attempt.startedAt().isBefore(Instant.now().minus(Duration.ofHours(23)))) {
            // Stripe may prune idempotency keys after 24h. Never risk creating a second charge.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment requires provider reconciliation before retrying");
        }
        if (!create && attempt.intentId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment creation pending; retry the POST");
        }
        try {
            StripeIntent intent;
            if (attempt.intentId() != null) {
                intent = stripe.get().uri("/v1/payment_intents/{id}", attempt.intentId()).retrieve().body(StripeIntent.class);
            } else {
                var form = new LinkedMultiValueMap<String, String>();
                form.add("amount", Long.toString(cents)); form.add("currency", "usd");
                form.add("payment_method_types[]", "card"); form.add("metadata[order_id]", id);
                intent = stripe.post().uri("/v1/payment_intents").header("Idempotency-Key", "order-" + attempt.attemptId())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(StripeIntent.class);
            }
            if (intent == null || intent.id() == null || !intent.id().matches("pi_[A-Za-z0-9]+")
                    || intent.client_secret() == null || intent.status() == null || intent.amount() != cents
                    || !"usd".equals(intent.currency()) || (attempt.intentId() != null && !attempt.intentId().equals(intent.id()))) {
                throw unavailable();
            }
            if (attempt.intentId() == null) store.attach(account, attempt, intent.id());
            return new PaymentOut(intent.id(), intent.client_secret(), intent.status(), cents, "usd");
        } catch (RestClientException e) { throw unavailable(); }
    }

    private ResponseStatusException invalidAmount() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "USD payment must be between 0.50 and 999999.99");
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider unavailable; retry safely");
    }
}
