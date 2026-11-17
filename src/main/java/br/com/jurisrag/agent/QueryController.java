package br.com.jurisrag.agent;

import br.com.jurisrag.security.TenantResolver;
import jakarta.validation.Valid;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryController {
    private final QueryAgent queryAgent;
    private final TenantResolver tenantResolver;

    public QueryController(QueryAgent queryAgent, TenantResolver tenantResolver) {
        this.queryAgent = queryAgent;
        this.tenantResolver = tenantResolver;
    }

    @PostMapping("/v1/queries")
    public QueryResponse query(JwtAuthenticationToken authentication, @Valid @RequestBody QueryRequest request) {
        return queryAgent.query(tenantResolver.requireTenant(authentication), request.question().trim());
    }
}
