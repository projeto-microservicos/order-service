package store.order;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AccountIdentity {
    private final boolean jwtEnabled;
    public AccountIdentity(@Value("${store.security.jwt-enabled}") boolean jwtEnabled) { this.jwtEnabled = jwtEnabled; }

    public String resolve(String trustedHeader) {
        String account = trustedHeader;
        if (jwtEnabled) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) throw invalid();
            account = jwt.getSubject();
        }
        try {
            if (account == null || !UUID.fromString(account).toString().equalsIgnoreCase(account)) throw invalid();
            return account;
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(jwtEnabled ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST, "Valid account identity required");
    }
}
