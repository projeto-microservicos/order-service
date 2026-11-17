package store.order;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OrderSecurity {
    @Bean
    SecurityFilterChain ordersSecurity(HttpSecurity http,
            @Value("${store.security.jwt-enabled}") boolean jwtEnabled, ObjectProvider<JwtDecoder> decoder) throws Exception {
        http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (jwtEnabled) {
            http.securityMatcher("/orders", "/orders/**")
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/orders/health-check").permitAll()
                    .requestMatchers(HttpMethod.GET, "/orders", "/orders/**").hasAuthority("SCOPE_orders:read")
                    .requestMatchers(HttpMethod.POST, "/orders", "/orders/**").hasAuthority("SCOPE_orders:write")
                    .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder.getObject())));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "store.security.jwt-enabled", havingValue = "true")
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(@Value("${store.security.issuer-uri}") String issuer,
            @Value("${store.security.audience}") String audience) {
        if (issuer.isBlank() || audience.isBlank()) throw new IllegalArgumentException("JWT issuer and audience are required");
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }
}
