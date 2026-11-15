package store.order;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StoreClients {
    private final RestClient products;
    private final RestClient exchange;
    public record Product(String id, BigDecimal price) {}
    public record Quote(BigDecimal sell, BigDecimal buy) {}
    public StoreClients(@Value("${store.product-url}") String productUrl, @Value("${store.exchange-url}") String exchangeUrl) {
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        products=RestClient.builder().requestFactory(factory).baseUrl(productUrl).build();
        exchange=RestClient.builder().requestFactory(factory).baseUrl(exchangeUrl).build();
    }
    public BigDecimal price(String id, String account) {
        try {
            Product product=products.get().uri("/products/{id}",id).header("id-account",account).retrieve().body(Product.class);
            if(product==null || product.price()==null || product.price().signum()<0) throw unavailable();
            return product.price();
        } catch(HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Product does not exist: "+id);
        } catch(RestClientException e) { throw unavailable(); }
    }
    public BigDecimal rate(String currency,String account) {
        try {
            Quote quote=exchange.get().uri("/exchanges/USD/{currency}",currency).header("id-account",account).retrieve().body(Quote.class);
            if(quote==null || quote.sell()==null || quote.sell().signum()<=0) throw unavailable();
            return quote.sell();
        } catch(HttpClientErrorException e) {
            if(e.getStatusCode().value()==422) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Currency not supported");
            throw unavailable();
        } catch(RestClientException e) { throw unavailable(); }
    }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Required service unavailable"); }
}
