package store.order;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "store.payments.enabled=true", "store.payments.secret-key=sk_test_stub",
    "spring.datasource.url=jdbc:h2:mem:payments;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "logging.level.root=WARN"})
class OrderPaymentTest {
    static HttpServer provider;
    static List<String> keys = Collections.synchronizedList(new ArrayList<>());
    static List<String> forms = Collections.synchronizedList(new ArrayList<>());
    static AtomicInteger gets = new AtomicInteger();
    static AtomicBoolean unavailable = new AtomicBoolean();
    static AtomicReference<String> status = new AtomicReference<>("requires_payment_method");
    @Autowired OrderStore orders;
    @Autowired PaymentService service;
    @Autowired PaymentRepository payments;
    String account = UUID.randomUUID().toString();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/payment_intents", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                forms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } else gets.incrementAndGet();
            int code = unavailable.get() ? 503 : 200;
            String body = code == 200 ? "{\"id\":\"pi_Test\",\"client_secret\":\"pi_Test_secret_test\",\"status\":\""
                    + status.get() + "\",\"amount\":2024,\"currency\":\"usd\"}" : "{}";
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        provider.start();
        registry.add("store.payments.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }
    @AfterAll static void stop() { provider.stop(0); }
    @BeforeEach void reset() {
        payments.deleteAll(); keys.clear(); forms.clear(); gets.set(0); unavailable.set(false); status.set("requires_payment_method");
    }
    String order() { return orders.create(new OrderModel(account,
            List.of(new OrderItem(UUID.randomUUID().toString(), 2, new BigDecimal("10.12"))))).id(); }
    void assertStatus(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test void serverCalculatesAmountAndReusesSavedIntentAfterIdempotencyExpiry() {
        String order = order();
        assertThat(service.payment(account, order, true).status()).isEqualTo("requires_payment_method");
        var saved = payments.findById(order).orElseThrow();
        saved.startedAt = Instant.now().minusSeconds(172800); payments.saveAndFlush(saved);
        service.payment(account, order, true);
        status.set("succeeded");
        assertThat(service.payment(account, order, false).status()).isEqualTo("succeeded");
        assertThat(keys).hasSize(1);
        assertThat(forms.getFirst()).contains("amount=2024", "currency=usd");
        assertThat(gets.get()).isEqualTo(2);
        assertThat(payments.findById(order).orElseThrow().intentId).isEqualTo("pi_Test");
    }

    @Test void providerFailurePreservesKeyForRetry() {
        String order = order(); unavailable.set(true);
        assertStatus(() -> service.payment(account, order, true), 503);
        assertThat(payments.findById(order).orElseThrow().intentId).isNull();
        unavailable.set(false);
        service.payment(account, order, true);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    @Test void ownershipMissingPaymentsAndExpiredUncertainAttemptsAreProtected() {
        String order = order();
        assertStatus(() -> service.payment(UUID.randomUUID().toString(), order, true), 404);
        assertStatus(() -> service.payment(account, order, false), 404);
        unavailable.set(true);
        assertStatus(() -> service.payment(account, order, true), 503);
        var saved = payments.findById(order).orElseThrow();
        saved.startedAt = Instant.now().minusSeconds(86400); payments.saveAndFlush(saved);
        assertStatus(() -> service.payment(account, order, true), 409);
        assertThat(keys).hasSize(1);
    }

    @Test void zeroValueOrdersNeverReachProvider() {
        String free = orders.create(new OrderModel(account,
                List.of(new OrderItem(UUID.randomUUID().toString(), 1, BigDecimal.ZERO)))).id();
        assertStatus(() -> service.payment(account, free, true), 422);
        assertThat(keys).isEmpty();
    }
}
