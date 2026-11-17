package br.com.jurisrag.security;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class TenantResolver {
    public String requireTenant(JwtAuthenticationToken authentication) {
        String tenant = authentication.getToken().getClaimAsString("tenant_id");
        if (tenant == null || tenant.isBlank() || tenant.length() > 128) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token sem tenant válido.");
        }
        return tenant;
    }
}
