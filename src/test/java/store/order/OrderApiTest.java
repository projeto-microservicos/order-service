package store.order;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "management.server.port=0", "spring.datasource.url=jdbc:h2:mem:orders;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password="})
class OrderApiTest {
    static final String PRODUCT="00000000-0000-0000-0000-000000000001";
    static HttpServer upstream;
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/",e -> {
            String path=e.getRequestURI().getPath(); String body; int status=200;
            if(path.equals("/products/"+PRODUCT)) body="{\"id\":\""+PRODUCT+"\",\"name\":\"Tomato\",\"price\":10.12,\"unit\":\"kg\"}";
            else if(path.equals("/exchanges/USD/BRL")) body="{\"sell\":6,\"buy\":5.9}";
            else { status=path.startsWith("/products")?404:422; body="{}"; }
            byte[] data=body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type","application/json");
            e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();
        }); upstream.start();
        registry.add("store.product-url",() -> "http://127.0.0.1:"+upstream.getAddress().getPort());
        registry.add("store.exchange-url",() -> "http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    @AfterAll static void stop() { upstream.stop(0); }
    @Test void healthCheckAnswers200WithoutIdentityOrBody() {
        var response=RestClient.create("http://localhost:"+port).get().uri("/orders/health-check").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200); assertThat(response.getBody()).isNull();
    }
    RestClient client(String account) { return RestClient.builder().baseUrl("http://localhost:"+port).defaultHeader("id-account",account).build(); }
    @Test void totalsOwnershipAndCurrencyConversion() {
        var alice=client(UUID.randomUUID().toString()); var bob=client(UUID.randomUUID().toString());
        var created=alice.post().uri("/orders").body(new OrderIn(List.of(new OrderIn.Item(PRODUCT,2)))).retrieve().toEntity(OrderOut.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        var order=created.getBody(); String path="/orders/"+order.id();
        assertThat(order.total()).isEqualByComparingTo("20.24");
        assertThat(order.items().getFirst().product().id()).isEqualTo(PRODUCT);
        var brl=alice.get().uri(path+"?currency=BRL").retrieve().body(OrderOut.class);
        assertThat(brl.total()).isEqualByComparingTo("121.44"); assertThat(brl.currency()).isEqualTo("BRL");
        assertThat(alice.get().uri(path).retrieve().body(OrderOut.class).total()).isEqualByComparingTo("20.24");
        assertThat(bob.get().uri("/orders").retrieve().body(OrderOut.Summary[].class)).isEmpty();
        assertThatThrownBy(() -> bob.get().uri(path).retrieve().toBodilessEntity()).isInstanceOf(HttpClientErrorException.NotFound.class);
        assertThatThrownBy(() -> alice.get().uri(path+"?currency=ZZZ").retrieve().toBodilessEntity())
            .isInstanceOf(HttpClientErrorException.class).satisfies(e -> assertThat(((HttpClientErrorException)e).getStatusCode().value()).isEqualTo(422));
    }
    @Test void rejectsMissingProductsAndInvalidQuantitiesWithoutPartialOrder() {
        var c=client(UUID.randomUUID().toString());
        assertThatThrownBy(() -> c.post().uri("/orders").body(new OrderIn(List.of(new OrderIn.Item(UUID.randomUUID().toString(),1)))).retrieve().toBodilessEntity()).isInstanceOf(HttpClientErrorException.BadRequest.class);
        assertThatThrownBy(() -> c.post().uri("/orders").body(new OrderIn(List.of(new OrderIn.Item(PRODUCT,0)))).retrieve().toBodilessEntity()).isInstanceOf(HttpClientErrorException.BadRequest.class);
        assertThat(c.get().uri("/orders").retrieve().body(OrderOut.Summary[].class)).isEmpty();
    }
}
