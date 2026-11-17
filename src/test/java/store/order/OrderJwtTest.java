package store.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "management.server.port=0", "store.security.jwt-enabled=true",
    "spring.datasource.url=jdbc:h2:mem:jwt;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "logging.level.root=WARN"})
class OrderJwtTest {
    @LocalServerPort int port;
    @Autowired OrderStore orders;
    @MockitoBean JwtDecoder decoder;

    RestClient client(String token, String spoofedAccount) {
        var builder = RestClient.builder().baseUrl("http://localhost:" + port);
        if (token != null) builder.defaultHeader("Authorization", "Bearer " + token);
        if (spoofedAccount != null) builder.defaultHeader("id-account", spoofedAccount);
        return builder.build();
    }
    void token(String token, String account, String scope) {
        when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(account)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("scope", scope).build());
    }
    @Test void signedIdentityOverridesCallerHeaderAndWorksWithoutHeader() {
        String alice = UUID.randomUUID().toString(), bob = UUID.randomUUID().toString();
        var order = orders.create(new OrderModel(alice, List.of(new OrderItem(UUID.randomUUID().toString(), 1, BigDecimal.TEN))));
        token("alice", alice, "orders:read"); token("bob", bob, "orders:read");
        assertThat(client("alice", null).get().uri("/orders/" + order.id()).retrieve().body(OrderOut.class).id()).isEqualTo(order.id());
        assertThatThrownBy(() -> client("bob", alice).get().uri("/orders/" + order.id()).retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
    @Test void healthCheckNeedsNoTokenEvenWithJwtEnabled() {
        assertThat(client(null, null).get().uri("/orders/health-check").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }
    @Test void missingInvalidTokensAndInsufficientScopesAreRejected() {
        String account = UUID.randomUUID().toString();
        assertThatThrownBy(() -> client(null, account).get().uri("/orders").retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
        assertThatThrownBy(() -> client("invalid", account).get().uri("/orders").retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        token("reader", account, "orders:read");
        assertThatThrownBy(() -> client("reader", account).post().uri("/orders").body(Map.of("items", List.of())).retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }
}
